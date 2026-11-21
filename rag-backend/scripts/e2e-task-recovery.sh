#!/usr/bin/env bash
# 任务恢复端到端测试（批次 4）
#
# 覆盖场景：
#   R1 工作流中断后继续执行：基于 wgh_workflow_node_run 轨迹重建检查点，RUNNING 节点重跑至 COMPLETED
#   R2 恢复锁：Redis 锁被其它实例持有时拒绝恢复；释放后可恢复（跨实例互斥的同一条代码路径）
#   R3 终态运行不可恢复（已结束任务不重复执行）
#   R4 多 Agent 从检查点续跑（需要一条真实中断的 RUNNING 运行 + 可用模型，未提供时显式报错）
#   R5 会话级待办查询：无任务时 hasPending=false；会话越权返回拒绝
#
# 依赖：
#   - backend 运行中；真实 PostgreSQL + Redis（恢复锁失败关闭，Redis 未启用时直接拒绝恢复）
#   - 真实 PostgreSQL（JSONB graph_snapshot/context_json）
#
# 用法：
#   bash scripts/e2e-task-recovery.sh
#   E2E_MULTI_AGENT_RUN_ID=run-xxx bash scripts/e2e-task-recovery.sh
#
# 环境变量：
#   SRV / DB / DB_USER / DB_HOST / DB_PORT / PGPASSWORD / PG_CONTAINER
#   REDIS_CONTAINER / REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_DB
#   REDIS_KEY_PREFIX  默认 wgh
#   ADMIN_USER / ADMIN_PASSWORD  默认 admin / admin
#   E2E_MULTI_AGENT_RUN_ID  一条真实中断（RUNNING）的多 Agent runId；不提供则 R4 显式报错
#   E2E_TIMEOUT  单步等待上限秒数，默认 120
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
REDIS_KEY_PREFIX=${REDIS_KEY_PREFIX:-wgh}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-admin}
E2E_TIMEOUT=${E2E_TIMEOUT:-120}

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

wait_value() { # sql expected timeout
  local deadline=$(( $(date +%s) + ${3:-$E2E_TIMEOUT} )) value
  while [ "$(date +%s)" -lt "$deadline" ]; do
    value=$(psqlq "$1" 2>/dev/null | tr -d '[:space:]')
    [ "$value" = "$2" ] && return 0
    sleep 2
  done
  return 1
}

resume_api() { # taskType taskId -> json
  curl -s -X POST "$SRV/api/admin/tasks/resume" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"taskType\":\"$1\",\"taskId\":\"$2\"}"
}
json_field() { # json field
  python3 -c "
import sys,json
try:
    d=json.loads(sys.argv[1])
except Exception:
    print(''); raise SystemExit
print((d.get('data') or {}).get(sys.argv[2],''))" "$1" "$2" 2>/dev/null
}

echo "=============================================="
echo " 任务恢复端到端测试"
echo "=============================================="
echo "  DB: $PSQL_MODE / $DB     Redis: $REDIS_MODE     SRV: $SRV"

# ---------- 0. 前置 ----------
echo ""
echo "[R0] 前置检查"
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
if ! psqlq "SELECT to_regclass('public.wgh_workflow_run') IS NOT NULL" | grep -q t; then
  ng "缺少 wgh_workflow_run：请先初始化 schema"
  exit 1
fi
ok "工作流运行表存在"
if [ "$REDIS_MODE" != none ] && [ "$(redisq ping 2>/dev/null | tr -d '\r')" = "PONG" ]; then
  ok "Redis 可用（恢复锁依赖）"
else
  ng "Redis 不可用：任务恢复按失败关闭设计会直接拒绝，R1/R2 无法执行"
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
echo "  runId=$RUN"

