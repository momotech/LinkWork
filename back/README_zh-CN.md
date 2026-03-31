# LinkWork Back

`LinkWork/back` 是 LinkWork 的 Spring Boot 后端服务，提供任务编排、岗位管理、审批、镜像构建、MCP/Skills 管理、WebSocket 事件流等核心 API。

## 技术栈

- Java 21
- Spring Boot 3.2.5
- MyBatis-Plus + MySQL
- Redis
- Fabric8 Kubernetes Client
- 依赖 `io.linkwork:*`（来自 `linkwork-server`）

## 本地开发

### 1) 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8+
- Redis 7+

### 2) 构建

```bash
cd LinkWork/back
mvn -DskipTests clean package spring-boot:repackage
```

### 3) 启动

```bash
java -jar target/*.jar
```

默认端口：`8081`

健康检查：`GET /api/v1/health`

## 关键环境变量

| 变量 | 说明 |
|---|---|
| `LINKWORK_DB_URL` | MySQL JDBC 地址 |
| `LINKWORK_DB_USERNAME` / `LINKWORK_DB_PASSWORD` | 数据库账号密码 |
| `LINKWORK_REDIS_HOST` / `LINKWORK_REDIS_PORT` | Redis 连接信息 |
| `ROBOT_AUTH_PASSWORD` | 登录密码 BCrypt 哈希 |
| `ROBOT_AUTH_JWT_SECRET` | JWT 密钥（建议 32+ 字符） |
| `ROBOT_LITELLM_BASE_URL` / `ROBOT_LITELLM_API_KEY` | LLM 网关配置 |
| `IMAGE_REGISTRY` / `DEFAULT_AGENT_BASE_IMAGE` | 角色镜像构建配置 |

完整示例可参考仓库根目录：`LinkWork/.env.example` 与 `src/main/resources/application.yml`。

## Deploy 流程

### 方案 A：工作区一键部署到远端主机（推荐）

在工作区根目录（`link-work`）执行：

```bash
GG_HOST=<remote_host> \
REMOTE_USER=<remote_user> \
BRANCH=<target_branch> \
BACKEND_PORT=8081 \
./deploy-linkwork-backend-gg.local.sh
```

脚本会自动完成：

1. 远端拉取 `LinkWork` + `linkwork-server`
2. （可选）重建 MySQL schema
3. Maven 构建依赖与后端 jar
4. 构建并启动 `linkwork-backend` 容器
5. `curl /api/v1/health` 健康检查

### 方案 B：手动容器发布

```bash
cd LinkWork/back
mvn -DskipTests clean package spring-boot:repackage
cp target/*.jar target/app.jar

cat > Dockerfile.deploy <<'DOCKERFILE'
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/app.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
DOCKERFILE

docker build -f Dockerfile.deploy -t linkwork-backend:latest .
docker run -d --name linkwork-backend -p 8081:8081 linkwork-backend:latest
```
