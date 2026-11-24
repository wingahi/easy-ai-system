#!/usr/bin/env bash
# 代码沙箱端到端测试（批次 4）
#
# 通过既有「工作流 TOOL 节点 → 工具执行器」链路调用沙箱（不经 LLM 决策，结果确定）：
#   SB1 默认关闭：未开启时任何执行请求都被拒绝，返回「代码沙箱未启用」
#   SB2 开启后超时：sleep 超过 timeoutMs → timedOut=true，进程被强杀
#   SB3 开启后输出截断：输出超过 maxOutputBytes → truncated=true 且有输出上限
#   SB4 语言白名单：未允许的语言被拒绝（默认仅 PYTHON / JAVASCRIPT）
#
# 依赖：
#   - backend 运行中；真实 PostgreSQL（写入 wgh_tool / 工作流与节点运行记录）
#   - 沙箱总开关状态需与 SANDBOX_EXPECT_ENABLED 一致：
#       * false（默认）：后端未设置 WGH_SANDBOX_ENABLED / wgh.sandbox.enabled=false
#       * true：后端以 enabled=true 启动，并具备可用隔离（macOS sandbox-exec / Linux bwrap）
#   - PYTHON 语言需要运行后端的机器上有 python3（JAVASCRIPT 需要 node）
#
# 用法：
#   bash scripts/e2e-sandbox.sh                       # 验证默认关闭
#   SANDBOX_EXPECT_ENABLED=true bash scripts/e2e-sandbox.sh   # 验证开启后的超时/截断
#
# 环境变量：
#   SRV / DB / DB_USER / DB_HOST / DB_PORT / PGPASSWORD / PG_CONTAINER
#   ADMIN_USER / ADMIN_PASSWORD
#   SANDBOX_EXPECT_ENABLED  期望的沙箱开关状态，默认 false
#   E2E_TIMEOUT             单步等待上限秒数，默认 120
set -uo pipefail

SRV=${SRV:-http://localhost:8080}
MGMT_SRV=${MGMT_SRV:-http://localhost:9090}
HEALTH_URL=${HEALTH_URL:-$MGMT_SRV/actuator/health}
DB=${DB:-wgh_rag_test}
DB_TIMEZONE=${DB_TIMEZONE:-Asia/Shanghai}
DB_USER=${DB_USER:-postgres}
DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-5432}
PGPASSWORD=${PGPASSWORD:-}
PG_CONTAINER=${PG_CONTAINER:-${PG:-wgh-rag-postgres}}
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-admin}
SANDBOX_EXPECT_ENABLED=${SANDBOX_EXPECT_ENABLED:-false}
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
    docker exec -e PGTZ="$DB_TIMEZONE" -i "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB" -t -A -v ON_ERROR_STOP=1 -c "$1"
  else
    PGTZ="$DB_TIMEZONE" PGPASSWORD="$PGPASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB" \
      -t -A -v ON_ERROR_STOP=1 -c "$1"
  fi
}

echo "=============================================="
echo " 代码沙箱端到端测试（经工作流 TOOL 节点）"
echo "=============================================="
echo "  DB: $PSQL_MODE / $DB     SRV: $SRV     期望启用=$SANDBOX_EXPECT_ENABLED"

# ---------- 0. 前置 ----------
echo ""
echo "[SB0] 前置检查"
if ! curl -sf "$HEALTH_URL" | grep -q '"status":"UP"'; then
  ng "后端未就绪：$HEALTH_URL 非 UP"
  exit 1
fi
ok "后端健康检查 UP"
if ! psqlq "SELECT 1" >/dev/null 2>&1; then
  ng "数据库不可用（mode=$PSQL_MODE db=${DB}）"
  exit 1
fi
for t in wgh_tool wgh_workflow wgh_workflow_node_run; do
  if ! psqlq "SELECT to_regclass('public.$t') IS NOT NULL" | grep -q t; then
    ng "缺少表 ${t}：请先初始化 schema"
    exit 1
  fi
