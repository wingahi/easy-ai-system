#!/usr/bin/env bash
# 压力测试：登录 / 只读列表 / RAG 检索 / SSE 聊天 / 工作流执行
# 工具：ab（REST 吞吐）+ curl 并发（SSE 流式）
# 依赖：backend 8080 运行中；KB id=1；聊天窗口 id=7（纯聊天）；工作流 id=1（无 LLM 线性最小流）
# 用法：bash scripts/test-stress.sh
set -uo pipefail

SRV=${SRV:-http://localhost:8080}

PASS=0; FAIL=0
ok() { echo "  ✅ $1"; PASS=$((PASS+1)); }
ng() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }

# ab 结果摘要：rps / 平均延迟 / 失败数 / 非2xx数
ab_report() { # label ab_output_file
  local rps mean failed non2xx
  rps=$(grep "Requests per second" "$2" | awk '{print $4}')
  mean=$(grep "Time per request" "$2" | head -1 | awk '{print $4}')
  failed=$(grep "Failed requests" "$2" | awk '{print $3}')
  non2xx=$(grep "Non-2xx responses" "$2" | awk '{print $3}')
  echo "  $1: RPS=${rps:-N/A} 均值=${mean:-N/A}ms 失败=${failed:-N/A} 非2xx=${non2xx:-0}"
  [ "${failed:-1}" = "0" ] && [ "${non2xx:-0}" = "0" ] \
    && ok "$1 无失败请求" || ng "$1 存在失败（failed=${failed:-0} non2xx=${non2xx:-0}）"
}

echo "== 压力测试 =="

# 0. 登录取 JWT
TOKEN=$(curl -s -X POST $SRV/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
[ -n "$TOKEN" ] && ok "登录成功" || { echo "登录失败，退出"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

TMPD=$(mktemp -d)
echo '{"username":"admin","password":"admin"}' > "$TMPD/login.json"
echo '{"message":"用一句话介绍你自己"}' > "$TMPD/chat.json"
echo '{"input":"压测"}' > "$TMPD/wf.json"

# 1. 登录接口：200 请求 / 20 并发
echo "[S1] 登录 ab -n 200 -c 20"
ab -n 200 -c 20 -p "$TMPD/login.json" -T application/json \
  $SRV/api/auth/login > "$TMPD/s1.log" 2>&1
ab_report "S1 登录" "$TMPD/s1.log"

# 2. 模型列表（纯读）：500 / 50
echo "[S2] 模型列表 ab -n 500 -c 50"
ab -n 500 -c 50 -H "$AUTH" $SRV/api/models/chat/list > "$TMPD/s2.log" 2>&1
ab_report "S2 模型列表" "$TMPD/s2.log"

# 3. 聊天窗口分页（纯读）：300 / 30
echo "[S3] 聊天窗口分页 ab -n 300 -c 30"
ab -n 300 -c 30 -H "$AUTH" "$SRV/api/chat/windows?page=1&size=10" > "$TMPD/s3.log" 2>&1
ab_report "S3 聊天窗口分页" "$TMPD/s3.log"

# 4. 工作流列表（纯读）：300 / 30
echo "[S4] 工作流列表 ab -n 300 -c 30"
ab -n 300 -c 30 -H "$AUTH" $SRV/api/workflows/list > "$TMPD/s4.log" 2>&1
ab_report "S4 工作流列表" "$TMPD/s4.log"

# 5. RAG 检索（每次触发 embedding 调用；DashScope 账户有 QPS 限流，低并发小批量）：30 / 2
echo "[S5] RAG 检索 ab -n 30 -c 2"
ab -n 30 -c 2 -H "$AUTH" \
  "$SRV/api/kb/1/search?query=%E7%AE%80%E5%8E%86" > "$TMPD/s5.log" 2>&1
ab_report "S5 RAG 检索" "$TMPD/s5.log"

# 6. SSE 聊天 5 并发（每次一次 LLM 调用）：成功率 + 首字节时间
echo "[S6] SSE 聊天 5 并发"
T0=$(python3 -c "import time; print(time.time())")
for i in 1 2 3 4 5; do
  curl -s -N --max-time 90 -o "$TMPD/sse_$i.log" \
    -w "%{http_code} %{time_starttransfer}\n" \
    -X POST $SRV/api/chat/windows/7/stream \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d @"$TMPD/chat.json" > "$TMPD/sse_meta_$i" 2>&1 &
done
wait
T1=$(python3 -c "import time; print(time.time())")
SSE_OK=0
for i in 1 2 3 4 5; do
  CODE=$(awk '{print $1}' "$TMPD/sse_meta_$i")
  TTFB=$(awk '{print $2}' "$TMPD/sse_meta_$i")
  if [ "$CODE" = "200" ] && grep -q "data:" "$TMPD/sse_$i.log"; then
    SSE_OK=$((SSE_OK+1))
    echo "  流 $i: TTFB=${TTFB}s"
  else
    echo "  流 $i: 异常 http=$CODE"
  fi
done
[ "$SSE_OK" -eq 5 ] && ok "SSE 聊天 5 并发全部成功（总耗时 $(python3 -c "print(f'{$T1-$T0:.1f}')")s）" \
  || ng "SSE 聊天并发成功数 $SSE_OK/5"

# 7. 工作流执行 10 并发（线性最小流，无 LLM 节点）
echo "[S7] 工作流执行 10 并发"
for i in $(seq 1 10); do
  curl -s -N --max-time 60 -o "$TMPD/wf_$i.log" \
    -w "%{http_code}\n" \
    -X POST $SRV/api/workflows/1/run \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d @"$TMPD/wf.json" > "$TMPD/wf_meta_$i" 2>&1 &
done
wait
WF_OK=0
for i in $(seq 1 10); do
  CODE=$(cat "$TMPD/wf_meta_$i" | awk '{print $1}')
  if [ "$CODE" = "200" ] && grep -q "workflow_complete\|data:" "$TMPD/wf_$i.log"; then
    WF_OK=$((WF_OK+1))
  fi
done
[ "$WF_OK" -eq 10 ] && ok "工作流 10 并发全部完成" || ng "工作流并发完成数 $WF_OK/10"

rm -rf "$TMPD"
echo "=============================================="
echo " 结果: PASS=$PASS FAIL=$FAIL"
echo "=============================================="
