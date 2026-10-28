#!/usr/bin/env bash
# 工作流编排端到端测试
#
# 覆盖场景：
#   T1 工作流 CRUD 与图校验（非法图被拒绝）
#   T2 线性流：START → VARIABLE → END，验证变量渲染与最终输出
#   T3 条件分支：CONDITION 的 true / false 两个出口分别命中不同 END
#   T4 循环：LOOP maxIterations=3，验证轮次自增 / 中断条件 / done 出口
#   T5 HITL：运行到人工确认节点暂停（PAUSED），确认后从断点续跑至 COMPLETED
#   T6 取消：暂停状态下取消运行 → CANCELLED，待确认节点标记 SKIPPED
#   T7 LLM 节点（可选）：依赖已启用且可用的聊天模型
#   T8 知识库检索节点（可选）：依赖已有文件的知识库与可用的 embedding 模型
#   T9 版本管理：自动快照 / 版本列表 / 回滚生成新版本
#   T10 定时 / Webhook 触发：CRON 自动触发、停用停止、Webhook 免登录触发
#   T11 模板市场：内置模板列表、实例化（标准创建链路）、审批/巡检模板端到端运行、占位模型自动填充
#
# 依赖：backend 8080 运行中、postgres 容器 postgres-with-vector
# 用法：bash scripts/test-workflow-e2e.sh
set -uo pipefail

SRV=${SRV:-http://localhost:8080}
DB=${DB:-wgh_rag}
PG=${PG:-postgres-with-vector}

PASS=0; FAIL=0; SKIP=0
ok() { echo "  ✅ $1"; PASS=$((PASS+1)); }
ng() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
sk() { echo "  ⏭  $1"; SKIP=$((SKIP+1)); }

psqlq() { docker exec "$PG" psql -U postgres -d "$DB" -t -A -c "$1" 2>/dev/null | tr -d ' '; }

# ---------- SSE 解析 ----------
sse_field() { # file event field
  python3 - "$1" "$2" "$3" <<'PY'
import json, sys
path, ev, field = sys.argv[1], sys.argv[2], sys.argv[3]
raw = open(path, encoding='utf-8', errors='ignore').read().replace('\r', '')
for block in raw.split('\n\n'):
    lines = block.split('\n')
    names = [l[6:].strip() for l in lines if l.startswith('event:')]
    data = [l[5:] for l in lines if l.startswith('data:')]
    if names and names[0] == ev and data:
        try:
            v = json.loads('\n'.join(data)).get(field, '')
        except Exception:
            v = ''
        print(v)
PY
}

sse_count() { # file event
  python3 - "$1" "$2" <<'PY'
import sys
path, ev = sys.argv[1], sys.argv[2]
raw = open(path, encoding='utf-8', errors='ignore').read().replace('\r', '')
n = 0
for block in raw.split('\n\n'):
    names = [l[6:].strip() for l in block.split('\n') if l.startswith('event:')]
    if names and names[0] == ev:
        n += 1
print(n)
PY
}

# ---------- HTTP ----------
mkpayload() { # graph_file name out_file
  python3 - "$1" "$2" "$3" <<'PY'
import json, sys
graph = json.load(open(sys.argv[1], encoding='utf-8'))
payload = {'name': sys.argv[2], 'description': 'e2e 自动测试', 'graphJson': json.dumps(graph, ensure_ascii=False)}
json.dump(payload, open(sys.argv[3], 'w', encoding='utf-8'), ensure_ascii=False)
PY
}

create_wf() { # graph_file name
  mkpayload "$1" "$2" /tmp/wf_payload.json
  curl -s -X POST "$SRV/api/workflows" -H "$AUTH" -H 'Content-Type: application/json' -d @/tmp/wf_payload.json \
    | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('id',''))" 2>/dev/null
}

update_wf() { # id graph_file
  mkpayload "$2" "updated" /tmp/wf_payload.json
  curl -s -X PUT "$SRV/api/workflows/$1" -H "$AUTH" -H 'Content-Type: application/json' -d @/tmp/wf_payload.json \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('code'), (d.get('data') or {}).get('version',''))" 2>/dev/null
}

run_wf() { # id input out_file max_time
  local body
  body=$(python3 -c "import json,sys;print(json.dumps({'input':sys.argv[1]},ensure_ascii=False))" "$2")
  curl -s -N -X POST "$SRV/api/workflows/$1/run" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "$body" --max-time "$4" > "$3" 2>&1
}

resume_wf() { # runId userInput out_file max_time
  local body
  body=$(python3 -c "import json,sys;print(json.dumps({'userInput':sys.argv[1]},ensure_ascii=False))" "$2")
  curl -s -N -X POST "$SRV/api/workflows/runs/$1/hitl-confirm" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "$body" --max-time "$4" > "$3" 2>&1
}

