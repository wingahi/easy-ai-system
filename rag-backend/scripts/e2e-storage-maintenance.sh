#!/usr/bin/env bash
# 存储迁移与孤儿清理端到端测试（批次 4）
#
# 覆盖场景：
#   M1 异步迁移任务提交：立即返回 taskId，不阻塞请求线程
#   M2 进度 / 终态查询：轮询到 COMPLETED / FAILED / CANCELLED，终态含 report 或 errorMessage
#   M3 历史列表包含本次任务
#   M4 取消：终态任务 cancel 返回 false；运行中任务取消需人工前置（显式报错，不静默跳过）
#   M5 孤儿扫描默认只报告不删除：扫描到测试孤儿对象，deleted=0，文件仍在
#
# 依赖：
#   - backend 运行中；真实 PostgreSQL（知识库引用表）
#   - Redis（迁移单飞锁失败关闭：Redis 不可用时提交会被拒绝）
#   - 本地存储目录可写（默认 rag-backend/uploads，可用 UPLOAD_DIR 覆盖）
#   - 迁移到 OSS 需要 OSS 配置；未配置时 M2 只断言 FAILED + errorMessage（这是合法终态）
#
# 用法：
#   bash scripts/e2e-storage-maintenance.sh
#   UPLOAD_DIR=/data/uploads SRV=http://127.0.0.1:8080 bash scripts/e2e-storage-maintenance.sh
#
# 环境变量：
#   SRV / DB / DB_USER / DB_HOST / DB_PORT / PGPASSWORD / PG_CONTAINER
#   REDIS_CONTAINER / REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_DB
#   ADMIN_USER / ADMIN_PASSWORD
#   UPLOAD_DIR  本地存储根目录（相对路径以 rag-backend 为基准），默认 uploads
#   E2E_TIMEOUT 单步等待上限秒数，默认 120
#   E2E_MIGRATION_RUNNING=1  表示已准备可长时间运行的迁移（已配置 OSS 且对象较多），才验证运行中取消
set -uo pipefail