# ---------- 预置：工作流 + 一次完整运行（用于复制 graph_snapshot/context_json） ----------
echo ""
echo "[R0-b] 预置工作流与基线运行"
cat > /tmp/e2e_recovery_wf.json <<'JSON'
{
  "nodes": [
    {"id": "start", "type": "START", "name": "开始", "position": {"x": 0, "y": 0}, "config": {}, "onError": "STOP"},
    {"id": "v1", "type": "VARIABLE", "name": "变量赋值", "position": {"x": 200, "y": 0},
     "config": {"assignments": [{"name": "tag", "value": "resumed"}]}, "onError": "STOP"},
    {"id": "end", "type": "END", "name": "结束", "position": {"x": 400, "y": 0},
     "config": {"output": "resume:{{input}}:{{vars.tag}}"}, "onError": "STOP"}
  ],
  "edges": [
    {"id": "e1", "source": "start", "target": "v1", "sourceHandle": "default"},
    {"id": "e2", "source": "v1", "target": "end", "sourceHandle": "default"}
  ]
}
JSON
WF_PAYLOAD=$(python3 -c "
import json
g=open('/tmp/e2e_recovery_wf.json',encoding='utf-8').read()
print(json.dumps({'name':'e2e-任务恢复-'+'$RUN','description':'e2e','graphJson':g},ensure_ascii=False))")
WF=$(curl -s -X POST "$SRV/api/workflows" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "$WF_PAYLOAD" | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('id',''))" 2>/dev/null)
if [ -z "${WF:-}" ]; then
  ng "预置工作流创建失败"
  exit 1
fi
ok "工作流 id=$WF"

BASE_INPUT="baseline-$RUN"
curl -s -N -X POST "$SRV/api/workflows/$WF/run" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "$(python3 -c "import json;print(json.dumps({'input':'$BASE_INPUT'}))")" \
  --max-time 60 > /tmp/e2e_recovery_baseline.sse 2>&1
R1=$(psqlq "SELECT max(id) FROM wgh_workflow_run WHERE workflow_id=$WF")
ST_BASE=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$R1")
[ "$ST_BASE" = "COMPLETED" ] && ok "基线运行 COMPLETED（id=$R1）" || { ng "基线运行状态=${ST_BASE}"; exit 1; }

# 构造中断现场：RUNNING + START 成功 + v1 处于 RUNNING（模拟进程中断）
new_interrupted_run() { # input -> runId
  local input="$1"
  psqlq "INSERT INTO wgh_workflow_run
           (workflow_id, input_text, status, context_json, graph_snapshot, start_time, total_nodes)
         SELECT $WF, '$input', 'RUNNING',
                jsonb_build_object('vars', jsonb_build_object('input','$input','lastOutput',''),
                                   'nodeOutputs', '{}'::jsonb),
                graph_snapshot, now(), 3
           FROM wgh_workflow_run WHERE id=$R1
         RETURNING id"
}

# ---------- R1: 工作流中断后继续执行 ----------
echo ""
echo "[R1] 工作流中断后继续执行"
INPUT1="resume-$RUN"
RID=$(new_interrupted_run "$INPUT1")
[ -n "$RID" ] && ok "构造中断运行 id=$RID（status=RUNNING，v1 中断）" || { ng "构造中断运行失败"; exit 1; }
psqlq "INSERT INTO wgh_workflow_node_run (run_id, node_id, node_type, node_name, status, start_time)
       VALUES ($RID,'start','START','开始','SUCCESS', now() - interval '2 minutes')" >/dev/null
psqlq "INSERT INTO wgh_workflow_node_run (run_id, node_id, node_type, node_name, status, start_time)
       VALUES ($RID,'v1','VARIABLE','变量赋值','RUNNING', now() - interval '1 minute')" >/dev/null

RESP1=$(resume_api "WORKFLOW" "$RID")
SUCCESS1=$(json_field "$RESP1" success)
MSG1=$(json_field "$RESP1" message)
FROM1=$(json_field "$RESP1" resumedFrom)
[ "$SUCCESS1" = "True" ] && ok "恢复接口接受：$MSG1（resumedFrom=$FROM1）" || ng "恢复接口拒绝：$RESP1（message=$MSG1）"

if wait_value "SELECT status FROM wgh_workflow_run WHERE id=$RID" "COMPLETED" 90; then
  ok "续跑后运行 COMPLETED"
else
  ng "续跑后状态=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$RID")"
fi
OUT1=$(psqlq "SELECT coalesce(output_text,'') FROM wgh_workflow_run WHERE id=$RID")
if [ "$OUT1" = "resume:$INPUT1:resumed" ]; then
  ok "最终输出正确：$OUT1"
elif [ -z "$OUT1" ]; then
  echo "  ℹ output_text 为空（以节点轨迹与 COMPLETED 作为最终判定）"
