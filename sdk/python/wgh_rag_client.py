"""wgh-rag OpenAI 兼容最小客户端（仅依赖 Python 标准库）。

用法与 openai-python 保持一致：

    from wgh_rag_client import WghRagClient

    client = WghRagClient(api_key="wgh_sk_xxx", base_url="http://localhost:8080/v1")
    print(client.models.list())

    resp = client.chat.create(
        model="wgh-window-1",
        messages=[{"role": "user", "content": "你好"}],
    )
    print(resp["choices"][0]["message"]["content"])

    for chunk in client.chat.create(
        model="wgh-window-1",
        messages=[{"role": "user", "content": "你好"}],
        stream=True,
    ):
        delta = chunk["choices"][0]["delta"].get("content")
        if delta:
            print(delta, end="", flush=True)
"""

from __future__ import annotations

import http.client
import json
import urllib.error
import urllib.request
from typing import Any, Dict, Iterator, List, Optional
from urllib.parse import urlparse


class WghRagError(Exception):
    """OpenAI 兼容错误：包含 HTTP 状态码与 error.type / error.code。"""

    def __init__(self, status: int, message: str, error_type: str = "", code: str = ""):
        super().__init__(message)
        self.status = status
        self.message = message
        self.type = error_type
        self.code = code

    def __repr__(self) -> str:
        return f"WghRagError(status={self.status}, type={self.type!r}, code={self.code!r}, message={self.message!r})"


class WghRagClient:
    """OpenAI 协议客户端。

    :param api_key: 在系统「开放 API」页面创建的 API Key（形如 wgh_sk_...）
    :param base_url: 兼容入口根地址，默认本机 /v1
    :param timeout: 单次请求/流式读取超时（秒）
    """

    def __init__(self, api_key: str, base_url: str = "http://localhost:8080/v1",
                 timeout: float = 300.0):
        if not api_key:
            raise ValueError("api_key 不能为空")
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.chat = ChatCompletions(self)
        self.models = Models(self)

    # ===== 底层请求 =====

    def _headers(self, accept: str = "application/json") -> Dict[str, str]:
        return {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
            "Accept": accept,
        }

    def _request_json(self, method: str, path: str,
                      payload: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(self.base_url + path, data=data,
                                         headers=self._headers(), method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            raise _parse_error(error.code, error.read().decode("utf-8", "replace")) from None
        except urllib.error.URLError as error:
            raise WghRagError(0, f"无法连接服务: {error.reason}", "connection_error", "unreachable") from None

    def _request_stream(self, path: str, payload: Dict[str, Any]) -> Iterator[Dict[str, Any]]:
        parsed = urlparse(self.base_url)
        connection_class = (http.client.HTTPSConnection if parsed.scheme == "https"
                            else http.client.HTTPConnection)
        connection = connection_class(parsed.netloc, timeout=self.timeout)
        target = f"{parsed.path.rstrip('/')}{path}"
        try:
            connection.request("POST", target,
                               body=json.dumps(payload).encode("utf-8"),
                               headers=self._headers(accept="text/event-stream"))
            response = connection.getresponse()
            if response.status >= 400:
                raise _parse_error(response.status, response.read().decode("utf-8", "replace"))
            for raw_line in response:
                line = raw_line.decode("utf-8").strip()
                if not line.startswith("data:"):
                    continue
                data = line[len("data:"):].strip()
                if data == "[DONE]":
                    break
                if data:
                    yield json.loads(data)
        finally:
            connection.close()


class ChatCompletions:
    def __init__(self, client: WghRagClient):
        self._client = client

    def create(self, *, model: str, messages: List[Dict[str, Any]],
               stream: bool = False, session_id: Optional[int] = None,
               **kwargs: Any) -> Any:
        """创建对话补全。

        session_id 为扩展字段：携带后复用服务端会话，实现多轮上下文；
        不传则每次新建会话（messages 中的历史不会重放）。
        其余 OpenAI 参数（temperature/top_p/max_tokens 等）会原样发送，但当前由
        聊天窗口配置决定，服务端不会覆盖窗口参数。
        """
        payload: Dict[str, Any] = {
            "model": model,
            "messages": messages,
            "stream": stream,
        }
        if session_id is not None:
            payload["session_id"] = session_id
        payload.update(kwargs)
        if stream:
            return self._client._request_stream("/chat/completions", payload)
        return self._client._request_json("POST", "/chat/completions", payload)


class Models:
    def __init__(self, client: WghRagClient):
        self._client = client

    def list(self) -> Dict[str, Any]:
        """列出可用模型（每个模型对应一个已启用的聊天窗口）。"""
        return self._client._request_json("GET", "/models")


def _parse_error(status: int, raw: str) -> WghRagError:
    try:
        body = json.loads(raw)
    except json.JSONDecodeError:
        return WghRagError(status, raw or f"HTTP {status}")
    detail = body.get("error") if isinstance(body, dict) else None
    if isinstance(detail, dict):
        return WghRagError(status, detail.get("message", ""),
                           detail.get("type", ""), detail.get("code", ""))
    message = body.get("message") if isinstance(body, dict) else raw
    return WghRagError(status, message or f"HTTP {status}")
