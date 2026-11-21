#!/usr/bin/env bash
# 调度中心端到端测试（批次 4）
#
# 覆盖场景：
#   S1 作业提交 → 领取(LEASED) → 运行(RUNNING) → 终态(SUCCEEDED)，并真实执行 WF_RUN
#   S2 后台车道 / 维护车道分别可执行
#   S3 租约过期回收：未超上限回 PENDING 并被重新领取；超上限置 DEAD(LEASE_LOST)
#   S4 可重试失败 → FAILED(退避) → 重新入队 → 超上限 DEAD
#   S5 死信：未注册作业类型 / 负载不兼容直接 DEAD
#   S6 取消：领取后检测到取消标记 → CANCELLED，且不产生业务副作用
#   S7 (job_type, biz_key) 幂等去重（部分唯一索引 + 冲突不新增）
#   S8 维护车道 Redis 单飞：锁被占用时不执行、回 PENDING 并回滚 attempt；释放后恢复执行
#
# 依赖（缺一不可，缺失会显式报错并以非 0 退出）：
#   - backend 运行中（默认 http://localhost:8080）
#   - 真实 PostgreSQL（H2 不支持 FOR UPDATE SKIP LOCKED / JSONB / 部分唯一索引 / make_interval）
#   - Redis（维护车道单飞为失败关闭，未启用时维护作业不会执行）
#
# 用法：
#   bash scripts/e2e-scheduler.sh
#   SRV=http://127.0.0.1:8080 PG_CONTAINER=my-pg REDIS_HOST=127.0.0.1 bash scripts/e2e-scheduler.sh
#
# 环境变量：
#   SRV                后端基地址，默认 http://localhost:8080
#   DB / DB_USER / DB_HOST / DB_PORT / PGPASSWORD   数据库连接信息
#   PG_CONTAINER       通过 docker exec 访问数据库的容器名（默认 wgh-rag-postgres；
#                      未提供且容器不存在时退回 TCP 连接）
#   REDIS_CONTAINER    通过 docker exec 访问 Redis 的容器名（默认 wgh-rag-redis）
#   REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_DB
#   REDIS_KEY_PREFIX   Redis key 命名空间根，默认 wgh（对应 WGH_REDIS_KEY_PREFIX）
#   ADMIN_USER / ADMIN_PASSWORD  登录账号，默认 admin / admin
#   E2E_TIMEOUT        单步等待上限秒数，默认 180
set -uo pipefail

SRV=${SRV:-http://localhost:8080}
MGMT_SRV=${MGMT_SRV:-http://localhost:9090}
HEALTH_URL=${HEALTH_URL:-$MGMT_SRV/actuator/health}
DB=${DB:-wgh_rag}
DB_TIMEZONE=${DB_TIMEZONE:-Asia/Shanghai}
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
REDIS_KEY_PREFIX=${REDIS_KEY_PREFIX:-wgh}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-admin}
E2E_TIMEOUT=${E2E_TIMEOUT:-180}

PASS=0; FAIL=0
ok() { echo "  ✅ $1"; PASS=$((PASS+1)); }
ng() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }

# ---------- 连接封装 ----------
if command -v docker >/dev/null 2>&1 && docker inspect "$PG_CONTAINER" >/dev/null 2>&1; then
  PSQL_MODE=container
else
  PSQL_MODE=tcp
fi
psqlq() {
  if [ "$PSQL_MODE" = container ]; then
    docker exec -e PGTZ="$DB_TIMEZONE" -i "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB" -t -A -v ON_ERROR_STOP=1 -c "$1"
  else
    PGTZ="$DB_TIMEZONE" PGPASSWORD="$PGPASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB" \
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
  else
    if [ -n "$REDIS_PASSWORD" ]; then
      redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" -n "$REDIS_DB" "$@"
    else
      redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DB" "$@"
    fi
  fi
}

# 轮询直到 SQL 结果等于期望值；超时返回 1
wait_value() { # sql expected timeout
  local deadline=$(( $(date +%s) + ${3:-$E2E_TIMEOUT} )) value
  while [ "$(date +%s)" -lt "$deadline" ]; do
    value=$(psqlq "$1" 2>/dev/null | tr -d '[:space:]')
    [ "$value" = "$2" ] && return 0
    sleep 2
  done
  return 1
}

# 轮询直到作业进入终态，回显最终状态
wait_job_status() { # execution_id expected_list timeout
  local deadline=$(( $(date +%s) + ${3:-$E2E_TIMEOUT} )) st
  while [ "$(date +%s)" -lt "$deadline" ]; do
    st=$(psqlq "SELECT status FROM wgh_execution_job WHERE execution_id='$1' AND deleted=0")
    case " $2 " in *" $st "*) echo "$st"; return 0;; esac
    sleep 2
  done
  echo "$st"
  return 1
}

