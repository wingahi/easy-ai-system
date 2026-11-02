# 免登录分享 / 嵌入 Widget / 开放 API 设计说明

> 特性组：阶段 H（免登录分享、嵌入 Widget、开放 API）
> 实现位置：后端 `rag-controller`（`com.wgh.rag.publicaccess`），前端 `PublicShareChat.vue` / `PublicAccess.vue` / `public/widget.js`
> 代码版本：随特性提交；DDL 见 `rag-backend/sql/schema.sql` 末尾「免登录分享 / Widget / 开放 API」段

---

## 1. 目标与边界

| 入口 | 目标 | 明确不做（简化实现） |
|------|------|--------------------|
| 免登录分享 | 生成带 token 的分享链接，支持有效期、可选访问密码、只读范围（会话/知识库/窗口问答）、可吊销，匿名访问 | 不做访问密码的失败锁定与验证码；不做分享链接的“访问白名单 IP” |
| Widget | 一行 `<script>` 在第三方页面注入 iframe 展示分享页 | 不做宿主页 ↔ iframe 的双向 `postMessage` 通信；不做跨域 SDK 事件订阅 |
| 开放 API | API Key 创建/吊销/scope 管理，独立鉴权过滤器，复用配额与限流，记录调用日志 | 不做 OAuth2 客户端凭证模式；不做 API 版本协商（固定 `/v1`）；不做计费出账 |

**共同约束**：三类入口都只能访问被显式授权（分享范围 / Key scope）的资源，不能绕过既有业务权限；所有入口复用 `RagChatService` 的配额校验，因此匿名/API 调用同样计入 `wgh_quota_usage`。

---

## 2. 表结构

### 2.1 `wgh_share_link`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL | 主键 |
| token | VARCHAR(64) UNIQUE | 分享令牌，格式 `s_<32位hex>` |
| name | VARCHAR(100) | 分享名称（展示用） |
| scope | VARCHAR(20) | `WINDOW` 匿名问答 / `SESSION` 只读会话记录 / `KB` 只读知识库检索 |
| target_id | BIGINT | WINDOW=chat_window_id；SESSION=chat_session_id；KB=knowledge_base_id |
| expires_at | TIMESTAMP | 过期时间；创建时默认 now+7d，上限 now+30d |
| password_hash | VARCHAR(128) | BCrypt 哈希；为空表示无需密码 |
| max_uses | INT | 最大使用次数，0=不限 |
| use_count | INT | 已使用次数（原子累加） |
| revoked | SMALLINT | 0/1 吊销标记 |
| created_by / create_time / update_time / deleted | — | BaseEntity 审计字段 |

索引：`idx_share_link_token(token)`、`idx_share_link_target(scope, target_id)`。

### 2.2 `wgh_api_key`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL | 主键 |
| key_prefix | VARCHAR(16) | 明文前 12 位，便于后台识别 |
| key_hash | VARCHAR(128) UNIQUE | 明文 `wgh_sk_<32hex>` 的 SHA-256，仅存哈希 |
| name | VARCHAR(100) | Key 名称 |
| scopes | VARCHAR(200) | 逗号分隔：`chat:write,kb:read,usage:read`；`*` 表示全部 |
| expires_at | TIMESTAMP | 可空，空=不过期 |
| revoked | SMALLINT | 0/1 |
| created_by | VARCHAR(64) | 创建人 |
| last_used_time | TIMESTAMP | 最近一次校验成功时间 |

索引：`idx_api_key_hash(key_hash)`。

### 2.3 `wgh_api_call_log`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL | 主键 |
| api_key_id / api_key_name | BIGINT / VARCHAR(100) | 调用方 Key |
| username | VARCHAR(64) | 逻辑主体，固定 `api_<keyId>` |
| method / path | VARCHAR(10) / VARCHAR(300) | 请求方法与路径 |
| status_code | INT | HTTP 状态码 |
| duration_ms | BIGINT | 耗时（毫秒） |
| ip | VARCHAR(64) | 客户端 IP（`X-Forwarded-For` 优先） |

索引：`idx_api_call_log_key(api_key_id, create_time)`。

---

## 3. 接口清单