echo "=============================================="
echo " 工作流编排端到端测试"
echo "=============================================="

# ---------- 0. 前置 ----------
echo ""
echo "[0] 前置检查"
curl -sf "$SRV/actuator/health" | grep -q '"status":"UP"' && ok "后端健康检查 UP" || { ng "后端未就绪"; exit 1; }
TOKEN=$(curl -s -X POST "$SRV/api/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
[ -n "${TOKEN:-}" ] && ok "登录成功" || { ng "登录失败"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

# ---------- T1: CRUD 与校验 ----------
echo ""
echo "[T1] 工作流 CRUD 与图校验"

cat > /tmp/wf_valid.json <<'JSON'
{
  "nodes": [
    {"id": "start", "type": "START", "name": "开始", "position": {"x": 0, "y": 0}, "config": {}, "onError": "STOP"},
    {"id": "end", "type": "END", "name": "结束", "position": {"x": 240, "y": 0}, "config": {"output": "{{input}}"}, "onError": "STOP"}
  ],
  "edges": [
    {"id": "e1", "source": "start", "target": "end", "sourceHandle": "default"}
  ]
}
JSON
WF1=$(create_wf /tmp/wf_valid.json "e2e-线性最小流")
[ -n "$WF1" ] && ok "创建工作流成功 id=$WF1" || { ng "创建工作流失败"; exit 1; }

curl -s "$SRV/api/workflows/$WF1" -H "$AUTH" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
assert d['version']==1, d
print(d['name'])" >/dev/null 2>&1 && ok "详情查询正常（version=1）" || ng "详情查询异常"

VALID=$(curl -s -X POST "$SRV/api/workflows/$WF1/validate" -H "$AUTH" \
  | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'] or []))" 2>/dev/null)
[ "$VALID" = "0" ] && ok "合法图校验通过" || ng "合法图校验返回 ${VALID} 条错误"

cat > /tmp/wf_invalid.json <<'JSON'
{
  "nodes": [
    {"id": "s", "type": "START", "name": "开始", "position": {"x": 0, "y": 0}, "config": {}, "onError": "STOP"},
    {"id": "c", "type": "CONDITION", "name": "空条件", "position": {"x": 200, "y": 0}, "config": {"expression": ""}, "onError": "STOP"}
  ],
  "edges": [
    {"id": "e1", "source": "s", "target": "c", "sourceHandle": "default"}
  ]
}
JSON
mkpayload /tmp/wf_invalid.json "e2e-非法图" /tmp/wf_payload.json
BADCODE=$(curl -s -X POST "$SRV/api/workflows" -H "$AUTH" -H 'Content-Type: application/json' \
  -d @/tmp/wf_payload.json | python3 -c "import sys,json;print(json.load(sys.stdin).get('code'))" 2>/dev/null)
[ "$BADCODE" != "200" ] && ok "非法图（无 END / 空条件 / 无出边）被拒绝 code=$BADCODE" || ng "非法图未被拒绝"

NODES_CNT=$(psqlq "SELECT jsonb_array_length(graph_json->'nodes') FROM wgh_workflow WHERE id=$WF1")
[ "$NODES_CNT" = "2" ] && ok "graph_json 以 JSONB 落库（nodes=2）" || ng "graph_json 落库异常 nodes=${NODES_CNT}"

# ---------- T2: 线性流 + 变量 ----------
echo ""
echo "[T2] 线性流：START → VARIABLE → END"
cat > /tmp/wf_linear.json <<'JSON'
{
  "nodes": [
    {"id": "start", "type": "START", "name": "开始", "position": {"x": 0, "y": 0}, "config": {}, "onError": "STOP"},
    {"id": "v1", "type": "VARIABLE", "name": "变量赋值", "position": {"x": 200, "y": 0},
     "config": {"assignments": [{"name": "greeting", "value": "你好 {{input}}"}]}, "onError": "STOP"},
    {"id": "end", "type": "END", "name": "结束", "position": {"x": 400, "y": 0},
     "config": {"output": "{{vars.greeting}} / {{input}}"}, "onError": "STOP"}
  ],
  "edges": [
    {"id": "e1", "source": "start", "target": "v1", "sourceHandle": "default"},
    {"id": "e2", "source": "v1", "target": "end", "sourceHandle": "default"}
  ]
}
JSON
WF2=$(create_wf /tmp/wf_linear.json "e2e-线性变量流")
run_wf "$WF2" "hello" /tmp/wf_t2.sse 60

[ "$(sse_count /tmp/wf_t2.sse workflow_start)" = "1" ] && ok "收到 workflow_start" || ng "缺少 workflow_start"
[ "$(sse_count /tmp/wf_t2.sse workflow_complete)" = "1" ] && ok "收到 workflow_complete" || ng "缺少 workflow_complete"
[ "$(sse_count /tmp/wf_t2.sse workflow_failed)" = "0" ] && ok "无 workflow_failed" || ng "出现 workflow_failed"
OUT2=$(sse_field /tmp/wf_t2.sse workflow_complete output | tail -1)
[ "$OUT2" = "你好 hello / hello" ] && ok "最终输出正确：$OUT2" || ng "最终输出错误：$OUT2"

RUN2=$(psqlq "SELECT max(id) FROM wgh_workflow_run WHERE workflow_id=$WF2")
ST2=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$RUN2")
[ "$ST2" = "COMPLETED" ] && ok "运行状态 COMPLETED" || ng "运行状态=${ST2}"
NR2=$(psqlq "SELECT count(*) FROM wgh_workflow_node_run WHERE run_id=$RUN2 AND status='SUCCESS'")
[ "$NR2" = "3" ] && ok "节点级记录 3 条全部 SUCCESS" || ng "节点级记录异常 count=${NR2}"

# ---------- T3: 条件分支 ----------
echo ""
echo "[T3] 条件分支：CONDITION true / false 出口"
cat > /tmp/wf_cond.json <<'JSON'
{
  "nodes": [
    {"id": "start", "type": "START", "name": "开始", "position": {"x": 0, "y": 0}, "config": {}, "onError": "STOP"},
    {"id": "cond", "type": "CONDITION", "name": "是否通过", "position": {"x": 200, "y": 0},
     "config": {"expression": "{{input}} contains 通过"}, "onError": "STOP"},
    {"id": "pass", "type": "END", "name": "通过", "position": {"x": 400, "y": -80},
     "config": {"output": "PASS:{{input}}"}, "onError": "STOP"},
    {"id": "reject", "type": "END", "name": "拒绝", "position": {"x": 400, "y": 80},
     "config": {"output": "FAIL:{{input}}"}, "onError": "STOP"}
  ],
  "edges": [
    {"id": "e1", "source": "start", "target": "cond", "sourceHandle": "default"},
    {"id": "e2", "source": "cond", "target": "pass", "sourceHandle": "true"},
    {"id": "e3", "source": "cond", "target": "reject", "sourceHandle": "false"}
  ]
}
JSON
WF3=$(create_wf /tmp/wf_cond.json "e2e-条件分支")
run_wf "$WF3" "订单审核通过" /tmp/wf_t3a.sse 60
OUT3A=$(sse_field /tmp/wf_t3a.sse workflow_complete output | tail -1)
[ "$OUT3A" = "PASS:订单审核通过" ] && ok "true 出口命中：$OUT3A" || ng "true 出口结果异常：$OUT3A"

run_wf "$WF3" "订单材料不全" /tmp/wf_t3b.sse 60
OUT3B=$(sse_field /tmp/wf_t3b.sse workflow_complete output | tail -1)
[ "$OUT3B" = "FAIL:订单材料不全" ] && ok "false 出口命中：$OUT3B" || ng "false 出口结果异常：$OUT3B"

# ---------- T4: 循环 ----------
echo ""
echo "[T4] 循环：LOOP maxIterations=3"
cat > /tmp/wf_loop.json <<'JSON'
{
  "nodes": [
    {"id": "start", "type": "START", "name": "开始", "position": {"x": 0, "y": 0}, "config": {}, "onError": "STOP"},
    {"id": "loop1", "type": "LOOP", "name": "循环", "position": {"x": 200, "y": 0},
     "config": {"maxIterations": 3, "breakCondition": ""}, "onError": "STOP"},
    {"id": "body", "type": "VARIABLE", "name": "循环体", "position": {"x": 420, "y": 0},
     "config": {"assignments": [{"name": "lastIter", "value": "{{loop.iteration}}"}]}, "onError": "STOP"},
    {"id": "end", "type": "END", "name": "结束", "position": {"x": 200, "y": 160},
     "config": {"output": "loopDone:{{vars.lastIter}}/{{loop.iteration}}"}, "onError": "STOP"}
  ],
  "edges": [
    {"id": "e1", "source": "start", "target": "loop1", "sourceHandle": "default"},
    {"id": "e2", "source": "loop1", "target": "body", "sourceHandle": "loop"},
    {"id": "e3", "source": "body", "target": "loop1", "sourceHandle": "default"},
    {"id": "e4", "source": "loop1", "target": "end", "sourceHandle": "done"}
  ]
}
JSON
WF4=$(create_wf /tmp/wf_loop.json "e2e-循环体")
run_wf "$WF4" "go" /tmp/wf_t4.sse 60

RUN4=$(psqlq "SELECT max(id) FROM wgh_workflow_run WHERE workflow_id=$WF4")
ST4=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$RUN4")
[ "$ST4" = "COMPLETED" ] && ok "循环运行 COMPLETED" || ng "循环运行状态=${ST4}"
BODY_CNT=$(psqlq "SELECT count(*) FROM wgh_workflow_node_run WHERE run_id=$RUN4 AND node_id='body' AND status='SUCCESS'")
[ "$BODY_CNT" = "3" ] && ok "循环体执行 3 轮" || ng "循环体执行轮次=${BODY_CNT}"
MAXIT=$(psqlq "SELECT coalesce(max(iteration),-1) FROM wgh_workflow_node_run WHERE run_id=$RUN4")
[ "$MAXIT" = "3" ] && ok "迭代序号自增到 3" || ng "最大迭代序号=${MAXIT}"
OUT4=$(sse_field /tmp/wf_t4.sse workflow_complete output | tail -1)
[ "$OUT4" = "loopDone:3/3" ] && ok "循环后输出正确：$OUT4" || ng "循环后输出错误：$OUT4"

# ---------- T5: HITL ----------
echo ""
echo "[T5] HITL：暂停 → 确认 → 续跑"
cat > /tmp/wf_hitl.json <<'JSON'
{
  "nodes": [
    {"id": "start", "type": "START", "name": "开始", "position": {"x": 0, "y": 0}, "config": {}, "onError": "STOP"},
    {"id": "n_hitl_1", "type": "HITL", "name": "人工确认", "position": {"x": 200, "y": 0},
     "config": {"prompt": "请确认是否发布本次变更"}, "onError": "STOP"},
    {"id": "end", "type": "END", "name": "结束", "position": {"x": 400, "y": 0},
     "config": {"output": "confirmed:{{n_hitl_1.text}}"}, "onError": "STOP"}
  ],
  "edges": [
    {"id": "e1", "source": "start", "target": "n_hitl_1", "sourceHandle": "default"},
    {"id": "e2", "source": "n_hitl_1", "target": "end", "sourceHandle": "default"}
  ]
}
JSON
WF5=$(create_wf /tmp/wf_hitl.json "e2e-人工确认")
run_wf "$WF5" "发布 v1" /tmp/wf_t5a.sse 60

[ "$(sse_count /tmp/wf_t5a.sse node_hitl_pending)" = "1" ] && ok "收到 node_hitl_pending" || ng "缺少 node_hitl_pending"
[ "$(sse_field /tmp/wf_t5a.sse node_hitl_pending prompt | tail -1)" = "请确认是否发布本次变更" ] \
  && ok "确认提示语渲染正确" || ng "确认提示语异常"
RUN5=$(psqlq "SELECT max(id) FROM wgh_workflow_run WHERE workflow_id=$WF5")
ST5=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$RUN5")
[ "$ST5" = "PAUSED" ] && ok "运行状态 PAUSED（断点已持久化）" || ng "运行状态=${ST5}"
PEND5=$(psqlq "SELECT status FROM wgh_workflow_node_run WHERE run_id=$RUN5 AND node_id='n_hitl_1'")
[ "$PEND5" = "HITL_PENDING" ] && ok "HITL 节点记录 HITL_PENDING" || ng "HITL 节点状态=${PEND5}"

resume_wf "$RUN5" "同意发布" /tmp/wf_t5b.sse 60
[ "$(sse_count /tmp/wf_t5b.sse workflow_complete)" = "1" ] && ok "确认后收到 workflow_complete" || ng "确认后缺少 workflow_complete"
OUT5=$(sse_field /tmp/wf_t5b.sse workflow_complete output | tail -1)
[ "$OUT5" = "confirmed:同意发布" ] && ok "续跑输出正确：$OUT5" || ng "续跑输出错误：$OUT5"
ST5B=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$RUN5")
[ "$ST5B" = "COMPLETED" ] && ok "续跑后运行 COMPLETED" || ng "续跑后状态=${ST5B}"

# ---------- T6: 取消 ----------
echo ""
echo "[T6] 取消：暂停状态取消运行"
run_wf "$WF5" "再次发布" /tmp/wf_t6a.sse 60
RUN6=$(psqlq "SELECT max(id) FROM wgh_workflow_run WHERE workflow_id=$WF5")
CANCODE=$(curl -s -X POST "$SRV/api/workflows/runs/$RUN6/cancel" -H "$AUTH" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('code'))" 2>/dev/null)
[ "$CANCODE" = "200" ] && ok "取消接口返回成功" || ng "取消接口 code=${CANCODE}"
ST6=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$RUN6")
[ "$ST6" = "CANCELLED" ] && ok "运行状态 CANCELLED" || ng "运行状态=${ST6}"
SK6=$(psqlq "SELECT status FROM wgh_workflow_node_run WHERE run_id=$RUN6 AND node_id='n_hitl_1'")
[ "$SK6" = "SKIPPED" ] && ok "待确认节点标记 SKIPPED" || ng "待确认节点状态=${SK6}"

# ---------- T7: LLM 节点（可选） ----------
echo ""
echo "[T7] LLM 节点（可选）"
MODEL_ID=$(psqlq "SELECT id FROM wgh_llm_model WHERE model_type='CHAT' AND status=1 AND deleted=0 ORDER BY id LIMIT 1")
if [ -z "$MODEL_ID" ]; then
  sk "没有启用的聊天模型，跳过"
else
  cat > /tmp/wf_llm.json <<JSON
{
  "nodes": [
    {"id": "start", "type": "START", "name": "开始", "position": {"x": 0, "y": 0}, "config": {}, "onError": "STOP"},
    {"id": "llm", "type": "LLM", "name": "大模型", "position": {"x": 200, "y": 0},
     "config": {"modelId": $MODEL_ID, "systemPrompt": "你是一个简洁的助手", "prompt": "用一句话回答：{{input}}"}, "onError": "STOP"},
    {"id": "end", "type": "END", "name": "结束", "position": {"x": 400, "y": 0},
     "config": {"output": "{{llm.text}}"}, "onError": "STOP"}
  ],
  "edges": [
    {"id": "e1", "source": "start", "target": "llm", "sourceHandle": "default"},
    {"id": "e2", "source": "llm", "target": "end", "sourceHandle": "default"}
  ]
}
JSON
  WF7=$(create_wf /tmp/wf_llm.json "e2e-LLM节点")
  run_wf "$WF7" "1+1 等于几？" /tmp/wf_t7.sse 120
  RUN7=$(psqlq "SELECT max(id) FROM wgh_workflow_run WHERE workflow_id=$WF7")
  ST7=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$RUN7")
  OUT7=$(sse_field /tmp/wf_t7.sse workflow_complete output | tail -1)
  if [ "$ST7" = "COMPLETED" ] && [ -n "$OUT7" ]; then
    ok "LLM 节点调用成功（模型 ${MODEL_ID}），输出长度 ${#OUT7}"
  else
    sk "LLM 节点未执行成功（模型 ${MODEL_ID} 可能缺少 API Key 或额度），status=${ST7}"
  fi
fi

# ---------- T8: 知识库检索节点（可选） ----------
echo ""
echo "[T8] 知识库检索节点（可选）"
KB_ID=$(psqlq "SELECT knowledge_base_id FROM wgh_knowledge_file WHERE deleted=0 AND status='SUCCESS' ORDER BY id LIMIT 1")
if [ -z "$KB_ID" ]; then
  sk "没有可用的知识库文件，跳过"
else
  cat > /tmp/wf_rag.json <<JSON
{
  "nodes": [
    {"id": "start", "type": "START", "name": "开始", "position": {"x": 0, "y": 0}, "config": {}, "onError": "STOP"},
    {"id": "rag", "type": "RETRIEVAL", "name": "知识库检索", "position": {"x": 200, "y": 0},
     "config": {"kbIds": [$KB_ID], "query": "{{input}}", "topK": 3}, "onError": "STOP"},
    {"id": "end", "type": "END", "name": "结束", "position": {"x": 400, "y": 0},
     "config": {"output": "命中 {{rag.count}} 条"}, "onError": "STOP"}
  ],
  "edges": [
    {"id": "e1", "source": "start", "target": "rag", "sourceHandle": "default"},
    {"id": "e2", "source": "rag", "target": "end", "sourceHandle": "default"}
  ]
}
JSON
  WF8=$(create_wf /tmp/wf_rag.json "e2e-检索节点")
  run_wf "$WF8" "业务规则" /tmp/wf_t8.sse 90
  RUN8=$(psqlq "SELECT max(id) FROM wgh_workflow_run WHERE workflow_id=$WF8")
  ST8=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$RUN8")
  OUT8=$(sse_field /tmp/wf_t8.sse workflow_complete output | tail -1)
  if [ "$ST8" = "COMPLETED" ]; then
    ok "检索节点调用成功（知识库 ${KB_ID}）：${OUT8}"
    SRC8=$(psqlq "SELECT coalesce(jsonb_array_length(output_json->'sources'),0) FROM wgh_workflow_node_run WHERE run_id=$RUN8 AND node_id='rag'")
    [ "$SRC8" -gt 0 ] 2>/dev/null && ok "命中来源 ${SRC8} 条" || sk "本次查询未命中任何片段（可能知识库无相关内容）"
  else
    sk "检索节点未执行成功（知识库 ${KB_ID}，embedding 模型可能不可用），status=${ST8}"
  fi
fi

# ---------- T9: 版本管理（快照 / 列表 / 回滚） ----------
echo ""
echo "[T9] 版本管理：快照 / 列表 / 回滚"

VWID=$(create_wf /tmp/wf_valid.json "e2e-版本流")
if [ -z "$VWID" ]; then
  ng "创建工作流失败"
else
  ok "创建工作流 id=$VWID"

  CNT=$(psqlq "SELECT COUNT(*) FROM wgh_workflow_version WHERE workflow_id=$VWID AND deleted=0")
  [ "$CNT" = "1" ] && ok "创建即生成 v1 快照" || ng "v1 快照数异常：$CNT"

  update_wf "$VWID" /tmp/wf_valid.json > /dev/null
  update_wf "$VWID" /tmp/wf_valid.json > /dev/null
  VER=$(curl -s "$SRV/api/workflows/$VWID" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['version'])" 2>/dev/null)
  [ "$VER" = "3" ] && ok "两次更新后版本号=3" || ng "版本号异常：$VER"

  CNT=$(psqlq "SELECT COUNT(*) FROM wgh_workflow_version WHERE workflow_id=$VWID AND deleted=0")
  [ "$CNT" = "3" ] && ok "累计 3 条版本快照" || ng "快照数异常：$CNT"

  LIST=$(curl -s "$SRV/api/workflows/$VWID/versions" -H "$AUTH" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(len(d),d[0]['version'])" 2>/dev/null)
  [ "$LIST" = "3 3" ] && ok "版本列表接口：3 条且按版本倒序" || ng "版本列表异常：$LIST"

  D1=$(curl -s "$SRV/api/workflows/$VWID/versions/1" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['name'])" 2>/dev/null)
  [ "$D1" = "e2e-版本流" ] && ok "v1 快照详情保留原始名称" || ng "v1 详情异常：$D1"

  RB=$(curl -s -X POST "$SRV/api/workflows/$VWID/rollback/1" -H "$AUTH" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print(d['version'],d['name'])" 2>/dev/null)
  [ "$RB" = "4 e2e-版本流" ] && ok "回滚到 v1：生成 v4 且名称恢复" || ng "回滚异常：$RB"

  CNT=$(psqlq "SELECT COUNT(*) FROM wgh_workflow_version WHERE workflow_id=$VWID AND deleted=0")
  [ "$CNT" = "4" ] && ok "回滚后累计 4 条快照（只增不改）" || ng "回滚后快照数异常：$CNT"

  run_wf "$VWID" "回滚验证" /tmp/wf_t9.sse 30
  OUT9=$(sse_field /tmp/wf_t9.sse workflow_complete output | tail -1)
  [ "$OUT9" = "回滚验证" ] && ok "回滚后的工作流可正常运行：$OUT9" || ng "回滚后运行异常：$OUT9"
fi

# ---------- T10: 定时 / Webhook 触发 ----------
echo ""
echo "[T10] 定时 / Webhook 触发"

TWID=$(create_wf /tmp/wf_valid.json "e2e-触发流")
if [ -z "$TWID" ]; then
  ng "创建工作流失败"
else
  ok "创建工作流 id=$TWID"

  BAD=$(curl -s -X POST "$SRV/api/workflows/$TWID/triggers" -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"triggerType":"CRON","cronExpression":"not-a-cron"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['code'])" 2>/dev/null)
  [ "$BAD" != "200" ] && ok "非法 cron 表达式被拒绝 code=$BAD" || ng "非法 cron 未被拒绝"

  TID=$(curl -s -X POST "$SRV/api/workflows/$TWID/triggers" -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"triggerType":"CRON","cronExpression":"*/2 * * * * *","inputText":"定时触发"}' \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
  [ -n "$TID" ] && ok "创建 CRON 触发器 id=${TID}（每 2 秒）" || ng "创建 CRON 触发器失败"

  sleep 7
  FC=$(psqlq "SELECT fire_count FROM wgh_workflow_trigger WHERE id=$TID")
  [ "${FC:-0}" -ge 2 ] && ok "CRON 已自动触发 $FC 次" || ng "CRON 触发次数异常：$FC"
  RUNS=$(psqlq "SELECT COUNT(*) FROM wgh_workflow_run WHERE workflow_id=$TWID AND input_text='定时触发'")
  [ "${RUNS:-0}" -ge 2 ] && ok "生成 $RUNS 条触发运行记录" || ng "触发运行记录异常：$RUNS"

  curl -s -X POST "$SRV/api/workflows/triggers/$TID/disable" -H "$AUTH" > /dev/null
  sleep 3
  BEFORE=$(psqlq "SELECT fire_count FROM wgh_workflow_trigger WHERE id=$TID")
  sleep 3
  AFTER=$(psqlq "SELECT fire_count FROM wgh_workflow_trigger WHERE id=$TID")
  [ "$BEFORE" = "$AFTER" ] && ok "停用后触发停止（$BEFORE 次保持不变）" || ng "停用后仍触发：$BEFORE → $AFTER"

  WTOKEN=$(curl -s -X POST "$SRV/api/workflows/$TWID/triggers" -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"triggerType":"WEBHOOK","inputText":"webhook默认输入"}' \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['webhookToken'])" 2>/dev/null)
  [ -n "$WTOKEN" ] && ok "创建 Webhook 触发器 token=${WTOKEN:0:8}..." || ng "创建 Webhook 触发器失败"

  WRES=$(curl -s -X POST "$SRV/api/workflows/webhook/$WTOKEN" -H 'Content-Type: application/json' -d '{"input":"webhook覆盖输入"}')
  WRID=$(echo "$WRES" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['runId'])" 2>/dev/null)
  [ -n "$WRID" ] && ok "Webhook 免登录触发成功 runId=$WRID" || ng "Webhook 触发失败：$WRES"
  sleep 2
  WST=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$WRID")
  WIN=$(psqlq "SELECT input_text FROM wgh_workflow_run WHERE id=$WRID")
  [ "$WST" = "COMPLETED" ] && [ "$WIN" = "webhook覆盖输入" ] \
    && ok "Webhook 运行完成且输入被覆盖（${WIN}）" || ng "Webhook 运行异常：status=$WST input=$WIN"

  NOTFOUND=$(curl -s -X POST "$SRV/api/workflows/webhook/no-such-token" -H 'Content-Type: application/json' -d '{}' \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['code'])" 2>/dev/null)
  [ "$NOTFOUND" != "200" ] && ok "无效 token 被拒绝 code=$NOTFOUND" || ng "无效 token 未被拒绝"

  DEL=$(curl -s -X DELETE "$SRV/api/workflows/triggers/$TID" -H "$AUTH" | python3 -c "import sys,json;print(json.load(sys.stdin)['code'])" 2>/dev/null)
  CNT=$(psqlq "SELECT COUNT(*) FROM wgh_workflow_trigger WHERE id=$TID AND deleted=0")
  [ "$DEL" = "200" ] && [ "$CNT" = "0" ] && ok "删除触发器成功（逻辑删除）" || ng "删除触发器异常：code=$DEL cnt=$CNT"
fi

# ---------- T11: 模板市场 ----------
echo ""
echo "[T11] 模板市场：内置模板列表 / 实例化 / 端到端运行"

TPL_LIST=$(curl -s "$SRV/api/workflows/templates" -H "$AUTH" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
ids=sorted(t['templateId'] for t in d)
print(len(d), ','.join(ids))" 2>/dev/null)
[ "$TPL_LIST" = "3 approval,inspection,kb-qa" ] && ok "模板列表返回 3 个内置模板：$TPL_LIST" || ng "模板列表异常：$TPL_LIST"

BAD_TPL=$(curl -s -X POST "$SRV/api/workflows/templates/no-such-template/instantiate" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"name":"x"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['code'])" 2>/dev/null)
[ "$BAD_TPL" != "200" ] && ok "无效模板标识被拒绝 code=${BAD_TPL}" || ng "无效模板未被拒绝"

AWID=$(curl -s -X POST "$SRV/api/workflows/templates/approval/instantiate" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"name":"e2e-模板审批流"}' \
  | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('id',''))" 2>/dev/null)
if [ -z "$AWID" ]; then
  ng "实例化审批模板失败"
else
  ok "实例化审批模板 id=${AWID}"

  AVER=$(curl -s "$SRV/api/workflows/$AWID" -H "$AUTH" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(d['version'], d['name'], d['description'].startswith('【模板：'))" 2>/dev/null)
  [ "$AVER" = "1 e2e-模板审批流 True" ] && ok "实例化走标准创建链路（v1 + 自定义名称 + 模板描述前缀）" || ng "实例化结果异常：$AVER"

  AVALID=$(curl -s -X POST "$SRV/api/workflows/$AWID/validate" -H "$AUTH" \
    | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'] or []))" 2>/dev/null)
  [ "$AVALID" = "0" ] && ok "审批模板图校验 0 错误" || ng "审批模板校验异常：$AVALID"

  VCNT=$(psqlq "SELECT COUNT(*) FROM wgh_workflow_version WHERE workflow_id=$AWID AND deleted=0")
  [ "$VCNT" = "1" ] && ok "实例化生成 v1 版本快照" || ng "版本快照异常：$VCNT"

  run_wf "$AWID" "采购三台服务器" /tmp/wf_t11a.sse 60
  [ "$(sse_count /tmp/wf_t11a.sse node_hitl_pending)" = "1" ] && ok "审批流运行到 HITL 暂停" || ng "审批流未触发 HITL"
  ARUN=$(psqlq "SELECT max(id) FROM wgh_workflow_run WHERE workflow_id=$AWID")
  AST=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=$ARUN")
  [ "$AST" = "PAUSED" ] && ok "审批流状态 PAUSED" || ng "审批流状态=${AST}"

  resume_wf "$ARUN" "同意" /tmp/wf_t11b.sse 60
  AOUT=$(sse_field /tmp/wf_t11b.sse workflow_complete output | tail -1)
  echo "$AOUT" | grep -q "已批准" && ok "确认同意后输出：$AOUT" || ng "审批通过输出异常：$AOUT"

  run_wf "$AWID" "采购三台服务器" /tmp/wf_t11c.sse 60
  ARUN2=$(psqlq "SELECT max(id) FROM wgh_workflow_run WHERE workflow_id=$AWID")
  resume_wf "$ARUN2" "驳回，预算不足" /tmp/wf_t11d.sse 60
  AOUT2=$(sse_field /tmp/wf_t11d.sse workflow_complete output | tail -1)
  echo "$AOUT2" | grep -q "已驳回" && ok "确认驳回后输出：$AOUT2" || ng "审批驳回输出异常：$AOUT2"
fi

IWID=$(curl -s -X POST "$SRV/api/workflows/templates/inspection/instantiate" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{}' \
  | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('id',''))" 2>/dev/null)
if [ -z "$IWID" ]; then
  ng "实例化巡检模板失败"
else
  ok "实例化巡检模板 id=${IWID}（默认名称）"
  run_wf "$IWID" "" /tmp/wf_t11e.sse 60
  IST=$(psqlq "SELECT status FROM wgh_workflow_run WHERE id=(SELECT max(id) FROM wgh_workflow_run WHERE workflow_id=$IWID)")
  IOUT=$(sse_field /tmp/wf_t11e.sse workflow_complete output | tail -1)
  [ "$IST" = "COMPLETED" ] && echo "$IOUT" | grep -q "巡检完成，共 3 项" \
    && ok "巡检模板开箱运行完成：$IOUT" || ng "巡检模板运行异常：status=${IST} output=${IOUT}"
fi

QWID=$(curl -s -X POST "$SRV/api/workflows/templates/kb-qa/instantiate" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"name":"e2e-模板问答"}' \
  | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('id',''))" 2>/dev/null)
if [ -z "$QWID" ]; then
  MODEL_ID=$(psqlq "SELECT id FROM wgh_llm_model WHERE model_type='CHAT' AND status=1 AND deleted=0 ORDER BY id LIMIT 1")
  [ -z "$MODEL_ID" ] && sk "无启用聊天模型，kb-qa 模板实例化按预期失败（校验拦截）" || ng "kb-qa 模板实例化失败"
else
  ok "实例化问答模板 id=${QWID}"
  QMODEL=$(curl -s "$SRV/api/workflows/$QWID" -H "$AUTH" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
g=json.loads(d['graphJson'])
llm=[n for n in g['nodes'] if n['type']=='LLM'][0]
print(llm['config'].get('modelId'))" 2>/dev/null)
  [ -n "$QMODEL" ] && [ "$QMODEL" != "None" ] && ok "LLM 占位模型已自动填充 modelId=${QMODEL}" || ng "LLM 模型占位符未填充：$QMODEL"
  QVALID=$(curl -s -X POST "$SRV/api/workflows/$QWID/validate" -H "$AUTH" \
    | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'] or []))" 2>/dev/null)
  [ "$QVALID" = "0" ] && ok "问答模板图校验 0 错误" || ng "问答模板校验异常：$QVALID"
fi

# ---------- 汇总 ----------
echo ""
echo "=============================================="
echo " 结果：通过 $PASS / 失败 $FAIL / 跳过 $SKIP"
echo "=============================================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
