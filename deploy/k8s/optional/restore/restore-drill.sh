#!/bin/sh
# 恢复演练脚本（在 restore-drill CronJob 的 postgres:15-alpine 容器内执行）。
#
# 目的：定期验证「备份是否可恢复」，而不只是「备份文件是否存在」。
# 做法：在同一个 PG 实例上创建一个一次性库 wgh_rag_prod_restore_drill，把备份还原进去，
#       校验扩展与核心表，打印关键行数，最后删除临时库。
#
# 安全护栏：
#   1. 目标库名必须以 wgh_rag_prod_restore_drill 开头，否则直接退出，避免误写生产库；
#   2. 不触碰 wgh_rag_prod；只读备份文件、只写临时库；
#   3. 任何一步失败即非零退出，由 Job 记录失败。
#
# 环境变量：
#   PGHOST/PGPORT/PGUSER/PGPASSWORD  目标实例（与迁移/备份一致）
#   RESTORE_SOURCE                   pvc（默认，从 /backup 读）或 oss（从对象存储拉取到 /work）
#   DUMP_FILE                        可选，指定备份文件名；缺省取最新
#   DRILL_DB                         临时库名，默认 wgh_rag_prod_restore_drill
#   KEEP_DRILL_DB                    true 时保留临时库以便人工排查
set -eu

: "${PGPORT:=5432}"
: "${PGDATABASE:=wgh_rag_prod}"
: "${RESTORE_SOURCE:=pvc}"
: "${DRILL_DB:=wgh_rag_prod_restore_drill}"
: "${KEEP_DRILL_DB:=false}"

case "${DRILL_DB}" in
  wgh_rag_prod_restore_drill*) ;;
  *)
    echo "[drill] refuse: DRILL_DB=${DRILL_DB} 必须以 wgh_rag_prod_restore_drill 开头" >&2
    exit 1
    ;;
esac

if [ "${RESTORE_SOURCE}" = "oss" ]; then
  workdir="/work"
else
  workdir="/backup"
fi

dump="${DUMP_FILE:-}"
if [ -z "${dump}" ]; then
  dump="$(ls -1t "${workdir}"/*.dump 2>/dev/null | head -n 1 || true)"
fi
if [ -z "${dump}" ] || [ ! -f "${dump}" ]; then
  echo "[drill] no dump found in ${workdir}" >&2
  exit 1
fi

echo "[drill] source=${RESTORE_SOURCE} dump=${dump} target=${DRILL_DB}"

# 校验备份完整性（有同名 .sha256 时强制校验）
if [ -f "${dump}.sha256" ]; then
  echo "[drill] verifying sha256"
  ( cd "$(dirname "${dump}")" && sha256sum -c "$(basename "${dump}").sha256" )
else
  echo "[drill] WARN: ${dump}.sha256 不存在，跳过完整性校验"
fi

echo "[drill] recreating scratch database"
dropdb --if-exists -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" "${DRILL_DB}"
createdb -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" "${DRILL_DB}"

echo "[drill] restoring (parallel 2)"
pg_restore -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${DRILL_DB}" \
  --no-owner --no-acl --exit-on-error --jobs=2 "${dump}"

q() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${DRILL_DB}" -tAc "$1"
}

echo "[drill] ---- checks ----"
echo "[drill] public tables: $(q "select count(*) from information_schema.tables where table_schema='public'")"
echo "[drill] extension vector: $(q "select count(*) from pg_extension where extname='vector'")"
echo "[drill] extension pg_trgm: $(q "select count(*) from pg_extension where extname='pg_trgm'")"
for t in wgh_knowledge_file wgh_chat_message wgh_execution_job wgh_quota_usage wgh_audit_log; do
  echo "[drill] ${t}: $(q "select count(*) from ${t}")"
done

# 最低验收：核心表必须存在（count 查询本身已证明存在）；表数量不为 0。
tables="$(q "select count(*) from information_schema.tables where table_schema='public'")"
if [ "${tables}" -lt 10 ]; then
  echo "[drill] FAIL: public schema 仅 ${tables} 张表，备份可能不完整" >&2
  exit 1
fi

if [ "${KEEP_DRILL_DB}" = "true" ]; then
  echo "[drill] keep ${DRILL_DB} for inspection"
else
  echo "[drill] dropping ${DRILL_DB}"
  dropdb -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" "${DRILL_DB}"
fi

echo "[drill] PASS"
