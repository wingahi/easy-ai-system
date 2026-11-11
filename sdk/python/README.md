# wgh-rag Python SDK（OpenAI 兼容）

让外部 Python 系统直接用 OpenAI 协议调用 wgh-rag 知识库系统。

## 1. 准备 API Key

登录管理端 → 「开放 API」创建 API Key（形如 `wgh_sk_...`），按需授予 scope：

| 接口 | 需要 scope |
| --- | --- |
| `POST /v1/chat/completions` | `chat:write` |
| `GET /v1/models` | `model:read` |

鉴权头：`Authorization: Bearer <API Key>`（也兼容 `X-API-Key`）。

## 2. 使用本 SDK（仅标准库，无三方依赖）

```python
import sys
sys.path.insert(0, "sdk/python")

from wgh_rag_client import WghRagClient, WghRagError

client = WghRagClient(api_key="wgh_sk_xxx", base_url="http://localhost:8080/v1")

# 模型列表：每个模型 = 一个已启用的聊天窗口
print(client.models.list())

# 非流式
resp = client.chat.create(
    model="wgh-window-1",
    messages=[{"role": "user", "content": "介绍一下这个知识库"}],
)
print(resp["choices"][0]["message"]["content"])
session_id = resp["wgh_session_id"]  # 多轮时回传

# 流式（逐 token）
for chunk in client.chat.create(
    model="wgh-window-1",
    messages=[{"role": "user", "content": "继续展开"}],
    stream=True,
    session_id=session_id,
):
    delta = chunk["choices"][0]["delta"].get("content")
    if delta:
        print(delta, end="", flush=True)

# 错误：WghRagError 带 status / type / code
try:
    client.chat.create(model="not-exist", messages=[{"role": "user", "content": "hi"}])
except WghRagError as e:
    print(e.status, e.type, e.code, e.message)  # 404 invalid_request_error model_not_found
```

## 3. 使用官方 openai SDK

兼容入口遵循 OpenAI Chat Completions 协议，可直接把 `base_url` 指向本系统：

```python
from openai import OpenAI

client = OpenAI(api_key="wgh_sk_xxx", base_url="http://localhost:8080/v1")

resp = client.chat.completions.create(
    model="wgh-window-1",
    messages=[{"role": "user", "content": "你好"}],
)
print(resp.choices[0].message.content)

stream = client.chat.completions.create(
    model="wgh-window-1",
    messages=[{"role": "user", "content": "你好"}],
    stream=True,
)
for chunk in stream:
    text = chunk.choices[0].delta.content if chunk.choices else None
    if text:
        print(text, end="", flush=True)
```

## 4. 模型名映射规则

一个「模型」对应一个**已启用的聊天窗口**（`wgh_chat_window`），
因为温度、知识库、工具、Agent/工作流等 RAG 参数都挂在窗口上。

请求里的 `model` 支持以下写法（按顺序匹配）：

1. `wgh-window-<windowId>`：`GET /v1/models` 返回的 id
2. 纯数字 `<windowId>`：窗口主键
3. 窗口名称精确匹配（仅启用状态）

不存在返回 `404` + `error.code = model_not_found`；窗口停用返回 `400` + `invalid_request`。
不支持直接传底层 LLM 模型名（如 `qwen-plus`）。

## 5. 多轮会话

OpenAI 客户端每次会重发完整 `messages`，而本系统的上下文由服务端会话维护。

- 首次请求不传 `session_id`，响应中会带 `wgh_session_id`
- 后续请求传入该 `session_id`（也接受 `sessionId`）即可复用上下文
- 服务端不会重放 `messages` 中的历史，避免重复拼接

## 6. 当前不支持的能力

- `n > 1`、`logprobs`、`function_call` / `tools` 透传（工具由聊天窗口配置决定）
- `temperature` / `top_p` / `max_tokens` 等采样参数不会覆盖窗口配置
- `usage` 为本地 token 估算值（RAG 事件流不返回真实 token 计数）
- 客户端主动断开后，服务端生成不会立即取消（会自然结束）

## 7. 错误结构

所有错误均为 OpenAI 结构，且 HTTP 状态码正确：

```json
{
  "error": {
    "message": "模型不存在: xxx",
    "type": "invalid_request_error",
    "param": null,
    "code": "model_not_found"
  }
}
```

| HTTP | type | 典型 code |
| --- | --- | --- |
| 400 | `invalid_request_error` | `invalid_request` |
| 401 | `authentication_error` | `invalid_api_key` |
| 403 | `permission_error` | `insufficient_permissions` |
| 404 | `invalid_request_error` | `model_not_found` |
| 429 | `rate_limit_error` | `insufficient_quota` |
| 500 | `api_error` | `internal_error` |
| 502/504 | `api_error` | `upstream_error` / `timeout` |
