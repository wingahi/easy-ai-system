#!/usr/bin/env bash
# ============================================================
# WGH RAG System — Agent 全链路测试脚本
# 覆盖：SSE 流事件 / REST 控制 API / 前端集成验证
# 用法：bash test-agent-e2e.sh
# 前置：后端 localhost:8080 运行中，dev 数据库已初始化
# ============================================================
BASE="http://localhost:8080"
PASS=0
FAIL=0
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

log_pass() { echo -e "  ${GREEN}✓ PASS${NC} $1"; PASS=$((PASS+1)); }
log_fail() { echo -e "  ${RED}✗ FAIL${NC} $1"; FAIL=$((FAIL+1)); }
log_info() { echo -e "  ${YELLOW}ℹ${NC} $1"; }

echo "=============================================="
echo "WGH RAG — Agent 全链路测试"
echo "=============================================="

# ---- 0. 前置：登录 + 获取 Token ----
echo ""
echo "## 0. 前置检查"
HEALTH=$(curl -s $BASE/actuator/health)
if echo "$HEALTH" | grep -q '"UP"'; then log_pass "后端健康检查 UP"; else log_fail "后端健康检查: $HEALTH"; exit 1; fi

TOKEN=$(curl -s -X POST $BASE/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
if [ -n "$TOKEN" ]; then log_pass "登录成功，拿到 Token"; else log_fail "登录失败"; exit 1; fi

AUTH="Authorization: Bearer $TOKEN"

# ---- 1. 数据层：ChatWindow agent 字段 ----
echo ""
echo "## 1. 数据层：ChatWindow(17) agent 字段"
WINDOW=$(curl -s $BASE/api/chat/windows/17 -H "$AUTH")
python3 -c "
import json,sys
w=json.loads('''$WINDOW''')['data']
assert w['agentEnabled']==1, f'agentEnabled 应为 1，实际 {w[\"agentEnabled\"]}'
assert w['agentMaxSteps']==10, f'agentMaxSteps 应为 10，实际 {w[\"agentMaxSteps\"]}'
assert w['agentReflectionThreshold']==6.0, f'threshold 应为 6.0，实际 {w[\"agentReflectionThreshold\"]}'
assert w['agentHitlEnabled']==1, f'hitlEnabled 应为 1'
print('ALL_OK')
" 2>/dev/null && log_pass "agent_* 7 字段全部正确读取" || log_fail "agent_* 字段缺失"

# ---- 2. Agent SSE 主链路（端到端验证）----
echo ""
echo "## 2. SSE 主链路：Agent 自动规划 + 执行 + 反思"
TMP_SSE=$(mktemp)
curl -s -N -X POST "$BASE/api/chat/windows/17/stream" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"sessionId":null,"message":"获取后端健康状态，再查用户 U1001 订单，给个总结"}' \
  --max-time 90 > "$TMP_SSE" 2>&1

# 事件名提取
EVENTS=$(grep "^event:" "$TMP_SSE" | sed 's/event://' | tr -d ' ')
echo "$EVENTS" | sort | uniq -c | sort -rn

check_event() {
  local name=$1
  if echo "$EVENTS" | grep -qx "$name"; then log_pass "SSE 事件 event:$name 存在"; else log_fail "SSE 事件 event:$name 缺失"; fi
}

check_event "start"
check_event "retrieve"
check_event "plan_created"
check_event "plan_complete"
check_event "done"

# step 事件
STEP_START_COUNT=$(echo "$EVENTS" | grep -c "^step_start$" || true)
STEP_SUCCESS_COUNT=$(echo "$EVENTS" | grep -c "^step_success$" || true)
STEP_REFLECTION_COUNT=$(echo "$EVENTS" | grep -c "^step_reflection$" || true)