### 3.1 管理端（需登录，`@Audit` 记录审计）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/share-links` | 分页查询分享链接（`page/size/keyword/scope`） |
| POST | `/api/admin/share-links` | 创建分享链接；body: `name/scope/targetId/password(明文)/maxUses/expiresDays` |
| POST | `/api/admin/share-links/{id}/revoke` | 吊销 |
| GET | `/api/admin/api-keys` | 分页查询 API Key（不回传 `keyHash`） |
| POST | `/api/admin/api-keys` | 创建 Key；返回 `{id,name,scopes,plainKey,expiresAt}`，**明文仅此一次** |
| POST | `/api/admin/api-keys/{id}/revoke` | 吊销 |
| GET | `/api/admin/api-keys/{id}/call-logs` | 最近调用日志（默认 20 条） |

### 3.2 免登录分享（`/api/public/share`，无需 JWT）

请求头：`X-Share-Password`（可选）、`X-Visitor-Id`（`[A-Za-z0-9_-]{6,64}`，用于匿名会话隔离）。

| 方法 | 路径 | scope | 说明 |
|------|------|-------|------|
| GET | `/api/public/share/{token}` | 任意 | 分享信息：`scope/targetId/expiresAt/passwordRequired/passwordVerified/maxUses/useCount/readOnly` + 目标元信息 |
| POST | `/api/public/share/{token}/unlock` | 任意 | 校验访问密码 |
| GET | `/api/public/share/{token}/sessions` | WINDOW | 访客会话列表（按 `share_<visitorId>` 隔离） |
| POST | `/api/public/share/{token}/sessions` | WINDOW | 新建访客会话 |
| DELETE | `/api/public/share/{token}/sessions/{sessionId}` | WINDOW | 删除访客会话（仅本人） |
| GET | `/api/public/share/{token}/messages` | SESSION / WINDOW | 只读消息记录 |
| POST | `/api/public/share/{token}/stream` | WINDOW | 匿名 SSE 问答；复用 `RagChatService.streamChat` |
| POST | `/api/public/share/{token}/kb/search` | KB | 只读检索，复用 `RagRetrieveService.searchMultiple(...).matches()` |

错误码约定：token 无效/吊销/过期 → `1003/1004/1002` 类明确错误；需要密码 → `OPERATION_NOT_ALLOWED`（刻意不用 1001/1002，避免前端误判为登录失效）；密码错误 → `PERMISSION_DENIED`；次数用尽/限流 → `QUOTA_EXCEEDED(429)`。

### 3.3 开放 API（`/api/open/v1`，`X-API-Key` 或 `Authorization: Bearer <key>`）

| 方法 | 路径 | scope | 说明 |
|------|------|-------|------|
| GET | `/api/open/v1/me` | 任意 | 当前 Key 信息（不含哈希） |
| POST | `/api/open/v1/chat/stream` | `chat:write` | SSE 问答；`windowId` 必填，`sessionId` 缺省时自动创建 `api_<keyId>` 会话 |
| GET | `/api/open/v1/kb/search` | `kb:read` | 知识库检索（`windowId` + `query` + `topK`） |
| GET | `/api/open/v1/usage` | `usage:read` | 配额当日用量 + 调用总数 + 最近 20 条调用日志 |

路径 → scope 映射由 `ApiKeyAuthenticationFilter` 统一完成：`/v1/chat/→chat:write`、`/v1/kb/→kb:read`、`/v1/usage→usage:read`。

### 3.4 Widget

| 资源 | 路径 | 说明 |
|------|------|------|
| 脚本 | `/widget.js` | 读取 `data-token/data-base/data-container/data-height`，注入 iframe `{base}/share/{token}?embed=1` |
| 演示页 | `/widget-demo.html` | 本地可打开，支持 `?token=` 动态加载，展示嵌入代码与 CSP 说明 |

嵌入示例：

```html
<div id="rag-widget"></div>
<script src="https://your-rag-host/widget.js"
        data-token="s_xxx"
        data-base="https://your-rag-host"
        data-container="#rag-widget"
        data-height="640"></script>
```

---

## 4. 配置项

