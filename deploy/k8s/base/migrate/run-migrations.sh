#!/bin/sh
# 数据库迁移入口脚本（在迁移 Job 的 postgres:15-alpine 容器内执行）。
#
# 设计要点：
#   1. 顺序固定：基线 schema → 按日期目录顺序执行 migrations → 可选种子数据；
#   2. 每个脚本独立事务（脚本内部自带 BEGIN/COMMIT），失败即整体退出（ON_ERROR_STOP）；
#   3. 现有脚本均使用 IF NOT EXISTS / ON CONFLICT 等幂等写法，可重复执行；
#   4. 种子数据含示例模型，默认不执行，需显式 RUN_SEED=true。
#
# 维护方式：新增 sql/migrations/<YYYYMMDD>/NN_*.sql 后，
#   需同步在 base/kustomization.yaml 的 configMapGenerator.files 中登记，
#   并在本文件末尾按顺序追加 run_sql 调用（ConfigMap 的 key 是文件名，不含日期目录）。
set -eu

: "${PGHOST:=postgres}"
: "${PGPORT:=5432}"
: "${PGDATABASE:=wgh_rag_prod}"
: "${PGUSER:=postgres}"
: "${RUN_SEED:=false}"

SCRIPT_DIR="$(dirname "$0")"

echo "[migrate] target ${PGHOST}:${PGPORT}/${PGDATABASE} user=${PGUSER}"
echo "[migrate] waiting for database ..."
i=0
until pg_isready -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "${i}" -ge 60 ]; then
    echo "[migrate] database not ready after 120s" >&2
    exit 1
  fi
  sleep 2
done
echo "[migrate] database is ready"

run_sql() {
  echo "[migrate] ==> ${1}"
  psql -v ON_ERROR_STOP=1 \
    -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" \
    -f "${SCRIPT_DIR}/${1}"
}

# 基线（新建库）；对存量库同样幂等
run_sql 01-schema.sql

# 存量库增量迁移（按日期目录顺序）
run_sql 01_resource_visibility.sql
run_sql 01_eval_dataset_versioning.sql
run_sql 02_kb_connector.sql
run_sql 03_upload_session.sql
run_sql 04_llm_model_capability.sql
run_sql 05_agent_memory.sql
run_sql 01_execution_job.sql
run_sql 02_quota_charge.sql
run_sql 01_execution_job_trace.sql

# 种子数据：含示例模型占位 Key，生产默认不执行
if [ "${RUN_SEED}" = "true" ]; then
  run_sql 02-data.sql
else
  echo "[migrate] skip seed data (RUN_SEED=${RUN_SEED})"
fi

echo "[migrate] all scripts applied"