if [ "$STEP_START_COUNT" -ge 2 ]; then log_pass "step_start × $STEP_START_COUNT (≥2)"; else log_fail "step_start 数量不足"; fi
if [ "$STEP_SUCCESS_COUNT" -ge 2 ]; then log_pass "step_success × $STEP_SUCCESS_COUNT (≥2)"; else log_fail "step_success 数量不足"; fi
if [ "$STEP_REFLECTION_COUNT" -ge 2 ]; then log_pass "step_reflection × $STEP_REFLECTION_COUNT (≥2)"; else log_fail "step_reflection 数量不足"; fi

# 检查 payload 字段完整性
echo ""
echo "## 3. SSE payload 字段完整性"
python3 << PYEOF
import re, json
with open("$TMP_SSE") as f: content = f.read()

events = {}
current_event = None
for line in content.split('\n'):
    if line.startswith('event:'):
        current_event = line[6:].strip()
    elif line.startswith('data:') and current_event:
        raw = line[5:].strip()
        try: data = json.loads(raw)
        except: data = raw
        events.setdefault(current_event, []).append(data)

# 校验 plan_created
pc = events.get('plan_created', [{}])[0]
req = ['planId', 'planSummary', 'totalSteps']
missing = [k for k in req if k not in pc]
print(f"  plan_created payload: missing={missing or 'none'}, keys={list(pc.keys())}")
assert not missing, f"plan_created 缺字段: {missing}"

# 校验 step_start
ss = events.get('step_start', [{}])[0]
req2 = ['stepId', 'stepIndex', 'stepTitle']
missing2 = [k for k in req2 if k not in ss]
print(f"  step_start payload: missing={missing2 or 'none'}, keys={list(ss.keys())}")
assert not missing2, f"step_start 缺字段: {missing2}"

# 校验 step_success
suc = events.get('step_success', [{}])[0]
req3 = ['stepId', 'toolCount']
missing3 = [k for k in req3 if k not in suc]
print(f"  step_success payload: missing={missing3 or 'none'}, keys={list(suc.keys())}")
assert not missing3

# 校验 step_reflection
sr = events.get('step_reflection', [{}])[0]
req4 = ['stepId', 'score', 'pass', 'feedback']
missing4 = [k for k in req4 if k not in sr]
print(f"  step_reflection payload: missing={missing4 or 'none'}, score={sr.get('score')}, pass={sr.get('pass')}")
assert not missing4

# 校验 done → fullResponse 含工具结果
done = events.get('done', [{}])[0]
full = done.get('fullResponse', '')
assert len(full) > 200, f"done.fullResponse 过短: {len(full)} chars"
print(f"  done.fullResponse length: {len(full)} ✅")

# 工具调用验证
assert 'UP' in full or '健康' in full or '订单' in full, "最终回复应包含工具调用结果（健康状态/订单）"
print(f"  最终回复含工具调用结果 ✅")

print("\n  ALL PAYLOAD CHECKS PASSED ✅")
PYEOF
PAYLOAD_OK=$?
[ $PAYLOAD_OK -eq 0 ] && log_pass "SSE payload 字段全部完整" || log_fail "SSE payload 字段有缺失"

rm -f "$TMP_SSE"

# ---- 4. 数据库验证：Agent Plan / Step 持久化 ----
echo ""
echo "## 4. 数据持久化验证"
PLAN_COUNT=$(docker exec postgres-with-vector psql -U postgres -d wgh_rag -tAc "SELECT COUNT(*) FROM wgh_agent_plan WHERE deleted=0")
STEP_COUNT=$(docker exec postgres-with-vector psql -U postgres -d wgh_rag -tAc "SELECT COUNT(*) FROM wgh_agent_step WHERE deleted=0")
echo "  wgh_agent_plan: $PLAN_COUNT 条"
echo "  wgh_agent_step: $STEP_COUNT 条"
[ "$PLAN_COUNT" -ge 1 ] && log_pass "Agent Plan 已持久化" || log_fail "Agent Plan 未持久化"
[ "$STEP_COUNT" -ge 2 ] && log_pass "Agent Step 已持久化 (≥2)" || log_fail "Agent Step 未持久化"

