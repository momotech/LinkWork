package com.linkwork.service.memory;

import com.linkwork.config.MemoryConfig;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
@Service
@RequiredArgsConstructor
public class MilvusStoreService {

    private final MemoryConfig memoryConfig;
    private MilvusClientV2 client;
    private final Set<String> knownCollections = ConcurrentHashMap.newKeySet();
    private volatile boolean available = false;
    private final AtomicBoolean unavailableWarned = new AtomicBoolean(false);

    static final List<String> QUERY_FIELDS = List.of(
            "content", "source", "heading", "chunk_hash",
            "heading_level", "start_line", "end_line", "file_type", "indexed_at"
    );

    @PostConstruct
    public void init() {
        if (!memoryConfig.isEnabled()) {
            log.info("Memory service disabled, skipping Milvus connection");
            return;
        }
        try {
            ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                    .uri(memoryConfig.getMilvus().getUri());
            String token = memoryConfig.getMilvus().getToken();
            if (token != null && !token.isBlank()) {
                builder.token(token);
            }
            client = new MilvusClientV2(builder.build());
            available = true;
            unavailableWarned.set(false);
            log.info("Connected to Milvus at {}", memoryConfig.getMilvus().getUri());
        } catch (Exception e) {
            available = false;
            log.warn("Milvus unavailable at startup, memory features degraded: {}", e.getMessage());
            log.debug("Milvus init failure details", e);
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing Milvus client", e);
            }
        }
    }

    public void ensureCollection(String collectionName) {
        if (!isAvailable("ensureCollection")) {
            return;
        }
        if (knownCollections.contains(collectionName)) {
            return;
        }
        try {
            boolean exists = client.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build());
            if (exists) {
                knownCollections.add(collectionName);
                return;
            }
            createCollection(collectionName);
            knownCollections.add(collectionName);
        } catch (Exception e) {
            markUnavailable();
            log.error("Failed to ensure collection {}: {}", collectionName, e.getMessage(), e);
            throw new RuntimeException("Milvus collection setup failed", e);
        }
    }

    private void createCollection(String collectionName) {
        int dim = memoryConfig.getEmbedding().getDimension();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(true)
                .build();

        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_hash").dataType(DataType.VarChar).maxLength(64).isPrimaryKey(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("embedding").dataType(DataType.FloatVector).dimension(dim).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("content").dataType(DataType.VarChar).maxLength(65535)
                .enableAnalyzer(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("sparse_vector").dataType(DataType.SparseFloatVector).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("source").dataType(DataType.VarChar).maxLength(1024).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("heading").dataType(DataType.VarChar).maxLength(1024).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("heading_level").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("start_line").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("end_line").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("file_type").dataType(DataType.VarChar).maxLength(32).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("indexed_at").dataType(DataType.Int64).build());

        schema.addFunction(CreateCollectionReq.Function.builder()
                .name("bm25_fn")
                .functionType(FunctionType.BM25)
                .inputFieldNames(Collections.singletonList("content"))
                .outputFieldNames(Collections.singletonList("sparse_vector"))
                .build());

        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .build());
        indexParams.add(IndexParam.builder()
                .fieldName("sparse_vector")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build());

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .indexParams(indexParams)
                .build());

        log.info("Created Milvus collection: {} (dim={})", collectionName, dim);
    }

    public int upsert(String collectionName, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return 0;
        if (!isAvailable("upsert")) return 0;
        ensureCollection(collectionName);
        if (!isAvailable("upsert")) return 0;
        // Convert List<Map> to List<JsonObject> for Milvus SDK
        List<com.google.gson.JsonObject> jsonRecords = records.stream().map(record -> {
            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            record.forEach((k, v) -> {
                if (v instanceof String) json.addProperty(k, (String) v);
                else if (v instanceof Number) json.addProperty(k, (Number) v);
                else if (v instanceof Boolean) json.addProperty(k, (Boolean) v);
                else if (v != null) json.addProperty(k, v.toString());
            });
            return json;
        }).collect(java.util.stream.Collectors.toList());
        try {
            UpsertResp resp = client.upsert(UpsertReq.builder()
                    .collectionName(collectionName)
                    .data(jsonRecords)
                    .build());
            return (int) resp.getUpsertCnt();
        } catch (Exception e) {
            markUnavailable();
            log.warn("Milvus upsert degraded: collection={}, error={}", collectionName, e.getMessage());
            return 0;
        }
    }

    /**
     * Hybrid search: dense cosine + BM25 sparse + RRF reranking.
     */
    public List<Map<String, Object>> search(
            String collectionName, List<Float> queryEmbedding, String queryText, int topK) {
        if (!isAvailable("search")) return Collections.emptyList();
        ensureCollection(collectionName);
        if (!isAvailable("search")) return Collections.emptyList();

        List<AnnSearchReq> searchRequests = new ArrayList<>();

        searchRequests.add(AnnSearchReq.builder()
                .vectorFieldName("embedding")
                .vectors(Collections.singletonList(new FloatVec(queryEmbedding)))
                .params("{\"metric_type\": \"COSINE\"}")
                .topK(topK)
                .build());

        if (queryText != null && !queryText.isBlank()) {
            searchRequests.add(AnnSearchReq.builder()
                    .vectorFieldName("sparse_vector")
                    .vectors(Collections.singletonList(new EmbeddedText(queryText)))
                    .topK(topK)
                    .build());
        }

        SearchResp resp;
        try {
            resp = client.hybridSearch(HybridSearchReq.builder()
                    .collectionName(collectionName)
                    .searchRequests(searchRequests)
                    .ranker(new RRFRanker(60))
                    .topK(topK)
                    .outFields(QUERY_FIELDS)
                    .build());
        } catch (Exception e) {
            markUnavailable();
            log.warn("Milvus search degraded: collection={}, error={}", collectionName, e.getMessage());
            return Collections.emptyList();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        if (resp.getSearchResults() != null && !resp.getSearchResults().isEmpty()) {
            for (SearchResp.SearchResult hit : resp.getSearchResults().get(0)) {
                Map<String, Object> row = new HashMap<>(hit.getEntity());
                row.put("score", hit.getScore());
                results.add(row);
            }
        }
        return results;
    }

    public List<Map<String, Object>> query(String collectionName, String filterExpr) {
        if (!isAvailable("query")) return Collections.emptyList();
        ensureCollection(collectionName);
        if (!isAvailable("query")) return Collections.emptyList();
        String filter = (filterExpr != null && !filterExpr.isBlank()) ? filterExpr : "chunk_hash != \"\"";
        QueryResp resp;
        try {
            resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter(filter)
                    .outputFields(QUERY_FIELDS)
                    .build());
        } catch (Exception e) {
            markUnavailable();
            log.warn("Milvus query degraded: collection={}, error={}", collectionName, e.getMessage());
            return Collections.emptyList();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        if (resp.getQueryResults() != null) {
            for (QueryResp.QueryResult r : resp.getQueryResults()) {
                results.add(new HashMap<>(r.getEntity()));
            }
        }
        return results;
    }

    public Set<String> hashesBySource(String collectionName, String source) {
        if (!isAvailable("hashesBySource")) return Collections.emptySet();
        String filter = "source == \"" + source.replace("\"", "\\\"") + "\"";
        QueryResp resp;
        try {
            resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter(filter)
                    .outputFields(Collections.singletonList("chunk_hash"))
                    .build());
        } catch (Exception e) {
            markUnavailable();
            log.warn("Milvus hashes query degraded: collection={}, error={}", collectionName, e.getMessage());
            return Collections.emptySet();
        }
        Set<String> hashes = new HashSet<>();
        if (resp.getQueryResults() != null) {
            for (QueryResp.QueryResult r : resp.getQueryResults()) {
                Object hash = r.getEntity().get("chunk_hash");
                if (hash != null) hashes.add(hash.toString());
            }
        }
        return hashes;
    }

    public void deleteBySource(String collectionName, String source) {
        if (!isAvailable("deleteBySource")) return;
        String filter = "source == \"" + source.replace("\"", "\\\"") + "\"";
        try {
            client.delete(DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter(filter)
                    .build());
        } catch (Exception e) {
            markUnavailable();
            log.warn("Milvus deleteBySource degraded: collection={}, error={}", collectionName, e.getMessage());
            return;
        }
        log.info("Deleted chunks for source {} from {}", source, collectionName);
    }

    public void deleteByHashes(String collectionName, List<String> hashes) {
        if (hashes == null || hashes.isEmpty()) return;
        if (!isAvailable("deleteByHashes")) return;
        try {
            client.delete(DeleteReq.builder()
                    .collectionName(collectionName)
                    .ids(new ArrayList<>(hashes))
                    .build());
        } catch (Exception e) {
            markUnavailable();
            log.warn("Milvus deleteByHashes degraded: collection={}, error={}", collectionName, e.getMessage());
        }
    }

    public long count(String collectionName) {
        try {
            if (!isAvailable("count")) return 0;
            ensureCollection(collectionName);
            if (!isAvailable("count")) return 0;
            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("chunk_hash != \"\"")
                    .outputFields(Collections.singletonList("chunk_hash"))
                    .build());
            return resp.getQueryResults() != null ? resp.getQueryResults().size() : 0;
        } catch (Exception e) {
            markUnavailable();
            return 0;
        }
    }

    /**
     * Query recent chunks ordered by indexed_at descending.
     */
    public List<Map<String, Object>> recent(String collectionName, int limit) {
        if (!isAvailable("recent")) return Collections.emptyList();
        ensureCollection(collectionName);
        if (!isAvailable("recent")) return Collections.emptyList();
        QueryResp resp;
        try {
            resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("chunk_hash != \"\"")
                    .outputFields(QUERY_FIELDS)
                    .build());
        } catch (Exception e) {
            markUnavailable();
            log.warn("Milvus recent degraded: collection={}, error={}", collectionName, e.getMessage());
            return Collections.emptyList();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        if (resp.getQueryResults() != null) {
            for (QueryResp.QueryResult r : resp.getQueryResults()) {
                results.add(new HashMap<>(r.getEntity()));
            }
        }
        results.sort((a, b) -> {
            long ta = a.get("indexed_at") != null ? ((Number) a.get("indexed_at")).longValue() : 0;
            long tb = b.get("indexed_at") != null ? ((Number) b.get("indexed_at")).longValue() : 0;
            return Long.compare(tb, ta);
        });
        return results.subList(0, Math.min(limit, results.size()));
    }

    private boolean isAvailable(String operation) {
        if (!memoryConfig.isEnabled()) {
            return false;
        }
        if (client != null && available) {
            return true;
        }
        if (unavailableWarned.compareAndSet(false, true)) {
            log.warn(
                    "Milvus unavailable, memory operation degraded: op={}, uri={}",
                    operation,
                    memoryConfig.getMilvus().getUri()
            );
        }
        return false;
    }

    private void markUnavailable() {
        available = false;
        unavailableWarned.set(false);
    }
}