else
  ng "最终输出=${OUT1}"
fi
LAST_V1=$(psqlq "SELECT status FROM wgh_workflow_node_run WHERE run_id=$RID AND node_id='v1' ORDER BY id DESC LIMIT 1")
[ "$LAST_V1" = "SUCCESS" ] && ok "中断节点 v1 重跑成功" || ng "v1 最新状态=${LAST_V1}"
INT_V1=$(psqlq "SELECT count(*) FROM wgh_workflow_node_run WHERE run_id=$RID AND node_id='v1' AND status='FAILED' AND error_message='interrupted by restart'")
[ "$INT_V1" = "1" ] && ok "原中断轨迹被标记 FAILED(interrupted by restart)" || ng "中断轨迹标记数=${INT_V1}"
AUDIT1=$(psqlq "SELECT count(*) FROM wgh_audit_log WHERE action='TASK_RESUME' AND resource_type='WORKFLOW' AND resource_id='$RID'")
[ "$AUDIT1" -ge 1 ] && ok "写入审计日志 TASK_RESUME" || ng "缺少审计日志"

# ---------- R2: 恢复锁 ----------
echo ""
echo "[R2] 恢复锁：其它实例持锁时拒绝恢复，释放后可恢复"
RID2=$(new_interrupted_run "lock-$RUN")
psqlq "INSERT INTO wgh_workflow_node_run (run_id, node_id, node_type, node_name, status, start_time)
       VALUES ($RID2,'start','START','开始','SUCCESS', now() - interval '2 minutes')" >/dev/null
psqlq "INSERT INTO wgh_workflow_node_run (run_id, node_id, node_type, node_name, status, start_time)
       VALUES ($RID2,'v1','VARIABLE','变量赋值','RUNNING', now() - interval '1 minute')" >/dev/null
LOCK_KEY="$REDIS_KEY_PREFIX:lock:task-resume:WORKFLOW:$RID2"
redisq SET "$LOCK_KEY" "e2e-other-instance" NX PX 30000 >/dev/null
HELD=$(redisq EXISTS "$LOCK_KEY" | tr -d '\r')
[ "$HELD" = "1" ] && ok "已模拟其它实例持有恢复锁" || ng "模拟持锁失败"

RESP2=$(resume_api "WORKFLOW" "$RID2")
SUCCESS2=$(json_field "$RESP2" success)
MSG2=$(json_field "$RESP2" message)
[ "$SUCCESS2" = "False" ] && ok "持锁期间恢复被拒绝" || ng "持锁期间仍恢复成功：$RESP2"
echo "$MSG2" | grep -q "已在其他实例恢复中" && ok "拒绝原因正确：$MSG2" || ng "拒绝原因=${MSG2}"
ST2A=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$RID2")
[ "$ST2A" = "RUNNING" ] && ok "被拒绝的运行保持 RUNNING，未被推进" || ng "运行状态=${ST2A}"

redisq DEL "$LOCK_KEY" >/dev/null
RESP2B=$(resume_api "WORKFLOW" "$RID2")
SUCCESS2B=$(json_field "$RESP2B" success)
[ "$SUCCESS2B" = "True" ] && ok "释放锁后可恢复：$(json_field "$RESP2B" message)" || ng "释放锁后恢复仍失败：$RESP2B"
wait_value "SELECT status FROM wgh_workflow_run WHERE id=$RID2" "COMPLETED" 90 >/dev/null \
  && ok "第二次恢复执行完成" || ng "第二次恢复未完成：$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$RID2")"

# ---------- R3: 终态不可恢复 ----------
echo ""
echo "[R3] 终态运行不可恢复"
RESP3=$(resume_api "WORKFLOW" "$R1")
SUCCESS3=$(json_field "$RESP3" success)
MSG3=$(json_field "$RESP3" message)
[ "$SUCCESS3" = "False" ] && ok "已完成运行拒绝恢复" || ng "已完成运行仍恢复：$RESP3"
echo "$MSG3" | grep -q "任务已结束" && ok "拒绝原因正确：$MSG3" || ng "拒绝原因=${MSG3}"

