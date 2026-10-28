#!/usr/bin/env bash
# 多 Agent 协作冒烟测试
# 覆盖：登录 → crewId 模板创建 Run → SSE 流事件序列 → 终态校验
# 依赖：backend 8080 运行中、wgh_m_agent_crew id=1（双 Agent 顺序模式，模型已启用）
# 用法：bash scripts/test-multi-agent-smoke.sh
set -uo pipefail

SRV=${SRV:-http://localhost:8080}
DB=${DB:-wgh_rag}
PG=${PG:-postgres-with-vector}

PASS=0; FAIL=0
ok() { echo "  ✅ $1"; PASS=$((PASS+1)); }
ng() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }

psqlq() { docker exec "$PG" psql -U postgres -d "$DB" -t -A -c "$1" 2>/dev/null | tr -d ' '; }

echo "== 多 Agent 冒烟 =="

# 0. 登录
TOKEN=$(curl -s -X POST $SRV/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
[ -n "$TOKEN" ] && ok "登录成功" || { echo "登录失败，退出"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

# 1. Crew 列表可查
CREW_COUNT=$(curl -s $SRV/api/agent-multi/crews -H "$AUTH" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))" 2>/dev/null)
[ "${CREW_COUNT:-0}" -ge 1 ] && ok "Crew 列表查询（$CREW_COUNT 条）" || ng "Crew 列表查询失败"

# 2. 创建 Run（crewId 模板补齐 agentsJson/crewConfigJson）
RUN_ID=$(curl -s -X POST $SRV/api/agent-multi/runs -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"crewId":1,"sessionId":1,"executionMode":"CREW_SEQUENTIAL","input":"用一句话介绍 RAG 的核心价值","maxRounds":3}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['runId'])" 2>/dev/null)
[ -n "$RUN_ID" ] && ok "Run 创建成功 runId=$RUN_ID" || { ng "Run 创建失败"; exit 1; }

# crewId 模板补齐校验
CREW_CFG=$(psqlq "SELECT crew_config_json FROM wgh_m_agent_run WHERE run_id='$RUN_ID';")
[ "$CREW_CFG" = '{"mode":"SEQUENTIAL"}' ] && ok "crewId 模板 crewConfigJson 已补齐" || ng "crewConfigJson 补齐异常: $CREW_CFG"

# 3. SSE 流执行（最多等 120s）
EVENTS_FILE=$(mktemp)
curl -s -N --max-time 120 $SRV/api/agent-multi/runs/$RUN_ID/stream -H "$AUTH" > "$EVENTS_FILE" 2>/dev/null || true

for EVT in multi_agent_start agent_start agent_done multi_agent_complete; do
  if grep -q "event: *$EVT\|\"$EVT\"\|$EVT" "$EVENTS_FILE"; then ok "SSE 事件 $EVT 收到"; else ng "SSE 事件 $EVT 缺失"; fi
done

# 双 Agent 各自执行
AGENT_STARTS=$(grep -c "agent_start" "$EVENTS_FILE" || true)
[ "$AGENT_STARTS" -ge 2 ] && ok "两个 Agent 均启动（$AGENT_STARTS 次 agent_start）" || ng "agent_start 次数异常: $AGENT_STARTS"

# 4. 终态校验
sleep 2
STATUS=$(psqlq "SELECT status FROM wgh_m_agent_run WHERE run_id='$RUN_ID';")
[ "$STATUS" = "COMPLETED" ] && ok "Run 终态 COMPLETED" || ng "Run 终态异常: $STATUS"

STEPS=$(psqlq "SELECT COUNT(*) FROM wgh_m_agent_step WHERE run_id='$RUN_ID' AND status='SUCCESS';")
[ "${STEPS:-0}" -ge 2 ] && ok "步骤持久化 SUCCESS ≥ 2（实际 ${STEPS}）" || ng "步骤持久化异常: $STEPS"

# 5. 状态查询接口
STATE=$(curl -s $SRV/api/agent-multi/runs/$RUN_ID/state -H "$AUTH" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(d['run']['status'], len(d['steps']))" 2>/dev/null)
echo "$STATE" | grep -q "COMPLETED" && ok "state 接口返回 COMPLETED" || ng "state 接口异常: $STATE"

rm -f "$EVENTS_FILE"
echo "=============================================="
echo " 结果: PASS=$PASS FAIL=$FAIL"
echo "=============================================="
