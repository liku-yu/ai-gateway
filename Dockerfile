# ============================================================
# AI 网关镜像（多阶段构建）
#
# 构建：docker build -t ai-gateway:latest .
# 运行：见 docker-compose.yml（推荐），或
#   docker run --rm -p 8080:8080 \
#     -e GW_CRYPTO_MASTER_KEY=... -e GW_API_KEY_SALT=... -e GW_ADMIN_PASSWORD=... \
#     -e SPRING_DATASOURCE_URL='jdbc:mariadb://<db>:3306/ai_gateway' \
#     -e GW_DB_USER=gw -e GW_DB_PASSWORD=... -e SPRING_DATA_REDIS_HOST=<redis> \
#     ai-gateway:latest
#
# 注意：镜像内不含任何密钥；敏感配置全部由运行时环境变量注入，
# 缺失/默认时会由 SecurityEnvironmentPostProcessor 直接拒绝启动。
# ============================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 可选：Maven 镜像加速。默认阿里云（国内快）；海外可改回官方：
#   docker compose build --build-arg MAVEN_MIRROR=https://repo1.maven.org/maven2
ARG MAVEN_MIRROR=https://maven.aliyun.com/repository/public
RUN mkdir -p /root/.m2 \
 && printf '%s' \
      '<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf><url>'"$MAVEN_MIRROR"'</url></mirror></mirrors></settings>' \
      > /root/.m2/settings.xml

# 先只复制 pom 拉依赖；用 BuildKit 缓存挂载把 ~/.m2 跨构建保留，
# 避免每次构建都从零下载整个依赖树（这正是首次构建慢的原因）。
# 不加 -q，保留下载/构建进度输出，避免看起来像“卡住”。
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -DskipTests dependency:go-offline || true

COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -DskipTests package \
    && cp target/ai-gateway-*.jar /build/app.jar

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 -m app

WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar

USER app
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

HEALTHCHECK --interval=15s --timeout=5s --start-period=50s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