# ---------- R4: 多 Agent 续跑 ----------
echo ""
echo "[R4] 多 Agent 从检查点续跑"
if [ -n "${E2E_MULTI_AGENT_RUN_ID:-}" ]; then
  RUN_STATUS=$(psqlq "SELECT status FROM wgh_multi_agent_run WHERE run_id='${E2E_MULTI_AGENT_RUN_ID}'")
  if [ -z "$RUN_STATUS" ]; then
    ng "指定 runId 不存在：${E2E_MULTI_AGENT_RUN_ID}"
  elif [ "$RUN_STATUS" != "RUNNING" ] && [ "$RUN_STATUS" != "PLANNING" ]; then
    ng "指定运行状态=${RUN_STATUS}，需为 RUNNING/PLANNING（终态不可续跑）"
  else
    RESP4=$(resume_api "MULTI_AGENT" "$E2E_MULTI_AGENT_RUN_ID")
    SUCCESS4=$(json_field "$RESP4" success)
    [ "$SUCCESS4" = "True" ] && ok "多 Agent 续跑已触发：$(json_field "$RESP4" message)" || ng "多 Agent 续跑被拒绝：$RESP4"
    AUDIT4=$(psqlq "SELECT count(*) FROM wgh_audit_log WHERE action='TASK_RESUME' AND resource_type='MULTI_AGENT' AND resource_id='${E2E_MULTI_AGENT_RUN_ID}'")
    [ "$AUDIT4" -ge 1 ] && ok "写入审计日志 TASK_RESUME(MULTI_AGENT)" || ng "缺少多 Agent 恢复审计"
    if wait_value "SELECT status IN ('RUNNING','PLANNING') FROM wgh_multi_agent_run WHERE run_id='${E2E_MULTI_AGENT_RUN_ID}'" "f" 180; then
      ok "运行已离开 RUNNING/PLANNING（续跑到终态或失败）"
    else
      ng "运行仍在进行中或未变化：$(psqlq "SELECT status FROM wgh_multi_agent_run WHERE run_id='${E2E_MULTI_AGENT_RUN_ID}'")"
    fi
  fi
else
  ng "多 Agent 续跑未执行：需要一条真实中断的 RUNNING 运行（E2E_MULTI_AGENT_RUN_ID）与可用模型，"
  echo "      请按 docs/testing/test-cases.md §44.3 准备后重跑（本用例禁止静默跳过）。"
fi

# ---------- R5: 会话级待办查询 ----------
echo ""
echo "[R5] 会话级待办查询"
WIN=$(psqlq "SELECT id FROM wgh_chat_window WHERE deleted=0 AND status=1 ORDER BY id LIMIT 1")
if [ -z "$WIN" ]; then
  ng "没有可用聊天窗口，R5 无法执行"
else
  SID=$(curl -s -X POST "$SRV/api/chat/windows/$WIN/sessions" -H "$AUTH" \
    -H "Content-Type: application/json" -d '{}' \
    | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('id',''))" 2>/dev/null)
  if [ -z "${SID:-}" ]; then
    ng "创建会话失败（window=$WIN）"
  else
    PEND=$(curl -s "$SRV/api/chat/sessions/$SID/pending-task" -H "$AUTH")
    HP=$(json_field "$PEND" hasPending)
    [ "$HP" = "False" ] && ok "空会话 hasPending=false" || ng "空会话待办返回异常：$PEND"
    RESUME_EMPTY=$(curl -s -X POST "$SRV/api/chat/sessions/$SID/resume-task" -H "$AUTH")
    MSG_EMPTY=$(json_field "$RESUME_EMPTY" message)
    echo "$MSG_EMPTY" | grep -q "没有可继续执行的任务" && ok "空会话恢复提示正确" || ng "空会话恢复返回：$RESUME_EMPTY"
    # 越权：使用一个必然不属于当前用户的 sessionId（0 或极大值）
    DENY=$(curl -s "$SRV/api/chat/sessions/999999999/pending-task" -H "$AUTH" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(d.get('code'))" 2>/dev/null)
    [ "$DENY" != "200" ] && ok "越权会话被拒绝 code=$DENY" || ng "越权会话未被拒绝"
  fi
fi

# ---------- 汇总 ----------
echo ""
echo "=============================================="
echo " 结果：通过 $PASS / 失败 $FAIL"
echo "=============================================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