| key | 默认值 | 说明 |
|-----|--------|------|
| `wgh.public-access.share-max-expire-days` | 30 | 分享链接有效期上限（天），超限拒绝创建 |
| `wgh.public-access.share-default-expire-days` | 7 | 未指定有效期时的默认天数 |
| `wgh.public-access.share-rate-limit-per-minute` | 20 | 分享入口每分钟请求上限（token + IP 维度） |
| `wgh.public-access.api-rate-limit-per-minute` | 60 | 开放 API 每分钟请求上限（API Key 维度） |
| `wgh.public-access.max-images-per-request` | 4 | 分享/开放 API 单次提问图片数量上限 |

配置类：`PublicAccessProperties`（`@ConfigurationProperties(prefix = "wgh.public-access")`）。

**跨域 / CSP 说明**：

- 分享页与 Widget 脚本由同一后端/前端源提供，iframe 依赖浏览器同源隔离实现样式隔离（iframe 内独立文档，不与宿主 CSS 冲突）。
- 后端 CORS 复用 `CorsConfigurationSource`（`PublicAccessSecurityConfig` 注入），默认放通 `allowedOriginPatterns`；生产环境请在 `wgh.cors.*` 中收敛到可信域。
- 若第三方页面设置了 `Content-Security-Policy`，需允许：`script-src <rag-host>`、`frame-src <rag-host>`。
- 分享页返回头如需禁止被任意站点嵌入，可配置 `Content-Security-Policy: frame-ancestors <允许的宿主域>`；当前为简化实现，未按域名白名单限制 frame-ancestors。

---

## 5. 关键类与调用链路

### 5.1 关键类

| 类 | 职责 |
|----|------|
| `PublicAccessSecurityConfig` | `@Order(1)` + `securityMatcher("/api/public/**","/api/open/**")` 的独立过滤链；关键：注册 `FilterRegistrationBean<JwtAuthenticationFilter>` 并 `setEnabled(false)`，关闭 JWT 过滤器的全局 Servlet 自动注册，避免匿名请求被原链 401，同时保留其在原 `SecurityConfig` 链内生效 |
| `ApiKeyAuthenticationFilter` | 仅作用于 `/api/open/**`；校验 Key → scope → 设置 `Authentication("api_<id>", ROLE_API_CLIENT)`；异常转 401/403/429 JSON；`finally` 写调用日志 |
| `PublicAccessProperties` | 配置绑定 |
| `PublicAccessRateLimiter` | 60s 滑动窗口限流（内存实现），超限抛 `QUOTA_EXCEEDED` |
| `ShareLinkService` | 创建/校验/密码/范围/次数扣减/吊销/分页 |
| `ApiKeyService` | 创建（明文返回一次 + SHA-256 存储）/校验/scope/吊销/分页/`findById` |
| `ApiCallLogService` | 调用日志落库、最近日志查询、客户端 IP 解析 |
| `PublicShareController` | 免登录分享全部端点；`asVisitor()` 临时注入匿名 `SecurityContext` |
| `ShareLinkAdminController` / `ApiKeyAdminController` | 管理端 CRUD（`@Audit`） |
| `OpenApiController` | 开放 API 端点；`currentKey()` 从 principal `api_<id>` 解析 |

### 5.2 匿名问答链路（WINDOW 分享）

```
浏览器 PublicShareChat.vue
  └─ POST /api/public/share/{token}/stream  (X-Visitor-Id / X-Share-Password)
       ├─ PublicAccessSecurityConfig 链（permitAll，无 JWT）
       ├─ PublicShareController.stream
       │    ├─ rateLimiter.acquire(token + ip, shareRateLimitPerMinute)
       │    ├─ shareLinkService.resolveActive(token)      // 无效/吊销/过期/次数
       │    ├─ shareLinkService.requirePassword(link, pwd)
       │    ├─ shareLinkService.requireScope(link, WINDOW)
       │    ├─ windowService.getById(targetId)            // 窗口存在且启用
       │    ├─ shareLinkService.consumeUse(link)          // 原子扣减，0 行 → 429
       │    ├─ windowService.createSession(windowId, "share_<visitorId>")  // 首次
       │    └─ asVisitor("share_<visitorId>"):
       │         RagChatService.streamChat(windowId, sessionId, message, true, images)
       │           └─ SecurityUtils.currentUsername() = "share_<visitorId>"
       │              └─ QuotaService.checkAndIncrementChat(...)   // 复用配额
       └─ SSE: start → content* → sources → done
```

