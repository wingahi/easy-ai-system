#!/usr/bin/env bash
# 配额扣费与退款补偿端到端测试（批次 4）
#
# 覆盖场景：
#   Q1 正常扣费后执行：聊天请求落 CHARGED，执行开始后置 SETTLED，计数 +1
#   Q2 CHARGED 悬挂（超过保护期）被 QUOTA_REFUND 补偿作业扫到并退款，计数 -1
#   Q3 重复退款幂等：再次扫描不重复减计数
#   Q4 退款失败退避：未到 next_retry_at 的 REFUND_FAILED 不被扫描；到期后被扫描并退款
#   Q5 超次数进 DEAD：需要故障注入（显式人工前置，不做静默跳过）
#   Q6 拒绝分支退款：需要执行器拒绝（显式人工前置，不做静默跳过）
#
# 依赖：
#   - backend 运行中（补偿作业走维护车道，需要真实 PostgreSQL + Redis）
#   - 真实 PostgreSQL（H2 不支持 JSONB / 部分唯一索引 / 维护车道调度）
#   - Redis（维护车道单飞失败关闭，Redis 不可用时 QUOTA_REFUND 不会执行）
#   - Q1 还需要一个已启用且配置了聊天模型、可正常调用的窗口（脚本自动从库中挑选）
#
# 用法：
#   bash scripts/e2e-quota-refund.sh
#   SRV=http://127.0.0.1:8080 PG_CONTAINER=my-pg bash scripts/e2e-quota-refund.sh
#
# 环境变量：
#   SRV / DB / DB_USER / DB_HOST / DB_PORT / PGPASSWORD / PG_CONTAINER
#   REDIS_CONTAINER / REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_DB
#   ADMIN_USER / ADMIN_PASSWORD  登录账号，默认 admin / admin
#   E2E_TIMEOUT      单步等待上限秒数，默认 180
#   E2E_QUOTA_FAULT_INJECTION  设为 1 表示已人工完成故障注入（见 docs/testing/test-cases.md 43.4）
#   E2E_QUOTA_DEAD_CHARGE_ID   故障注入期间由脚本创建的待 DEAD 流水号（故障注入模式下必需）
#   E2E_TASK_REJECT  设为 1 表示已按文档把聊天异步执行器队列压到 1 并重启，用于拒绝分支退款
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
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-admin}
E2E_TIMEOUT=${E2E_TIMEOUT:-180}

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
  elif [ -n "$REDIS_PASSWORD" ]; then
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" -n "$REDIS_DB" "$@"
  else
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DB" "$@"
  fi
}

wait_value() { # sql expected timeout
  local deadline=$(( $(date +%s) + ${3:-$E2E_TIMEOUT} )) value
  while [ "$(date +%s)" -lt "$deadline" ]; do
    value=$(psqlq "$1" 2>/dev/null | tr -d '[:space:]')
    [ "$value" = "$2" ] && return 0
    sleep 2
  done
  return 1
}

echo "=============================================="
echo " 配额扣费与退款补偿端到端测试"
echo "=============================================="
echo "  DB: $PSQL_MODE / $DB     Redis: $REDIS_MODE     SRV: $SRV"

# ---------- 0. 前置 ----------
echo ""
echo "[Q0] 前置检查"
if ! curl -sf "$HEALTH_URL" | grep -q '"status":"UP"'; then
  ng "后端未就绪：$HEALTH_URL 非 UP（补偿作业必须由后端调度执行）"
  exit 1
fi
ok "后端健康检查 UP"

if ! psqlq "SELECT 1" >/dev/null 2>&1; then
  ng "数据库不可用（mode=$PSQL_MODE db=${DB}）"
  exit 1
fi
for t in wgh_quota_charge wgh_quota_usage wgh_execution_job; do
  if ! psqlq "SELECT to_regclass('public.$t') IS NOT NULL" | grep -q t; then
    ng "缺少表 ${t}：请先执行 sql/schema/01-schema.sql 或 sql/migrations/20261120/02_quota_charge.sql"
    exit 1
  fi
done
ok "配额流水 / 用量 / 作业表存在"

REDIS_OK=0
if [ "$REDIS_MODE" != none ] && [ "$(redisq ping 2>/dev/null | tr -d '\r')" = "PONG" ]; then
  REDIS_OK=1
  ok "Redis 可用（${REDIS_MODE}）"
