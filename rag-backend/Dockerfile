# syntax=docker/dockerfile:1
# ============================================================
# 后端镜像：多阶段构建（Maven 构建 + JRE 运行）
# 构建上下文：rag-backend/
# ============================================================

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -B -Dmaven.test.skip=true -pl rag-app -am package

FROM eclipse-temurin:21-jre-alpine

# 非 root 运行；日志目录与 prod profile 默认路径保持一致
RUN addgroup -S wgh && adduser -S -G wgh wgh \
    && mkdir -p /app /var/log/wgh-rag \
    && chown -R wgh:wgh /app /var/log/wgh-rag

WORKDIR /app
COPY --from=build --chown=wgh:wgh /build/rag-app/target/rag-app-*.jar /app/app.jar

USER wgh

# 容器内存感知：按 cgroup 限额的 75% 设置堆，避免容器被 OOM Kill
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:MaxMetaspaceSize=256m" \
    WGH_PROFILE=prod \
    SERVER_PORT=8080 \
    WGH_MANAGEMENT_PORT=9090

EXPOSE 8080 9090

# 管理端口探活（业务端口不含 actuator）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://127.0.0.1:9090/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
