# pgvector 0.8.7 trusted 派生镜像（仅 dev 使用）
#
# 背景：官方 pgvector/pgvector:pg15 的 vector.control 未声明 trusted，
# 非超级用户（wgh_app）即使持有库级 CREATE 也不能执行 CREATE EXTENSION vector。
#
# 做法：只追加一行 trusted = true，不动 control 的其他字段；
# 基础镜像按 digest 锁定保证可重复构建，构建期断言防止上游变更后静默失效。
#
# 构建：docker build -t wgh/pgvector:pg15-0.8.7-trusted \
#         -f rag-backend/docker/pgvector/Dockerfile rag-backend/docker/pgvector
ARG PG_MAJOR=15
ARG PGVECTOR_VERSION=0.8.7

FROM pgvector/pgvector@sha256:47af0b65960b14f1c6945aa4ccae37836c632e9a6fe0eea9160936e616e5b2e9

ARG PG_MAJOR=15
ARG PGVECTOR_VERSION=0.8.7

LABEL org.opencontainers.image.title="wgh pgvector trusted" \
      org.opencontainers.image.description="pgvector/pgvector:pg15 with vector.control marked trusted" \
      org.opencontainers.image.base.digest="sha256:47af0b65960b14f1c6945aa4ccae37836c632e9a6fe0eea9160936e616e5b2e9" \
      org.opencontainers.image.base.name="pgvector/pgvector:pg15" \
      org.opencontainers.image.version="pg15-${PGVECTOR_VERSION}-trusted"

RUN set -eux; \
    test "$(pg_config --sharedir)" = "/usr/share/postgresql/${PG_MAJOR}"; \
    control="$(pg_config --sharedir)/extension/vector.control"; \
    test -f "$control"; \
    grep -qx "default_version = '${PGVECTOR_VERSION}'" "$control"; \
    grep -qx 'relocatable = true' "$control"; \
    grep -q '^module_pathname = ' "$control"; \
    cp "$control" /tmp/vector.control.orig; \
    if grep -q '^trusted' "$control"; then \
        sed -i 's/^trusted[[:space:]]*=.*/trusted = true/' "$control"; \
    else \
        printf '%s\n' 'trusted = true' >> "$control"; \
    fi; \
    test "$(grep -c '^trusted' "$control")" -eq 1; \
    grep -qx 'trusted = true' "$control"; \
    test "$(grep -v '^trusted' "$control")" = "$(cat /tmp/vector.control.orig)"; \
    rm -f /tmp/vector.control.orig; \
    printf 'vector.control trusted=%s\n' "$(grep '^trusted' "$control")"