else
  ng "Redis 不可用：QUOTA_REFUND 在维护车道且单飞失败关闭，补偿用例无法执行"
  exit 1
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
USER_E2E="e2e_q_$RANDOM"
PERIOD=$(date +%Y-%m-%d)
echo "  runId=$RUN  测试用户=$USER_E2E  周期=$PERIOD"

# QUOTA_REFUND 是周期作业（默认 60s 提交一次），先确认调度在跑
echo ""
echo "[Q0-b] 确认补偿作业已被调度执行（最多 90s）"
if wait_value "SELECT count(*) FROM wgh_execution_job WHERE job_type='QUOTA_REFUND' AND create_time > now() - interval '10 minutes'" \
     "1" 90; then
  ok "近 10 分钟内存在 QUOTA_REFUND 作业记录"
else
  ng "近 10 分钟没有 QUOTA_REFUND 作业：调度中心未启用或维护车道被阻塞"
fi

# ---------- Q1: 正常扣费后执行 ----------
echo ""
echo "[Q1] 正常扣费后执行：CHARGED → SETTLED，计数 +1"
WIN=$(psqlq "SELECT id FROM wgh_chat_window WHERE deleted=0 AND status=1 AND llm_model_id IS NOT NULL ORDER BY id LIMIT 1")
if [ -z "$WIN" ]; then
  ng "没有可用聊天窗口（需 status=1 且已配置 llm_model_id），Q1 无法执行；这是环境缺失，不是断言失败"
else
  SID=$(curl -s -X POST "$SRV/api/chat/windows/$WIN/sessions" -H "$AUTH" \
    -H "Content-Type: application/json" -d '{}' \
    | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('id',''))" 2>/dev/null)
  if [ -z "${SID:-}" ]; then
    ng "创建会话失败（window=${WIN}）"
  else
    BEFORE_MAX=$(psqlq "SELECT coalesce(max(id),0) FROM wgh_quota_charge")
    BEFORE_COUNT=$(psqlq "SELECT coalesce(chat_count,0) FROM wgh_quota_usage WHERE username='$ADMIN_USER' AND date_key='$PERIOD'")
    before_msg=$(python3 -c "import json;print(json.dumps({'sessionId':int('$SID'),'message':'e2e 配额扣费验证'},ensure_ascii=False))")
    curl -s -N -X POST "$SRV/api/chat/windows/$WIN/stream" -H "$AUTH" -H "Content-Type: application/json" \
      -d "$before_msg" --max-time 60 > /tmp/e2e_quota_chat.sse 2>&1

    CHARGE_ID=$(psqlq "SELECT charge_id FROM wgh_quota_charge WHERE id > $BEFORE_MAX AND username='$ADMIN_USER' ORDER BY id DESC LIMIT 1")
    if [ -z "$CHARGE_ID" ]; then
      ng "请求未产生扣费流水（可能在扣费前已被拒绝；检查 /tmp/e2e_quota_chat.sse）"
    else
      ok "产生扣费流水 chargeId=$CHARGE_ID"
      ST=$(psqlq "SELECT status FROM wgh_quota_charge WHERE charge_id='$CHARGE_ID'")
      [ "$ST" = "SETTLED" ] && ok "流水置 SETTLED（执行已开始）" || ng "流水状态=${ST}（期望 SETTLED；若为 REFUNDED 说明执行器拒绝了任务）"
      SID_DB=$(psqlq "SELECT coalesce(session_id::text,'') FROM wgh_quota_charge WHERE charge_id='$CHARGE_ID'")
      [ "$SID_DB" = "$SID" ] && ok "流水已回填 session_id" || ng "流水 session_id=${SID_DB}（期望 ${SID}）"
      AFTER_COUNT=$(psqlq "SELECT coalesce(chat_count,0) FROM wgh_quota_usage WHERE username='$ADMIN_USER' AND date_key='$PERIOD'")
      [ "$AFTER_COUNT" -gt "$BEFORE_COUNT" ] && ok "对话计数增加（$BEFORE_COUNT → ${AFTER_COUNT}）" \
        || ng "对话计数未增加（$BEFORE_COUNT → ${AFTER_COUNT}）"
    fi
  fi
fi

# ---------- Q2: CHARGED 悬挂被补偿 ----------
echo ""
echo "[Q2] CHARGED 悬挂（超过保护期）被补偿作业退款"
psqlq "INSERT INTO wgh_quota_usage (username, date_key, chat_count, token_used)
       VALUES ('$USER_E2E', '$PERIOD', 5, 0)
       ON CONFLICT (username, date_key) DO UPDATE SET chat_count = 5" >/dev/null