job_field() { # execution_id column
  psqlq "SELECT coalesce($2::text,'<null>') FROM wgh_execution_job WHERE execution_id='$1' AND deleted=0"
}

insert_job() { # execution_id job_type lane payload_json extra_columns_clause
  psqlq "INSERT INTO wgh_execution_job
           (execution_id, job_type, lane, tenant_id, payload_json, payload_schema_version,
            status, scheduled_at, max_attempts ${5:+,$5})
         VALUES
           ('$1', '$2', '$3', 'default', '$4'::jsonb, 1,
            'PENDING', now(), 3 ${6:+,$6})
         RETURNING id"
}

echo "=============================================="
echo " 调度中心端到端测试（真实 PostgreSQL + Redis）"
echo "=============================================="
echo "  DB: $PSQL_MODE / $DB     Redis: $REDIS_MODE     SRV: $SRV"

# ---------- 0. 前置检查 ----------
echo ""
echo "[S0] 前置检查"
if ! curl -sf "$HEALTH_URL" | grep -q '"status":"UP"'; then
  ng "后端未就绪：$HEALTH_URL 非 UP（调度中心用例必须运行后端，不能只连库）"
  exit 1
fi
ok "后端健康检查 UP"

if ! psqlq "SELECT 1" >/dev/null 2>&1; then
  ng "数据库不可用（mode=$PSQL_MODE db=${DB}）：请设置 DB_HOST/DB_PORT/DB_USER/PGPASSWORD 或 PG_CONTAINER"
  exit 1
fi
ok "数据库可用（${PSQL_MODE}）"

if ! psqlq "SELECT to_regclass('public.wgh_execution_job') IS NOT NULL" | grep -q t; then
  ng "缺少 wgh_execution_job 表：请先执行 sql/schema/01-schema.sql 或 sql/migrations/20261120/01_execution_job.sql"
  exit 1
fi
ok "作业表存在"

REDIS_OK=0
if [ "$REDIS_MODE" != none ] && [ "$(redisq ping 2>/dev/null | tr -d '\r')" = "PONG" ]; then
  REDIS_OK=1
  ok "Redis 可用（${REDIS_MODE}）"
else
  ng "Redis 不可用（mode=${REDIS_MODE}）：维护车道单飞按失败关闭设计不会执行作业；请设置 REDIS_* 或 REDIS_CONTAINER"
fi

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
echo "  runId=${RUN}（用于隔离本次测试数据）"

