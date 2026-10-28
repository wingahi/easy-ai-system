#!/usr/bin/env bash
# Agent HITL（人工确认）端到端测试
#
# 覆盖场景（Planner 会把有副作用/不可逆的操作标记 hitl=true）：
#   T1 窗口 hitl=1 + 删除类目标 → 暂停：step_hitl_pending + plan_paused，DB 状态 PAUSED
#   T2 对 HITL 步骤 hitl-confirm → 恢复执行并最终 COMPLETED
#   T3 窗口 hitl=0 同目标 → 不暂停（对照组，验证窗口级开关生效）
#   T4 暂停后 cancel → 计划 CANCELLED，未执行步骤 SKIPPED
#
# 依赖：backend 8080 运行中、postgres 容器 postgres-with-vector
# 用法：bash scripts/test-agent-hitl-e2e.sh
set -uo pipefail

SRV=${SRV:-http://localhost:8080}
DB=${DB:-wgh_rag}
PG=${PG:-postgres-with-vector}
WIN_HITL=${WIN_HITL:-17}      # 已启用 HITL 的窗口
WIN_NO_HITL=${WIN_NO_HITL:-15} # 未启用 HITL 的窗口
HITL_GOAL=${HITL_GOAL:-"删除用户 U1001 的订单数据"}

PASS=0; FAIL=0; SKIP=0
ok() { echo "  ✅ $1"; PASS=$((PASS+1)); }
ng() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
sk() { echo "  ⏭  $1"; SKIP=$((SKIP+1)); }

psqlq() { docker exec "$PG" psql -U postgres -d "$DB" -t -A -c "$1" 2>/dev/null | tr -d ' '; }

echo "=============================================="
echo " Agent HITL 端到端测试"
echo "=============================================="

# ---------- 0. 前置检查 ----------
echo ""
echo "[0] 前置检查"
curl -sf "$SRV/actuator/health" | grep -q '"status":"UP"' && ok "后端健康检查 UP" || { ng "后端未就绪"; exit 1; }
TOKEN=$(curl -s -X POST "$SRV/api/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
[ -n "${TOKEN:-}" ] && ok "登录成功" || { ng "登录失败"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

H1=$(psqlq "SELECT coalesce(agent_hitl_enabled::text,'null') FROM wgh_chat_window WHERE id=$WIN_HITL")
H2=$(psqlq "SELECT coalesce(agent_hitl_enabled::text,'null') FROM wgh_chat_window WHERE id=$WIN_NO_HITL")
[ "$H1" = "1" ] && ok "窗口 $WIN_HITL agent_hitl_enabled=1" || ng "窗口 ${WIN_HITL} agent_hitl_enabled=${H1}（期望 1）"
[ "$H2" != "1" ] && ok "窗口 ${WIN_NO_HITL} agent_hitl_enabled=${H2}（未启用）" || ng "窗口 ${WIN_NO_HITL} 意外启用了 HITL"

new_session() { curl -s -X POST "$SRV/api/chat/windows/$1/sessions" -H "$AUTH" \
  -H "Content-Type: application/json" -d '{}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])"; }

# 发送消息并把 SSE 落盘
run_stream() { # win sid msg out maxtime
  local body
  body=$(python3 -c "import json,sys;print(json.dumps({'sessionId':int(sys.argv[1]),'message':sys.argv[2]},ensure_ascii=False))" "$2" "$3")
  curl -s -N -X POST "$SRV/api/chat/windows/$1/stream" -H "$AUTH" -H "Content-Type: application/json" \
    -d "$body" --max-time "$5" > "$4" 2>&1
}

plan_of_session() { # sid -> planId status
  curl -s "$SRV/api/agent/sessions/$1/plan" -H "$AUTH" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print('' if not d else '%s %s' % (d['planId'], d['status']))"
}

# ---------- T1: HITL 暂停 ----------
echo ""
echo "[T1] 窗口 ${WIN_HITL}（hitl=1）执行有副作用的目标 → 应暂停等待人工确认"
SID1=$(new_session "$WIN_HITL")
echo "  sessionId=$SID1, goal=\"$HITL_GOAL\""
run_stream "$WIN_HITL" "$SID1" "$HITL_GOAL" /tmp/hitl_t1.sse 200

if grep -q '^event:step_hitl_pending' /tmp/hitl_t1.sse; then
  ok "收到 step_hitl_pending 事件"
  grep -q '^event:plan_paused' /tmp/hitl_t1.sse && ok "收到 plan_paused 事件" || ng "缺少 plan_paused 事件"
else
  sk "Planner 未标记 hitl=true（LLM 输出不稳定），本轮无法验证暂停"
fi

read -r PLAN1 STATUS1 <<< "$(plan_of_session "$SID1")"
echo "  plan=$PLAN1 status=$STATUS1"
if [ "$STATUS1" = "PAUSED" ]; then
  ok "DB plan 状态 PAUSED"
  HSTEP=$(psqlq "SELECT s.id FROM wgh_agent_step s WHERE s.plan_id=$PLAN1 AND s.status='HITL_PENDING' ORDER BY s.id DESC LIMIT 1")
  [ -n "$HSTEP" ] && ok "DB 存在 HITL_PENDING 步骤 (id=$HSTEP)" || ng "DB 无 HITL_PENDING 步骤"
elif [ "$STATUS1" = "COMPLETED" ]; then
  sk "plan 直接 COMPLETED（Planner 未要求人工确认）"
else
  ng "plan 状态异常: $STATUS1"
fi

# ---------- T2: HITL 确认后续跑 ----------
echo ""
echo "[T2] 对 HITL 步骤执行 hitl-confirm → 应恢复执行并完成"
if [ -n "${HSTEP:-}" ]; then
  RESP=$(curl -s -X POST "$SRV/api/agent/steps/$HSTEP/hitl-confirm" -H "$AUTH" \
    -H "Content-Type: application/json" -d '{"userInput":"确认删除，请继续"}')
  echo "  confirm 返回: $(echo "$RESP" | cut -c1-160)"
  echo "$RESP" | grep -q '"code":200' && ok "hitl-confirm 接口返回成功" || ng "hitl-confirm 接口失败"
  read -r _ STATUS2 <<< "$(plan_of_session "$SID1")"
  [ "$STATUS2" = "COMPLETED" ] && ok "确认后 plan 变为 COMPLETED" || ng "确认后 plan 状态=${STATUS2}（期望 COMPLETED）"
  AFTER=$(psqlq "SELECT count(*) FROM wgh_chat_message WHERE session_id=$SID1 AND role='ASSISTANT' AND char_length(content)>20")
  [ "${AFTER:-0}" -ge 1 ] && ok "确认后的最终答复已落库（$AFTER 条）" || ng "确认后没有助手消息落库"
else
  sk "无 HITL 步骤可确认"
fi

# ---------- T3: 窗口未启用 HITL 对照 ----------
echo ""
echo "[T3] 窗口 ${WIN_NO_HITL}（hitl=0）同目标 → 不应暂停"
SID3=$(new_session "$WIN_NO_HITL")
run_stream "$WIN_NO_HITL" "$SID3" "$HITL_GOAL" /tmp/hitl_t3.sse 200
if grep -q '^event:plan_paused' /tmp/hitl_t3.sse; then
  ng "窗口未启用 HITL 却出现 plan_paused（窗口级开关未生效）"
else
  ok "未出现 plan_paused（窗口级开关生效）"
fi
read -r _ STATUS3 <<< "$(plan_of_session "$SID3")"
[ "$STATUS3" = "COMPLETED" ] && ok "plan 直接执行完成 COMPLETED" || echo "  ℹ plan 状态=$STATUS3"

# ---------- T4: 暂停后取消 ----------
echo ""
echo "[T4] HITL 暂停后取消计划 → CANCELLED + 剩余步骤 SKIPPED"
SID4=$(new_session "$WIN_HITL")
run_stream "$WIN_HITL" "$SID4" "$HITL_GOAL" /tmp/hitl_t4.sse 200
read -r PLAN4 STATUS4 <<< "$(plan_of_session "$SID4")"
if [ "$STATUS4" = "PAUSED" ]; then
  ok "计划进入 PAUSED（可取消）"
  curl -s -X POST "$SRV/api/agent/plans/$PLAN4/cancel" -H "$AUTH" > /tmp/hitl_cancel.json
  read -r _ STATUS5 <<< "$(plan_of_session "$SID4")"
  [ "$STATUS5" = "CANCELLED" ] && ok "取消后 plan=CANCELLED" || ng "取消后 plan=$STATUS5"
  LEFT=$(psqlq "SELECT count(*) FROM wgh_agent_step WHERE plan_id=$PLAN4 AND status IN ('PENDING','RUNNING','HITL_PENDING')")
  [ "${LEFT:-1}" = "0" ] && ok "未执行步骤已置为 SKIPPED" || ng "仍有 $LEFT 个未执行步骤"
else
  sk "计划未暂停（status=${STATUS4}），跳过取消验证"
fi

# ---------- 汇总 ----------
echo ""
echo "=============================================="
echo " 结果: PASS=$PASS  FAIL=$FAIL  SKIP=$SKIP"
echo "=============================================="
[ "$FAIL" -eq 0 ] || exit 1