C1="e2e-charge-$RUN-1"
psqlq "INSERT INTO wgh_quota_charge
         (charge_id, username, period_key, amount, status, create_time, update_time)
       VALUES ('$C1','$USER_E2E','$PERIOD',1,'CHARGED', now() - interval '10 minutes', now())
       ON CONFLICT (charge_id) DO NOTHING" >/dev/null
if wait_value "SELECT status FROM wgh_quota_charge WHERE charge_id='$C1'" "REFUNDED" 150; then
  ok "悬挂流水被补偿为 REFUNDED"
else
  ng "悬挂流水仍未退款：status=$(psqlq "SELECT status FROM wgh_quota_charge WHERE charge_id='$C1'")"
fi
CNT2=$(psqlq "SELECT chat_count FROM wgh_quota_usage WHERE username='$USER_E2E' AND date_key='$PERIOD'")
[ "$CNT2" = "4" ] && ok "计数已退回（5 → 4）" || ng "计数=${CNT2}（期望 4）"
FIN2=$(psqlq "SELECT finish_time IS NOT NULL FROM wgh_quota_charge WHERE charge_id='$C1'")
[ "$FIN2" = "t" ] && ok "退款终态写入 finish_time" || ng "finish_time 为空"

# ---------- Q3: 重复退款幂等 ----------
echo ""
echo "[Q3] 重复退款幂等：再等一轮扫描，计数不重复减"
sleep 20
CNT3=$(psqlq "SELECT chat_count FROM wgh_quota_usage WHERE username='$USER_E2E' AND date_key='$PERIOD'")
[ "$CNT3" = "4" ] && ok "计数保持 4，未重复退款" || ng "计数被重复扣减为 ${CNT3}"
ST3=$(psqlq "SELECT status FROM wgh_quota_charge WHERE charge_id='$C1'")
[ "$ST3" = "REFUNDED" ] && ok "流水保持 REFUNDED" || ng "流水状态=${ST3}"
CNT3B=$(psqlq "SELECT count(*) FROM wgh_quota_charge WHERE charge_id='$C1'")
[ "$CNT3B" = "1" ] && ok "流水未被复制（charge_id 唯一）" || ng "流水条数=${CNT3B}"

# ---------- Q4: 退款失败退避 ----------
echo ""
echo "[Q4-a] REFUND_FAILED 未到 next_retry_at 不被扫描"
C4A="e2e-charge-$RUN-4a"
psqlq "INSERT INTO wgh_quota_charge
         (charge_id, username, period_key, amount, status, retry_count, next_retry_at, last_error, create_time, update_time)
       VALUES ('$C4A','$USER_E2E','$PERIOD',1,'REFUND_FAILED',1, now() + interval '3 minutes',
               'e2e injected failure', now() - interval '10 minutes', now())
       ON CONFLICT (charge_id) DO NOTHING" >/dev/null
sleep 20
ST4A=$(psqlq "SELECT status FROM wgh_quota_charge WHERE charge_id='$C4A'")
RC4A=$(psqlq "SELECT retry_count FROM wgh_quota_charge WHERE charge_id='$C4A'")
[ "$ST4A" = "REFUND_FAILED" ] && ok "未到期流水保持 REFUND_FAILED" || ng "未到期流水状态=${ST4A}"
[ "$RC4A" = "1" ] && ok "retry_count 未被改写" || ng "retry_count=${RC4A}"

echo ""
echo "[Q4-b] REFUND_FAILED 到期后被扫描并退款"
C4B="e2e-charge-$RUN-4b"
psqlq "INSERT INTO wgh_quota_charge
         (charge_id, username, period_key, amount, status, retry_count, next_retry_at, last_error, create_time, update_time)
       VALUES ('$C4B','$USER_E2E','$PERIOD',1,'REFUND_FAILED',1, now() - interval '1 minute',
               'e2e injected failure', now() - interval '10 minutes', now())
       ON CONFLICT (charge_id) DO NOTHING" >/dev/null
if wait_value "SELECT status FROM wgh_quota_charge WHERE charge_id='$C4B'" "REFUNDED" 150; then
  ok "到期流水被扫描并退款"
else
  ng "到期流水未退款：status=$(psqlq "SELECT status FROM wgh_quota_charge WHERE charge_id='$C4B'")"