### 5.3 开放 API 链路

```
第三方服务
  └─ POST /api/open/v1/chat/stream   (X-API-Key)
       ├─ ApiKeyAuthenticationFilter
       │    ├─ ApiKeyService.verify(raw)          // 无效/吊销/过期
       │    ├─ ApiKeyService.requireScope(key, "chat:write")
       │    ├─ SecurityContext: principal="api_<id>", ROLE_API_CLIENT
       │    └─ finally → ApiCallLogService.record(...)  // 谁/接口/状态码/耗时/IP
       ├─ OpenApiController.stream
       │    ├─ rateLimiter.acquire("api_<id>", apiRateLimitPerMinute)
       │    ├─ windowService.getById / 校验状态
       │    ├─ 缺省 sessionId → windowService.createSession(windowId, "api_<keyId>")
       │    └─ 临时 SecurityContext → RagChatService.streamChat(...)
       │         └─ QuotaService.checkAndIncrementChat("api_<keyId>")
       └─ SSE
```

### 5.4 滥用防护矩阵

| 风险 | 防护 | 落点 |
|------|------|------|
| 链接长期有效 | `expires_at` 校验 + 创建时有效期上限 | `ShareLinkService#resolveActive/#create` |
| 链接被转发滥用 | `max_uses` 原子扣减；`revoked` 可即时吊销 | `consumeUse` / `revoke` |
| 高频刷接口 | 60s 滑动窗口（分享 token+IP / API Key） | `PublicAccessRateLimiter` |
| 越权访问资源 | `scope` 强校验 + 目标存在性/状态校验 | `requireScope` / `loadTargetWindow` |
| 绕过业务权限 | 不直接查库返回，统一复用 `RagChatService` / `RagRetrieveService`（含配额） | 控制器调用链 |
| Key 泄露 | 只存 SHA-256，明文仅创建时返回，支持 scope 收敛与吊销 | `ApiKeyService` |

---

## 6. 验收标准

1. 创建分享链接可指定 7/30 天有效期；超过 30 天创建被拒绝且提示明确。
2. 无密码链接匿名可访问；有密码链接未带密码提示「该分享链接需要访问密码」，错误密码提示「访问密码错误」。
3. 吊销 / 过期 / 次数用尽的链接分别返回明确错误；次数用尽与限流返回 429。
4. `SESSION` 分享只读展示目标会话消息，不提供输入框；`KB` 分享只可检索；`WINDOW` 分享可匿名问答且访客会话互相隔离。
5. 匿名问答正常产生 SSE（start/content/sources/done），并计入 `wgh_quota_usage`；配额耗尽时页面显示可读提示。
6. API Key 创建时返回一次性明文；再次查询不含明文/哈希；无 scope 的请求返回 403 且提示缺失范围；吊销/过期 Key 返回 401/1002/1003。
7. 每次开放 API 调用在 `wgh_api_call_log` 留痕（Key、方法、路径、状态码、耗时、IP）。
8. `widget.js` 可在任意页面注入 iframe，iframe 内样式不受宿主页影响；`widget-demo.html` 本地直接打开可验证。
9. 单元测试 `PublicAccessUnitTest` 18 项全部通过；`mvn -pl rag-controller -am -DskipTests compile` 与前端 `npm run build` 成功。

---

## 7. 简化实现与遗留

| 项 | 现状 | 影响 |
|----|------|------|
| 限流器 | 进程内内存滑动窗口 | 多实例部署不共享计数，重启清零 |
| Widget 通信 | 仅 iframe 注入，无 `postMessage` | 宿主页无法读取对话事件或控制高度自适应 |
| `frame-ancestors` | 未按域名白名单限制 | 任意站点均可嵌入已授权的分享链接 |
| 匿名身份 | `share_<visitorId>` / `api_<keyId>` 伪用户名 | 匿名/API 用量与真实用户配额隔离统计（这是预期行为） |
| 分享密码 | 无失败次数限制 | 暴力破解防护依赖网关层 |
| API Key | 无自动轮换、无 IP 白名单 | 泄露后需人工吊销 |