done
ok "工具 / 工作流 / 节点运行表存在"
if [ "$SANDBOX_EXPECT_ENABLED" = "true" ] && ! command -v python3 >/dev/null 2>&1; then
  ng "本机缺少 python3：无法执行 PYTHON 沙箱用例（若后端在容器中，请在容器侧确认解释器）"
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
TOOL_NAME="e2e_sandbox_$RANDOM"
TOOL_ID=$(psqlq "SELECT id FROM wgh_tool WHERE name='$TOOL_NAME' AND deleted=0 LIMIT 1")
if [ -z "$TOOL_ID" ]; then
  TOOL_ID=$(psqlq "INSERT INTO wgh_tool
                     (name, tool_type, description, definition, executor_config, status, is_example, visibility)
                   VALUES
                     ('$TOOL_NAME','SANDBOX','e2e 沙箱工具',
                      '{\"type\":\"function\",\"function\":{\"name\":\"$TOOL_NAME\",\"description\":\"执行一段代码\",\"parameters\":{\"type\":\"object\",\"properties\":{\"language\":{\"type\":\"string\"},\"code\":{\"type\":\"string\"},\"timeoutMs\":{\"type\":\"integer\"}},\"required\":[\"code\"]}}}',
                      '{}', 1, 0, 'PRIVATE')
                   RETURNING id")
fi
if [ -z "${TOOL_ID:-}" ]; then
  ng "注册 SANDBOX 工具失败"
  exit 1
fi
ok "SANDBOX 工具已注册 toolId=$TOOL_ID"

# 每次用例创建一个工作流：START → TOOL → END，END 输出工具返回文本
run_sandbox() { # code timeout_ms -> 节点输出文本（失败时输出 Error: ...）
  local code="$1" timeout_ms="$2" wf_payload wf run_id sse node_text deadline
  wf_payload=$(python3 - "$TOOL_ID" "$code" "$timeout_ms" "$RUN" <<'PY'
import json,sys
tool_id=int(sys.argv[1]); code=sys.argv[2]; timeout=int(sys.argv[3]); run=sys.argv[4]
graph={
  "nodes":[
    {"id":"start","type":"START","name":"开始","position":{"x":0,"y":0},"config":{},"onError":"STOP"},
    {"id":"tool","type":"TOOL","name":"沙箱","position":{"x":200,"y":0},
     "config":{"toolId":tool_id,"arguments":{"language":"PYTHON","code":code,"timeoutMs":timeout}},
     "onError":"STOP"},
    {"id":"end","type":"END","name":"结束","position":{"x":400,"y":0},
     "config":{"output":"{{tool.text}}"},"onError":"STOP"}
  ],
  "edges":[
    {"id":"e1","source":"start","target":"tool","sourceHandle":"default"},
    {"id":"e2","source":"tool","target":"end","sourceHandle":"default"}
  ]
}
print(json.dumps({"name":"e2e-sandbox-"+run,"description":"e2e","graphJson":json.dumps(graph,ensure_ascii=False)},ensure_ascii=False))
PY
)
  wf=$(curl -s -X POST "$SRV/api/workflows" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "$wf_payload" | python3 -c "import sys,json;print((json.load(sys.stdin).get('data') or {}).get('id',''))" 2>/dev/null)
  if [ -z "${wf:-}" ]; then
    echo "__WF_CREATE_FAILED__"
    return
  fi
  curl -s -N -X POST "$SRV/api/workflows/$wf/run" -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"input":"sandbox"}' --max-time 90 > "/tmp/e2e_sandbox_$RUN.sse" 2>&1
  deadline=$(( $(date +%s) + E2E_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    run_id=$(psqlq "SELECT max(id) FROM wgh_workflow_run WHERE workflow_id=$wf")
    node_text=$(psqlq "SELECT coalesce(output_json->>'text','') FROM wgh_workflow_node_run WHERE run_id=$run_id AND node_id='tool' ORDER BY id DESC LIMIT 1")
    [ -n "$node_text" ] && break
    sleep 2
  done
  echo "$node_text"
}

# ---------- SB1: 默认关闭 ----------
echo ""
echo "[SB1] 沙箱关闭时的执行拒绝"
OUT0=$(run_sandbox "print('should-not-run')" 3000)
if [ "$OUT0" = "__WF_CREATE_FAILED__" ]; then
  ng "工作流创建失败（检查 /tmp/e2e_sandbox_$RUN.sse）"
elif [ -z "$OUT0" ]; then
  ng "未取得工具节点输出（工作流未执行或节点未落库）"
else
  if [ "$SANDBOX_EXPECT_ENABLED" = "false" ]; then
    echo "$OUT0" | grep -q "代码沙箱未启用" \
      && ok "默认关闭时拒绝执行：$OUT0" \
      || ng "默认关闭仍未拒绝，工具返回：${OUT0:0:160}"
  else
    echo "$OUT0" | grep -q "should-not-run" \
      && ok "沙箱已开启，代码正常执行" \
      || ng "沙箱已开启但执行异常：${OUT0:0:160}"
  fi
fi

# ---------- SB2 / SB3 / SB4: 仅开启模式下验证 ----------
if [ "$SANDBOX_EXPECT_ENABLED" != "true" ]; then
  echo ""
  echo "[SB2-SB4] 沙箱开启相关用例未执行"
  ng "超时 / 输出截断 / 语言白名单需要以 WGH_SANDBOX_ENABLED=true 启动后端（并具备可用隔离），"
  echo "      请按 docs/testing/test-cases.md §46.2 准备后以 SANDBOX_EXPECT_ENABLED=true 重跑（禁止静默跳过）。"
else
  echo ""
  echo "[SB2] 超时：sleep 10s，timeoutMs=1000"
  echo "      说明：tools 的 timeoutMs 会被裁剪到 wgh.sandbox.max-timeout-ms（默认 30000）"
  OUT2=$(run_sandbox "import time; time.sleep(10); print('done')" 1000)
  echo "$OUT2" | grep -q "timedOut=true" && ok "返回 timedOut=true" || ng "超时未生效：${OUT2:0:200}"
  echo "$OUT2" | grep -q "^exitCode=" && ok "返回结构化执行结果" || ng "结果格式异常：${OUT2:0:200}"

  echo ""
  echo "[SB3] 输出截断：打印 200000 字符"
  OUT3=$(run_sandbox "print('x'*200000)" 5000)
  echo "$OUT3" | grep -q "truncated=true" && ok "返回 truncated=true" || ng "输出未截断：${OUT3:0:200}"
  LEN3=$(printf '%s' "$OUT3" | wc -c | tr -d ' ')
  [ "${LEN3:-0}" -lt 200000 ] && ok "返回体长度 ${LEN3} < 200000（已被截断）" || ng "返回体长度=${LEN3}"

  echo ""
  echo "[SB4] 语言白名单：RUST 未在 allowed-languages 中"
  OUT4=$(run_sandbox "fn main(){}" 3000)
  echo "$OUT4" | grep -q "不支持的语言" && ok "未允许语言被拒绝：${OUT4:0:120}" || ng "未允许语言未被拒绝：${OUT4:0:160}"
fi

# ---------- 汇总 ----------
echo ""
echo "=============================================="
echo " 结果：通过 $PASS / 失败 $FAIL"
echo "=============================================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