fi
CNT4=$(psqlq "SELECT chat_count FROM wgh_quota_usage WHERE username='$USER_E2E' AND date_key='$PERIOD'")
[ "$CNT4" = "3" ] && ok "退避到期退款后计数再减一（4 → 3）" || ng "计数=${CNT4}（期望 3）"

# ---------- Q5: 超次数进 DEAD（人工前置） ----------
echo ""
echo "[Q5] 超次数进 DEAD（需要故障注入）"
if [ "${E2E_QUOTA_FAULT_INJECTION:-0}" = "1" ] && [ -n "${E2E_QUOTA_DEAD_CHARGE_ID:-}" ]; then
  if wait_value "SELECT status FROM wgh_quota_charge WHERE charge_id='${E2E_QUOTA_DEAD_CHARGE_ID}'" "DEAD" 180; then
    ok "退款连续失败超过上限后置 DEAD"
    RC5=$(psqlq "SELECT retry_count FROM wgh_quota_charge WHERE charge_id='${E2E_QUOTA_DEAD_CHARGE_ID}'")
    [ "${RC5:-0}" -ge 5 ] && ok "retry_count=${RC5}（达到 refund-max-attempts）" || ng "retry_count=${RC5}"
  else
    ng "指定流水未进入 DEAD：status=$(psqlq "SELECT status FROM wgh_quota_charge WHERE charge_id='${E2E_QUOTA_DEAD_CHARGE_ID}'")"
  fi
else
  ng "DEAD 用例未执行：需先做故障注入（临时让 wgh_quota_usage 的 UPDATE 失败），"
  echo "      再把 E2E_QUOTA_FAULT_INJECTION=1 与 E2E_QUOTA_DEAD_CHARGE_ID=<chargeId> 传入重跑。"
  echo "      具体步骤见 docs/testing/test-cases.md §43.4（本用例禁止静默跳过）。"
fi

# ---------- Q6: 拒绝分支退款（人工前置） ----------
echo ""
echo "[Q6] 拒绝分支退款（执行器拒绝）"
if [ "${E2E_TASK_REJECT:-0}" = "1" ]; then
  BEFORE_REFUND=$(psqlq "SELECT count(*) FROM wgh_quota_charge WHERE username='$ADMIN_USER' AND status='REFUNDED'")
  WIN_R=$(psqlq "SELECT id FROM wgh_chat_window WHERE deleted=0 AND status=1 AND llm_model_id IS NOT NULL ORDER BY id LIMIT 1")
  if [ -z "$WIN_R" ]; then
    ng "拒绝分支退款未执行：没有可用聊天窗口"
  else
    SID_R=$(curl -s -X POST "$SRV/api/chat/windows/$WIN_R/sessions" -H "$AUTH" \
      -H "Content-Type: application/json" -d '{}' \
      | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('id',''))" 2>/dev/null)
    body=$(python3 -c "import json;print(json.dumps({'sessionId':int('$SID_R'),'message':'e2e reject-refund'},ensure_ascii=False))")
    # 队列被压到 1 时，8 个并发请求足以触发拒绝
    pids=()
    for i in $(seq 1 8); do
      curl -s -N -X POST "$SRV/api/chat/windows/$WIN_R/stream" -H "$AUTH" -H "Content-Type: application/json" \
        -d "$body" --max-time 30 > "/tmp/e2e_quota_reject_$i.sse" 2>&1 &
      pids+=($!)
    done
    for p in "${pids[@]}"; do wait "$p" 2>/dev/null; done
    AFTER_REFUND=$(psqlq "SELECT count(*) FROM wgh_quota_charge WHERE username='$ADMIN_USER' AND status='REFUNDED'")
    [ "$AFTER_REFUND" -gt "$BEFORE_REFUND" ] \
      && ok "拒绝后新增退款流水（$BEFORE_REFUND → ${AFTER_REFUND}）" \
      || ng "未观察到拒绝退款（$BEFORE_REFUND → ${AFTER_REFUND}）；确认已按文档缩小执行器队列并重启"
  fi
else
  ng "拒绝分支退款未执行：需先把聊天异步执行器队列压到 1 并重启后端，再以 E2E_TASK_REJECT=1 重跑。"
  echo "      具体步骤见 docs/testing/test-cases.md §43.5（本用例禁止静默跳过）。"
fi

# ---------- 汇总 ----------
echo ""
echo "=============================================="
echo " 结果：通过 $PASS / 失败 $FAIL"
echo "=============================================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