SRV=${SRV:-http://localhost:8080}
MGMT_SRV=${MGMT_SRV:-http://localhost:9090}
HEALTH_URL=${HEALTH_URL:-$MGMT_SRV/actuator/health}
DB=${DB:-wgh_rag}
DB_USER=${DB_USER:-postgres}
DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-5432}
PGPASSWORD=${PGPASSWORD:-}
PG_CONTAINER=${PG_CONTAINER:-${PG:-wgh-rag-postgres}}
REDIS_CONTAINER=${REDIS_CONTAINER:-wgh-rag-redis}
REDIS_HOST=${REDIS_HOST:-127.0.0.1}
REDIS_PORT=${REDIS_PORT:-6379}
REDIS_PASSWORD=${REDIS_PASSWORD:-}
REDIS_DB=${REDIS_DB:-0}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-admin}
E2E_TIMEOUT=${E2E_TIMEOUT:-120}

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
UPLOAD_DIR=${UPLOAD_DIR:-uploads}
UPLOAD_DIR_LABEL="$UPLOAD_DIR"
case "$UPLOAD_DIR" in
  /*) ;;
  *) UPLOAD_DIR="$SCRIPT_DIR/../$UPLOAD_DIR" ;;
esac

PASS=0; FAIL=0
ok() { echo "  ✅ $1"; PASS=$((PASS+1)); }
ng() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }

if command -v docker >/dev/null 2>&1 && docker inspect "$PG_CONTAINER" >/dev/null 2>&1; then
  PSQL_MODE=container
else
  PSQL_MODE=tcp
fi
psqlq() {
  if [ "$PSQL_MODE" = container ]; then
    docker exec -i "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB" -t -A -v ON_ERROR_STOP=1 -c "$1"
  else
    PGPASSWORD="$PGPASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB" \
      -t -A -v ON_ERROR_STOP=1 -c "$1"
  fi
}

REDIS_MODE=none
if command -v docker >/dev/null 2>&1 && docker inspect "$REDIS_CONTAINER" >/dev/null 2>&1; then
  REDIS_MODE=container
elif command -v redis-cli >/dev/null 2>&1; then
  REDIS_MODE=tcp
fi
redisq() {
  if [ "$REDIS_MODE" = container ]; then
    docker exec -i "$REDIS_CONTAINER" redis-cli -n "$REDIS_DB" "$@"
  elif [ -n "$REDIS_PASSWORD" ]; then
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" -n "$REDIS_DB" "$@"
  else
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DB" "$@"
  fi
}

json_field() { # json path(a.b)
  python3 -c "
import sys,json
try:
    d=json.loads(sys.argv[1])
except Exception:
    print(''); raise SystemExit
for k in sys.argv[2].split('.'):
    if isinstance(d, dict): d=d.get(k)
    else: d=None
if d is None: d=''
print(d)" "$1" "$2" 2>/dev/null
}

echo "=============================================="
echo " 存储迁移与孤儿清理端到端测试"
echo "=============================================="
echo "  DB: $PSQL_MODE / $DB     Redis: $REDIS_MODE     SRV: $SRV"
echo "  本地存储目录：$UPLOAD_DIR_LABEL（相对 rag-backend）"

# ---------- 0. 前置 ----------
echo ""
echo "[M0] 前置检查"
if ! curl -sf "$HEALTH_URL" | grep -q '"status":"UP"'; then
  ng "后端未就绪：$HEALTH_URL 非 UP"
  exit 1
fi
ok "后端健康检查 UP"
if ! psqlq "SELECT 1" >/dev/null 2>&1; then
  ng "数据库不可用（mode=$PSQL_MODE db=$DB）"
  exit 1
fi
ok "数据库可用"
if [ "$REDIS_MODE" = none ] || [ "$(redisq ping 2>/dev/null | tr -d '\r')" != "PONG" ]; then
  ng "Redis 不可用：存储迁移单飞锁失败关闭，提交会被拒绝"
  exit 1
fi
ok "Redis 可用"
if [ ! -d "$UPLOAD_DIR" ]; then
  ng "本地存储目录不存在：$UPLOAD_DIR（请设置 UPLOAD_DIR；若后端以容器运行，需指向容器内实际可写目录）"
  exit 1
fi
ok "本地存储目录存在"

TOKEN=$(curl -s -X POST "$SRV/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}" \
  | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('token',''))" 2>/dev/null)
if [ -z "${TOKEN:-}" ]; then
  ng "登录失败：请设置 ADMIN_USER / ADMIN_PASSWORD"
  exit 1
fi
AUTH="Authorization: Bearer $TOKEN"
ok "登录成功"

RUN=$(date +%s)-$RANDOM
echo "  runId=$RUN"

# ---------- M1/M2: 异步迁移提交与终态 ----------
echo ""
echo "[M1] 异步迁移任务提交（local → oss，dryRun=true）"
SUBMIT=$(curl -s -X POST "$SRV/api/admin/storage/migrate?source=local&target=oss&dryRun=true" -H "$AUTH")
TASK_ID=$(json_field "$SUBMIT" "data.taskId")
if [ -z "${TASK_ID:-}" ]; then
  ng "提交失败：$SUBMIT"
else
  ok "接口立即返回 taskId=$TASK_ID"
  ST0=$(json_field "$SUBMIT" "data.status")
  [ "$ST0" = "RUNNING" ] && ok "初始状态 RUNNING" || echo "  ℹ 初始状态=$ST0（可能已快速进入终态）"

  echo ""
  echo "[M2] 轮询进度直至终态"
  deadline=$(( $(date +%s) + E2E_TIMEOUT ))
  FINAL=""; VIEW=""
  while [ "$(date +%s)" -lt "$deadline" ]; do
    VIEW=$(curl -s "$SRV/api/admin/storage/migrate/$TASK_ID" -H "$AUTH")
    FINAL=$(json_field "$VIEW" "data.status")
    case "$FINAL" in RUNNING|"") sleep 2;; *) break;; esac
  done
  case "$FINAL" in
    COMPLETED)
      ok "迁移 COMPLETED"
      TOTAL_K=$(json_field "$VIEW" "data.totalKnown")
      [ "$TOTAL_K" = "True" ] && ok "totalKnown=true（源端枚举完成）" || ng "totalKnown=$TOTAL_K"
      ;;
    FAILED)
      ok "迁移 FAILED（未配置 OSS 时的预期终态）"
      ERR=$(json_field "$VIEW" "data.errorMessage")
      [ -n "$ERR" ] && ok "FAILED 携带 errorMessage：${ERR:0:80}" || ng "FAILED 未携带 errorMessage"
      ;;
    CANCELLED)
      ok "迁移 CANCELLED"
      ;;
    *)
      ng "迁移未在 ${E2E_TIMEOUT}s 内进入终态：status=${FINAL} view=$VIEW"
      ;;
  esac
  # 终态语义：RUNNING 之外才算结束
  [ "$FINAL" != "RUNNING" ] && [ -n "$FINAL" ] && ok "终态查询返回稳定" || true

  echo ""
  echo "[M3] 历史列表包含本次任务"
  HIST=$(curl -s "$SRV/api/admin/storage/migrate/history?limit=20" -H "$AUTH")
  echo "$HIST" | python3 -c "
import sys,json
d=json.load(sys.stdin)
ids=[x.get('taskId') for x in (d.get('data') or [])]
sys.exit(0 if '$TASK_ID' in ids else 1)" \
    && ok "历史列表包含 taskId" || ng "历史列表不含 taskId"

  echo ""
  echo "[M4] 终态任务取消返回 false"
  CANCEL=$(curl -s -X POST "$SRV/api/admin/storage/migrate/$TASK_ID/cancel" -H "$AUTH")
  CANCELLED_FLAG=$(json_field "$CANCEL" "data")
  [ "$CANCELLED_FLAG" = "False" ] && ok "终态任务 cancel=false（幂等）" || ng "终态任务 cancel 返回：$CANCEL"
fi

echo ""
echo "[M4-b] 运行中任务取消（需人工前置）"
if [ "${E2E_MIGRATION_RUNNING:-0}" = "1" ]; then
  SUBMIT2=$(curl -s -X POST "$SRV/api/admin/storage/migrate?source=local&target=oss&dryRun=false" -H "$AUTH")
  TASK2=$(json_field "$SUBMIT2" "data.taskId")
  if [ -z "${TASK2:-}" ]; then
    ng "取消用例提交失败：$SUBMIT2"
  else
    ST2=$(json_field "$SUBMIT2" "data.status")
    if [ "$ST2" != "RUNNING" ]; then
      ng "任务未处于 RUNNING（status=$ST2），无法验证运行中取消"
    else
      CAN2=$(curl -s -X POST "$SRV/api/admin/storage/migrate/$TASK2/cancel" -H "$AUTH")
      [ "$(json_field "$CAN2" "data")" = "True" ] && ok "运行中取消请求被接受" || ng "运行中取消失败：$CAN2"
      deadline=$(( $(date +%s) + E2E_TIMEOUT ))
      while [ "$(date +%s)" -lt "$deadline" ]; do
        V2=$(curl -s "$SRV/api/admin/storage/migrate/$TASK2" -H "$AUTH")
        S2=$(json_field "$V2" "data.status")
        case "$S2" in RUNNING|"") sleep 2;; *) break;; esac
      done
      [ "$S2" = "CANCELLED" ] && ok "任务收敛为 CANCELLED（协作式停止派发）" || ng "取消后终态=${S2}"
    fi
  fi
else
  ng "运行中取消未执行：需要已配置 OSS 且对象较多的迁移任务，先准备后以 E2E_MIGRATION_RUNNING=1 重跑。"
  echo "      具体步骤见 docs/testing/test-cases.md §45.3（本用例禁止静默跳过）。"
fi

# ---------- M5: 孤儿扫描不删除 ----------
echo ""
echo "[M5] 孤儿扫描（delete=false）只报告不删除"
ORPHAN_NAME="e2e-orphan-$RUN.txt"
ORPHAN_PATH="$UPLOAD_DIR/$ORPHAN_NAME"
printf 'e2e orphan fixture %s\n' "$RUN" > "$ORPHAN_PATH"
ok "已放置测试孤儿对象：$ORPHAN_NAME"
SCAN=$(curl -s -X POST "$SRV/api/admin/storage/orphans/scan?delete=false" -H "$AUTH")
SCAN_TYPE=$(json_field "$SCAN" "data.storageType")
SCAN_ORPHANS=$(json_field "$SCAN" "data.orphans")
SCAN_DELETED=$(json_field "$SCAN" "data.deleted")
SCAN_SCANNED=$(json_field "$SCAN" "data.scanned")
[ -n "$SCAN_TYPE" ] && ok "扫描返回 storageType=$SCAN_TYPE" || ng "扫描失败：$SCAN"
[ "${SCAN_SCANNED:-0}" -ge 1 ] 2>/dev/null && ok "扫描到 $SCAN_SCANNED 个对象" || ng "scanned=${SCAN_SCANNED}"
[ "${SCAN_ORPHANS:-0}" -ge 1 ] 2>/dev/null && ok "识别出孤儿对象 $SCAN_ORPHANS 个" || ng "orphans=${SCAN_ORPHANS}"
[ "$SCAN_DELETED" = "0" ] && ok "delete=false 时 deleted=0（未删除任何对象）" || ng "deleted=${SCAN_DELETED}"
[ -f "$ORPHAN_PATH" ] && ok "测试孤儿文件仍存在（未被删除）" || ng "测试孤儿文件被删除（违反只报告语义）"
echo "$SCAN" | grep -q "$ORPHAN_NAME" && ok "样例中包含测试孤儿对象" || ng "样例未包含测试孤儿对象"
rm -f "$ORPHAN_PATH"

# ---------- 汇总 ----------
echo ""
echo "=============================================="
echo " 结果：通过 $PASS / 失败 $FAIL"
echo "=============================================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