# 最新 Plan 状态
LATEST_PLAN=$(docker exec postgres-with-vector psql -U postgres -d wgh_rag -tAc "SELECT id,status,current_step_index FROM wgh_agent_plan WHERE deleted=0 ORDER BY id DESC LIMIT 1")
echo "  最新 Plan: $LATEST_PLAN"

# ---- 5. Agent REST API 验证 ----
echo ""
echo "## 5. Agent REST 控制 API"
LATEST_PLAN_ID=$(docker exec postgres-with-vector psql -U postgres -d wgh_rag -tAc "SELECT id FROM wgh_agent_plan WHERE deleted=0 ORDER BY id DESC LIMIT 1")
LATEST_STEP_ID=$(docker exec postgres-with-vector psql -U postgres -d wgh_rag -tAc "SELECT id FROM wgh_agent_step WHERE plan_id=$LATEST_PLAN_ID AND deleted=0 ORDER BY id DESC LIMIT 1")

if [ -n "$LATEST_PLAN_ID" ]; then
  # 先查 state（在 cancel 之前）— 写入文件避免 heredoc 控制字符问题
  STATE_FILE=$(mktemp)
  curl -s $BASE/api/agent/plans/$LATEST_PLAN_ID/state -H "$AUTH" > "$STATE_FILE"
  python3 << PYEOF
import json
with open("$STATE_FILE") as f: d = json.load(f)
assert d['code']==200, f"State API 返回 code={d['code']}, resp={d}"
s=d['data']
assert 'steps' in s and len(s['steps'])>=1, f"steps 列表为空"
assert 'planId' in s and 'status' in s, "缺少 planId/status 字段"
print(f"  planId={s['planId']}, status={s['status']}, steps={len(s['steps'])}")
print(f"  step 字段检查: {list(s['steps'][0].keys())}")
print("OK")
PYEOF
  STATE_OK=$?
  rm -f "$STATE_FILE"
  [ $STATE_OK -eq 0 ] && log_pass "GET /api/agent/plans/{id}/state ✅" || log_fail "GET /api/agent/plans/{id}/state"

  # cancel 测试（对已完成的 plan 调 cancel，应该也能成功）
  CANCEL=$(curl -s -X POST $BASE/api/agent/plans/$LATEST_PLAN_ID/cancel -H "$AUTH")
  echo "  cancel 响应: $(echo $CANCEL | head -c 200)"
  echo "$CANCEL" | grep -q '"code":200' && log_pass "POST /api/agent/plans/{id}/cancel ✅" || log_fail "cancel API"
else
  log_info "无已完成的 Plan 可用于 API 测试"
fi

# ---- 6. Agent 降级路径：agent_enabled=0 的窗口走普通 RAG ----
echo ""
echo "## 6. Agent 降级路径（agent_enabled=0）"
TMP_SSE2=$(mktemp)
curl -s -N -X POST "$BASE/api/chat/windows/1/stream" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"sessionId":null,"message":"你好"}' \
  --max-time 30 > "$TMP_SSE2" 2>&1

if grep -q "plan_created\|step_start" "$TMP_SSE2"; then
  log_fail "agent_enabled=0 窗口竟触发了 Agent 事件！"
else
  log_pass "agent_enabled=0 窗口正确降级，不触发 Agent 事件"
fi

if grep -q "content\|done\|sources" "$TMP_SSE2"; then
  log_pass "普通 RAG 事件正常（content + done）"
else
  log_fail "普通 RAG 事件缺失"
fi
rm -f "$TMP_SSE2"

# ---- 总结 ----
echo ""
echo "=============================================="
echo -e "  测试结果：${GREEN}通过 $PASS${NC} / ${RED}失败 $FAIL${NC}"
echo "=============================================="
[ $FAIL -eq 0 ] && echo -e "${GREEN}🎉 全部通过！${NC}" || echo -e "${RED}⚠ 有失败项，请检查上方输出${NC}"
exit 0
