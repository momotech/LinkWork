# LinkWork Back

English | [中文](./README_zh-CN.md)

`LinkWork/back` is the Spring Boot backend service of LinkWork. It provides task orchestration, role management, approval flow, image build APIs, MCP/Skills management, and WebSocket event streaming.

## Tech Stack

- Java 21
- Spring Boot 3.2.5
- MyBatis-Plus + MySQL
- Redis
- Fabric8 Kubernetes Client
- `io.linkwork:*` starters from `linkwork-server`

## Local Development

### 1) Requirements

- JDK 21
- Maven 3.9+
- MySQL 8+
- Redis 7+

### 2) Build

```bash
cd LinkWork/back
mvn -DskipTests clean package spring-boot:repackage
```

### 3) Run

```bash
java -jar target/*.jar
```

Default port: `8081`  
Health check: `GET /api/v1/health`

## Key Environment Variables

| Variable | Description |
|---|---|
| `LINKWORK_DB_URL` | MySQL JDBC URL |
| `LINKWORK_DB_USERNAME` / `LINKWORK_DB_PASSWORD` | DB credentials |
| `LINKWORK_REDIS_HOST` / `LINKWORK_REDIS_PORT` | Redis connection |
| `ROBOT_AUTH_PASSWORD` | BCrypt hash for login password |
| `ROBOT_AUTH_JWT_SECRET` | JWT secret (recommended 32+ chars) |
| `ROBOT_LITELLM_BASE_URL` / `ROBOT_LITELLM_API_KEY` | LLM gateway settings |
| `IMAGE_REGISTRY` / `DEFAULT_AGENT_BASE_IMAGE` | Role image build settings |

See also: `LinkWork/.env.example` and `src/main/resources/application.yml`.

## Deploy Flow

### Option A: One-command remote deploy (recommended)

Run from workspace root (`link-work`):

```bash
GG_HOST=<remote_host> \
REMOTE_USER=<remote_user> \
BRANCH=<target_branch> \
BACKEND_PORT=8081 \
./deploy-linkwork-backend-gg.local.sh
```

The script performs:

1. Clone/pull `LinkWork` and `linkwork-server` on remote
2. Optional MySQL schema reset
3. Maven build for dependencies and backend JAR
4. Build and run `linkwork-backend` Docker container
5. Health check via `/api/v1/health`

### Option B: Manual Docker deployment

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