# 预置一个最小工作流，供 WF_RUN 真实执行
cat > /tmp/e2e_sched_wf.json <<'JSON'
{
  "nodes": [
    {"id": "start", "type": "START", "name": "开始", "position": {"x": 0, "y": 0}, "config": {}, "onError": "STOP"},
    {"id": "end", "type": "END", "name": "结束", "position": {"x": 240, "y": 0},
     "config": {"output": "sched:{{input}}"}, "onError": "STOP"}
  ],
  "edges": [{"id": "e1", "source": "start", "target": "end", "sourceHandle": "default"}]
}
JSON
WF_PAYLOAD=$(python3 -c "
import json
g=open('/tmp/e2e_sched_wf.json',encoding='utf-8').read()
print(json.dumps({'name':'e2e-调度中心-'+'$RUN','description':'e2e','graphJson':g},ensure_ascii=False))")
WF=$(curl -s -X POST "$SRV/api/workflows" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "$WF_PAYLOAD" | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('id',''))" 2>/dev/null)
if [ -z "${WF:-}" ]; then
  ng "预置工作流创建失败，后续 WF_RUN 用例无法执行"
  exit 1
fi
ok "预置工作流 id=$WF"

# ---------- S1: 提交 → 领取 → 运行 → 终态 ----------
echo ""
echo "[S1] 后台作业全链路：PENDING → LEASED → RUNNING → SUCCEEDED"
EX1="e2e-sched-$RUN-wf"
ID1=$(insert_job "$EX1" "WF_RUN" "BACKGROUND" "{\"workflowId\":\"$WF\",\"input\":\"sched-$RUN\"}")
[ -n "$ID1" ] && ok "作业已提交（id=${ID1}）" || { ng "作业提交失败"; exit 1; }
ST1=$(wait_job_status "$EX1" "SUCCEEDED DEAD FAILED CANCELLED" 90)
[ "$ST1" = "SUCCEEDED" ] && ok "作业进入 SUCCEEDED" || ng "作业终态=${ST1} error=$(job_field "$EX1" error_message)"
[ "$(job_field "$EX1" attempt)" = "1" ] && ok "attempt=1（领取时 +1）" || ng "attempt=$(job_field "$EX1" attempt)"
psqlq "SELECT start_time IS NOT NULL FROM wgh_execution_job WHERE execution_id='$EX1'" | grep -q t \
  && ok "start_time 已写入（RUNNING 迁移生效）" || ng "start_time 为空"
psqlq "SELECT finish_time IS NOT NULL FROM wgh_execution_job WHERE execution_id='$EX1'" | grep -q t \
  && ok "finish_time 已写入（终态）" || ng "finish_time 为空"
psqlq "SELECT lease_owner IS NULL AND lease_expire_time IS NULL FROM wgh_execution_job WHERE execution_id='$EX1'" | grep -q t \
  && ok "终态后租约已释放" || ng "终态后仍持有租约"
RUNS1=$(psqlq "SELECT count(*) FROM wgh_workflow_run WHERE workflow_id=$WF AND input_text='sched-$RUN'")
[ "$RUNS1" = "1" ] && ok "作业执行真实产生 1 条工作流运行" || ng "工作流运行记录数=${RUNS1}"

# ---------- S2: 维护车道 ----------
echo ""
echo "[S2] 维护车道：QUOTA_REFUND 可在 MAINTENANCE 车道执行"
if [ "$REDIS_OK" = 1 ]; then
  EX2="e2e-sched-$RUN-maint"
  insert_job "$EX2" "QUOTA_REFUND" "MAINTENANCE" '{}' >/dev/null
  ST2=$(wait_job_status "$EX2" "SUCCEEDED DEAD FAILED CANCELLED" 120)
  [ "$ST2" = "SUCCEEDED" ] && ok "维护车道作业 SUCCEEDED" || ng "维护车道终态=${ST2} error=$(job_field "$EX2" error_message)"
  [ "$(job_field "$EX2" lane)" = "MAINTENANCE" ] && ok "lane=MAINTENANCE" || ng "lane=$(job_field "$EX2" lane)"
else
  ng "维护车道用例未执行：Redis 不可用（本用例禁止静默跳过）"
fi

# ---------- S3: 租约过期回收 ----------
echo ""
echo "[S3-a] 租约过期且未超上限 → PENDING 后被重新领取"
EX3="e2e-sched-$RUN-lease"
psqlq "INSERT INTO wgh_execution_job
         (execution_id, job_type, lane, tenant_id, payload_json, payload_schema_version,
          status, scheduled_at, max_attempts, attempt, lease_owner, start_time,
          lease_expire_time, heartbeat_time)
       VALUES
         ('$EX3','WF_RUN','BACKGROUND','default','{\"workflowId\":\"$WF\",\"input\":\"lease-$RUN\"}'::jsonb,1,
          'RUNNING', now(), 3, 1, 'e2e-dead-instance', now() - interval '2 minutes',
          now() - interval '1 minute', now() - interval '2 minutes')" >/dev/null
ST3=$(wait_job_status "$EX3" "SUCCEEDED DEAD FAILED CANCELLED" 120)
[ "$ST3" = "SUCCEEDED" ] && ok "回收后由本实例重新领取并执行成功" || ng "回收后终态=${ST3} error=$(job_field "$EX3" error_message)"
[ "$(job_field "$EX3" attempt)" = "2" ] && ok "attempt=2（回收后重新领取再 +1）" || ng "attempt=$(job_field "$EX3" attempt)"
[ "$(job_field "$EX3" error_code)" = "LEASE_LOST" ] && ok "保留回收标记 error_code=LEASE_LOST" || ng "error_code=$(job_field "$EX3" error_code)"

echo ""
echo "[S3-b] 租约过期且已达上限 → DEAD(LEASE_LOST)"
EX3B="e2e-sched-$RUN-lease-dead"
psqlq "INSERT INTO wgh_execution_job
         (execution_id, job_type, lane, tenant_id, payload_json, payload_schema_version,
          status, scheduled_at, max_attempts, attempt, lease_owner, start_time,
          lease_expire_time, heartbeat_time)
       VALUES
         ('$EX3B','WF_RUN','BACKGROUND','default','{\"workflowId\":\"$WF\",\"input\":\"lease-dead-$RUN\"}'::jsonb,1,
          'RUNNING', now(), 3, 3, 'e2e-dead-instance', now() - interval '3 minutes',
          now() - interval '1 minute', now() - interval '3 minutes')" >/dev/null
ST3B=$(wait_job_status "$EX3B" "DEAD" 90)
[ "$ST3B" = "DEAD" ] && ok "超上限回收置 DEAD" || ng "终态=${ST3B}"
[ "$(job_field "$EX3B" error_code)" = "LEASE_LOST" ] && ok "error_code=LEASE_LOST" || ng "error_code=$(job_field "$EX3B" error_code)"
psqlq "SELECT finish_time IS NOT NULL FROM wgh_execution_job WHERE execution_id='$EX3B'" | grep -q t \
  && ok "DEAD 写入 finish_time" || ng "DEAD 未写 finish_time"
RUNS3B=$(psqlq "SELECT count(*) FROM wgh_workflow_run WHERE workflow_id=$WF AND input_text='lease-dead-$RUN'")
[ "$RUNS3B" = "0" ] && ok "超上限作业未被执行业务" || ng "超上限作业仍产生运行记录 ${RUNS3B} 条"

# ---------- S4: 重试与死信 ----------
echo ""
echo "[S4] 可重试失败 → FAILED(退避) → 重新入队 → 超上限 DEAD"
EX4="e2e-sched-$RUN-retry"
psqlq "INSERT INTO wgh_execution_job
         (execution_id, job_type, lane, tenant_id, payload_json, payload_schema_version,
          status, scheduled_at, max_attempts)
       VALUES
         ('$EX4','WF_RUN','BACKGROUND','default','{\"workflowId\":\"999999999\",\"input\":\"retry-$RUN\"}'::jsonb,1,
          'PENDING', now(), 2)" >/dev/null
ST4A=$(wait_job_status "$EX4" "FAILED DEAD" 60)
[ "$ST4A" = "FAILED" ] && ok "首次失败落 FAILED（可重试）" || ng "首次失败终态=${ST4A}"
BACKOFF_OK=$(psqlq "SELECT coalesce(not_before > now(), false) FROM wgh_execution_job WHERE execution_id='$EX4'")
[ "$BACKOFF_OK" = "t" ] && ok "not_before 已写入（退避中）" || ng "not_before 未按退避写入"
ST4B=$(wait_job_status "$EX4" "DEAD" 120)
[ "$ST4B" = "DEAD" ] && ok "退避结束重新入队后超上限 DEAD" || ng "重试终态=${ST4B}"
[ "$(job_field "$EX4" attempt)" = "2" ] && ok "attempt=2（达到 max_attempts）" || ng "attempt=$(job_field "$EX4" attempt)"

echo ""
echo "[S4-b] 未注册作业类型 → DEAD(JOB_TYPE_UNKNOWN)"
EX4B="e2e-sched-$RUN-unknown"
insert_job "$EX4B" "E2E_NO_SUCH_JOB" "BACKGROUND" '{}' >/dev/null
ST4B2=$(wait_job_status "$EX4B" "DEAD" 60)
[ "$ST4B2" = "DEAD" ] && ok "未知作业类型直接死信" || ng "终态=${ST4B2}"
[ "$(job_field "$EX4B" error_code)" = "JOB_TYPE_UNKNOWN" ] && ok "error_code=JOB_TYPE_UNKNOWN" || ng "error_code=$(job_field "$EX4B" error_code)"

echo ""
echo "[S4-c] 负载不兼容 → DEAD(INCOMPATIBLE_PAYLOAD)"
EX4C="e2e-sched-$RUN-badpayload"
insert_job "$EX4C" "WF_RUN" "BACKGROUND" '{}' >/dev/null
ST4C=$(wait_job_status "$EX4C" "DEAD" 60)
[ "$ST4C" = "DEAD" ] && ok "缺少 workflowId 直接死信" || ng "终态=${ST4C}"
[ "$(job_field "$EX4C" error_code)" = "INCOMPATIBLE_PAYLOAD" ] && ok "error_code=INCOMPATIBLE_PAYLOAD" || ng "error_code=$(job_field "$EX4C" error_code)"

# ---------- S5: 取消 ----------
echo ""
echo "[S5] 取消：领取后检测到 cancel_requested → CANCELLED 且不执行业务"
EX5="e2e-sched-$RUN-cancel"
psqlq "INSERT INTO wgh_execution_job
         (execution_id, job_type, lane, tenant_id, payload_json, payload_schema_version,
          status, scheduled_at, max_attempts, cancel_requested, cancel_reason)
       VALUES
         ('$EX5','WF_RUN','BACKGROUND','default','{\"workflowId\":\"$WF\",\"input\":\"cancel-$RUN\"}'::jsonb,1,
          'PENDING', now(), 3, 1, 'e2e cancel')" >/dev/null
ST5=$(wait_job_status "$EX5" "CANCELLED DEAD FAILED SUCCEEDED" 60)
[ "$ST5" = "CANCELLED" ] && ok "作业取消成功" || ng "终态=${ST5}"
[ "$(job_field "$EX5" error_code)" = "CANCELLED" ] && ok "error_code=CANCELLED" || ng "error_code=$(job_field "$EX5" error_code)"
[ "$(job_field "$EX5" cancel_reason)" = "e2e cancel" ] && ok "cancel_reason 保留" || ng "cancel_reason=$(job_field "$EX5" cancel_reason)"
RUNS5=$(psqlq "SELECT count(*) FROM wgh_workflow_run WHERE workflow_id=$WF AND input_text='cancel-$RUN'")
[ "$RUNS5" = "0" ] && ok "取消后未产生工作流运行（无业务副作用）" || ng "取消后仍产生 ${RUNS5} 条运行记录"

# ---------- S6: 幂等去重 ----------
echo ""
echo "[S6] (job_type, biz_key) 幂等去重"
BIZ="e2e-sched-$RUN-biz"
EX6="e2e-sched-$RUN-idem"
insert_job "$EX6" "WF_RUN" "BACKGROUND" "{\"workflowId\":\"$WF\",\"input\":\"idem-$RUN\"}" ",biz_key" "'$BIZ'" >/dev/null
DUP_ID=$(psqlq "INSERT INTO wgh_execution_job
                  (execution_id, job_type, lane, tenant_id, payload_json, payload_schema_version,
                   status, scheduled_at, max_attempts, biz_key)
                VALUES
                  ('e2e-sched-$RUN-idem-dup','WF_RUN','BACKGROUND','default','{}'::jsonb,1,
                   'PENDING', now(), 3, '$BIZ')
                ON CONFLICT DO NOTHING
                RETURNING id")
[ -z "$DUP_ID" ] && ok "同 (job_type,biz_key) 第二次插入被唯一索引拦下" || ng "重复插入成功 id=${DUP_ID}"
CNT6=$(psqlq "SELECT count(*) FROM wgh_execution_job WHERE job_type='WF_RUN' AND biz_key='$BIZ' AND deleted=0")
[ "$CNT6" = "1" ] && ok "同幂等键只保留 1 条" || ng "同幂等键记录数=${CNT6}"
wait_job_status "$EX6" "SUCCEEDED" 60 >/dev/null \
  && ok "首次提交的作业正常执行" || ng "幂等作业未成功执行"

# ---------- S7: 维护车道 Redis 单飞 ----------
echo ""
echo "[S7] 维护车道 Redis 单飞互斥"
if [ "$REDIS_OK" = 1 ]; then
  LOCK_KEY="$REDIS_KEY_PREFIX:lock:scheduler-singleflight:job:QUOTA_REFUND"
  redisq SET "$LOCK_KEY" "e2e-holder" NX PX 60000 >/dev/null
  HELD=$(redisq EXISTS "$LOCK_KEY" | tr -d '\r')
  [ "$HELD" = "1" ] && ok "已模拟其它实例持有单飞锁（${LOCK_KEY}）" || ng "模拟持锁失败"

  EX7="e2e-sched-$RUN-sf"
  insert_job "$EX7" "QUOTA_REFUND" "MAINTENANCE" '{}' >/dev/null
  sleep 12
  ST7=$(job_field "$EX7" status)
  AT7=$(job_field "$EX7" attempt)
  [ "$ST7" = "PENDING" ] && ok "锁被占用时作业回退 PENDING（不执行）" || ng "锁被占用时状态=${ST7}"
  [ "$AT7" = "0" ] && ok "放弃领取时 attempt 已回滚为 0" || ng "attempt=${AT7}"
  psqlq "SELECT not_before > now() FROM wgh_execution_job WHERE execution_id='$EX7'" | grep -q t \
    && ok "not_before 写入重试延迟" || ng "未写入单飞重试延迟"

  redisq DEL "$LOCK_KEY" >/dev/null
  ST7B=$(wait_job_status "$EX7" "SUCCEEDED DEAD FAILED CANCELLED" 90)
  [ "$ST7B" = "SUCCEEDED" ] && ok "释放锁后作业恢复执行" || ng "释放锁后终态=${ST7B} error=$(job_field "$EX7" error_message)"
else
  ng "单飞互斥用例未执行：Redis 不可用（本用例禁止静默跳过）"
fi

# ---------- 汇总 ----------
echo ""
echo "=============================================="
echo " 结果：通过 $PASS / 失败 $FAIL"
echo "=============================================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
