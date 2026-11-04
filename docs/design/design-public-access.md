# 免登录分享 / 嵌入 Widget / 开放 API 设计说明

> 特性组：阶段 H（免登录分享、嵌入 Widget、开放 API）
> 实现位置：后端实现层 `rag-public-access`（`com.wgh.rag.publicaccess`），Controller 在 `rag-controller`（`com.wgh.rag.controller`），Redis 基础设施 `rag-config`（`com.wgh.rag.config.redis`），前端 `PublicShareChat.vue` / `PublicAccess.vue` / `public/widget.js`
> 代码版本：随特性提交；DDL 见 `rag-backend/sql/schema/01-schema.sql` 末尾「免登录分享 / Widget / 开放 API」段

---

## 1. 目标与边界

| 入口 | 目标 | 明确不做（简化实现） |
|------|------|--------------------|
| 免登录分享 | 生成带 token 的分享链接，支持有效期、可选访问密码（含失败锁定）、只读范围（会话/知识库/窗口问答）、可吊销，匿名访问 | 不做验证码；不做分享链接级访问 IP 白名单（API Key 与 Widget 嵌入各自有白名单） |
| Widget | 一行 `<script>` 在第三方页面注入 iframe 展示分享页；服务端按域名 / IP 白名单校验嵌入来源 | 不做宿主页 ↔ iframe 的双向 `postMessage` 通信；不做跨域 SDK 事件订阅 |
| 开放 API | API Key 创建/吊销/scope 管理/可选 IP 白名单，独立鉴权过滤器，复用配额与分布式限流，记录调用日志 | 不做 OAuth2 客户端凭证模式；不做 API 版本协商（固定 `/v1`）；不做计费出账 |

**共同约束**：

1. 三类入口都只能访问被显式授权（分享范围 / Key scope）的资源，不能绕过既有业务权限。
2. 所有入口复用 `RagChatService` 的配额校验，匿名/API 调用同样计入 `wgh_quota_usage`。
3. 限流、密码锁定、嵌入授权等状态集中在 Redis；Redis 不可用时降级为**单机内存**，不阻断应用启动。

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
| embed_enabled | SMALLINT | 0/1，是否允许 Widget 嵌入（默认 0） |
| embed_origin_whitelist | TEXT | 允许嵌入的域名列表，逗号/分号/空白分隔；支持 `example.com`、`*.example.com`、`*` |
| embed_ip_whitelist | TEXT | 允许嵌入的 IP/CIDR 列表，IPv4/IPv6 均支持 |
| created_by / create_time / update_time / deleted | — | BaseEntity 审计字段 |

索引：`idx_share_link_token(token)`、`idx_share_link_target(scope, target_id)`。
老库升级：`schema.sql` 末尾包含 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（幂等）。

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
| ip_whitelist | TEXT | IP/CIDR 白名单；为空表示不限制来源 IP |
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
| ip | VARCHAR(64) | 客户端 IP（经可信代理校验后的真实 IP，见 §8） |

索引：`idx_api_call_log_key(api_key_id, create_time)`。

---

## 3. 接口清单

### 3.1 管理端（需登录，`@Audit` 记录审计）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/share-links` | 分页查询分享链接（`page/size/keyword/scope`） |
| POST | `/api/admin/share-links` | 创建分享链接；body: `name/scope/targetId/password(明文)/maxUses/expiresDays/embedEnabled/embedOriginWhitelist/embedIpWhitelist` |
| PUT | `/api/admin/share-links/{id}/embed` | 更新嵌入开关与域名 / IP 白名单 |
| POST | `/api/admin/share-links/{id}/revoke` | 吊销 |
| GET | `/api/admin/api-keys` | 分页查询 API Key（不回传 `keyHash`） |
| POST | `/api/admin/api-keys` | 创建 Key；body 支持 `ipWhitelist`；返回 `{id,name,scopes,ipWhitelist,plainKey,expiresAt}`，**明文仅此一次** |
| PUT | `/api/admin/api-keys/{id}/ip-whitelist` | 设置 / 更新 IP 白名单（传空表示取消限制） |
| POST | `/api/admin/api-keys/{id}/revoke` | 吊销 |
| GET | `/api/admin/api-keys/{id}/call-logs` | 最近调用日志（默认 20 条） |

### 3.2 免登录分享（`/api/public/share`，无需 JWT）

请求头：`X-Share-Password`（可选）、`X-Visitor-Id`（`[A-Za-z0-9_-]{6,64}`，用于匿名会话隔离）、`X-Embed-Grant` / `X-Embed-Mode: 1`（Widget 嵌入时携带）。

| 方法 | 路径 | scope | 说明 |
|------|------|-------|------|
| GET | `/api/public/share/{token}` | 任意 | 分享信息：`passwordRequired/passwordLocked/lockRemainingSeconds/passwordVerified/embedEnabled/...` + 目标元信息 |
| POST | `/api/public/share/{token}/unlock` | 任意 | 校验访问密码（按 token + IP 计失败次数并锁定） |
| GET | `/api/public/share/{token}/embed/authorize` | 任意 | Widget 嵌入授权：服务端校验 Origin/Referer 与 IP 白名单，签发短期 grant |
| GET | `/api/public/share/{token}/sessions` | WINDOW | 访客会话列表（按 `share_<visitorId>` 隔离） |
| POST | `/api/public/share/{token}/sessions` | WINDOW | 新建访客会话 |
| DELETE | `/api/public/share/{token}/sessions/{sessionId}` | WINDOW | 删除访客会话（仅本人） |
| GET | `/api/public/share/{token}/messages` | SESSION / WINDOW | 只读消息记录 |
| POST | `/api/public/share/{token}/stream` | WINDOW | 匿名 SSE 问答；复用 `RagChatService.streamChat` |
| POST | `/api/public/share/{token}/kb/search` | KB | 只读检索，复用 `RagRetrieveService.searchMultiple(...).matches()` |

错误码约定：

| 场景 | code | 结构化字段 |
|------|------|-----------|
| token 无效 / 吊销 / 过期 / 次数用尽 | `1002/1003/1004/429`（沿用 ResultCode） | — |
| 需要密码 | `OPERATION_NOT_ALLOWED`（刻意不用 1001/1002，避免前端误判为登录失效） | — |
| 密码错误但未锁定 | `1004` | `locked=false`、`remainingAttempts`、`maxFailures` |
| 密码错误达到阈值 / 锁定期间 | `3004 PUBLIC_ACCESS_LOCKED` | `locked=true`、`remainingAttempts=0`、`lockRemainingSeconds`、`lockMinutes` |
| 超过限流阈值 | `429 QUOTA_EXCEEDED` | `dimension`、`limitPerMinute`、`retryAfterSeconds=60`、`store=redis/memory` |
| 嵌入来源未授权 / 缺少 Origin | `1004` | `clientIp`、`origin`（可读文案说明原因） |

> HTTP 状态码沿用仓库既有约定：业务异常返回 HTTP 200，语义码放在响应体 `code`；开放 API 鉴权过滤器层的错误使用 HTTP 401/403。

### 3.3 开放 API（`/api/open/v1`，`X-API-Key` 或 `Authorization: Bearer <key>`）

| 方法 | 路径 | scope | 说明 |
|------|------|-------|------|
| GET | `/api/open/v1/me` | 任意 | 当前 Key 信息（不含哈希） |
| POST | `/api/open/v1/chat/stream` | `chat:write` | SSE 问答；`windowId` 必填，`sessionId` 缺省时自动创建 `api_<keyId>` 会话 |
| GET | `/api/open/v1/kb/search` | `kb:read` | 知识库检索（`kbId` + `query` + `topK`） |
| GET | `/api/open/v1/usage` | `usage:read` | 配额当日用量 + 调用总数 + 最近 20 条调用日志 |

鉴权顺序（`ApiKeyAuthenticationFilter`）：

```
提取 X-API-Key / Bearer → SHA-256 反查 → 吊销/过期校验
  → requireIpAllowed(客户端 IP)        // 未命中：HTTP 403 + code 1004
  → requireScope(路径映射的 scope)     // 缺失：HTTP 403 + code 1004
  → 注入 principal="api_<id>", ROLE_API_CLIENT
  → 业务接口内：api-key / api-ip 双维度限流
```

路径 → scope 映射：`/v1/chat/→chat:write`、`/v1/kb/→kb:read`、`/v1/usage→usage:read`。

### 3.4 Widget

| 资源 | 路径 | 说明 |
|------|------|------|
| 脚本 | `/widget.js` | 读取 `data-token/data-base/data-api-base/data-container/data-height`；先请求授权接口，再注入 iframe |
| 演示页 | `/widget-demo.html` | 本地可打开，支持 `?token=` 动态加载，展示授权成功 / 拒绝效果与 CSP 说明 |

嵌入示例：

```html
<div id="rag-widget"></div>
<script src="https://your-rag-host/widget.js"
        data-token="s_xxx"
        data-base="https://your-rag-host"
        data-container="rag-widget"
        data-height="640px"></script>
```

授权流程（服务端强制，前端无法绕过）：

```
宿主页面 widget.js
  └─ GET /api/public/share/{token}/embed/authorize   （浏览器自动带 Origin）
       ├─ 限流 share-embed（按 IP + 路径）
       ├─ 分享链接必须 embed_enabled=1
       ├─ 域名白名单（Origin/Referer）+ IP 白名单（客户端 IP）
       └─ 通过 → 返回 grant（Redis wgh:pa:embed:grant:{token}:{grant}，绑定 IP，TTL 30 分钟）
  └─ iframe src = {base}/share/{token}?embed=1&grant=xxx
       └─ 分享页读取 grant → 请求头 X-Embed-Grant
            └─ 服务端校验 grant 存在且绑定 IP 一致，否则 1004
```

白名单规则：

- 域名：精确匹配（`example.com`）、通配子域（`*.example.com` 只匹配子域，不匹配裸域）、全通配（`*`）；来源取 `Origin`，缺失时取 `Referer`。
- IP：IPv4/IPv6 CIDR；单个 IP 视为 `/32` 或 `/128`。
- 两项都为空且已开启嵌入：不限制来源（显式语义，便于快速演示）。
- **缺少 `Origin` 且 `Referer` 缺失时默认拒绝**（仅当客户端 IP 命中 IP 白名单时放行），文案提示需从白名单域名页面加载。
- 普通（非嵌入）分享页直连不受白名单限制：同源 XHR 没有 `Origin`，`Referer` 指向分享页自身时放行。

---

## 4. Redis 接入与降级

### 4.1 版本与本地启动

- 版本：**Redis Open Source 8.10.1**（2026-08 发布的最新稳定补丁版本，含安全修复；镜像 `redis:8.10.1-alpine` 已在本地验证可拉取/启动）。
- 本地启动：仓库根目录新增 `docker-compose.yml`（仅含 Redis，供开发使用）。

```bash
docker compose up -d redis          # 启动，默认映射 6379
docker compose ps                   # 查看 wgh-rag-redis 健康状态
docker exec wgh-rag-redis redis-cli ping   # PONG
docker compose down                 # 停止（数据保留在 wgh-redis-data 卷）
```

生产环境建议使用托管 Redis 或独立部署，并开启持久化与访问密码。

### 4.2 Spring 侧接入

- 依赖：`spring-boot-starter-data-redis`（Lettuce 客户端），加在 `rag-config`，由 `rag-controller` 显式依赖 `rag-config`。
- 配置前缀：`wgh.rag.redis.*`
- 关键实现：
  - `RagRedisProperties`：配置绑定。
  - `RagRedisConfig`：`enabled=true` 时创建 `LettuceConnectionFactory` / `StringRedisTemplate` / `RedisCommandPort`；`enabled=false` 时注入 `RedisCommandPort.disabled()`。
  - `RagApplication` 排除 Spring Boot 自动配置（`RedisAutoConfiguration` / `RedisRepositoriesAutoConfiguration`），避免未配置时自动建连。
  - Lettuce 连接是**惰性**的，Redis 未启动不会阻塞应用启动；首次公开访问请求才会尝试连接。

### 4.3 降级策略

`DefaultPublicAccessStore` 采用「Redis 优先 + 单机内存兜底」：

| 场景 | 行为 |
|------|------|
| Redis 正常 | 限流 / 计数 / 锁定 / grant 全部走 Redis；Lua 原子执行 |
| Redis 连接失败 / 超时 | 捕获异常 → 10 秒内不再重试 Redis → 使用进程内 `ConcurrentHashMap` + 滑动窗口 → 每 30 秒最多输出一次 WARN |
| `wgh.rag.redis.enabled=false` | 启动时 WARN 一次，直接使用内存实现 |
| Redis 恢复 | 10 秒退避结束后自动重试；恢复后 Redis 重新成为权威数据源 |

降级影响（已知限制）：

- 单机内存计数**不跨实例共享**，多实例下限流阈值被放大 N 倍。
- 应用重启会清零进程内计数与锁定状态。
- Redis 故障期间写入的计数在恢复后不迁移到 Redis（窗口内计数重新开始）。
- 不可用时不影响启动与主业务（问答/检索/管理端）。

### 4.4 Redis Key 约定

| Key | 类型 / TTL | 用途 |
|-----|-----------|------|
| `wgh:pa:rl:{dimension}:{path}:{subject}` | ZSET，60s | 滑动窗口限流成员（score=毫秒时间戳） |
| `wgh:pa:pwd:fail:{token}:{ip}` | STRING，lockMinutes | 密码失败计数（原子 INCR + PEXPIRE） |
| `wgh:pa:pwd:lock:{token}:{ip}` | STRING，lockMinutes | 锁定标记（值为解锁时间戳） |
| `wgh:pa:embed:grant:{token}:{grant}` | STRING，grantTtlMinutes | Widget 嵌入 grant，值为绑定的客户端 IP |

> 运维可通过 `DEL` 上述 key 立即解除单个主体的限流 / 锁定 / 授权（应用可访问 Redis 时以 Redis 为准）。

---

## 5. 四项能力实现要点

### 5.1 分布式限流

- 维度组合：`API Key`、`用户/IP`、`接口路径`；分享侧使用 `share-token`（token+IP）、`share-ip`（IP）、`share-chat`、`share-kb`、`share-embed`；开放 API 使用 `api-key`、`api-ip`。所有维度 key 都包含请求路径。
- 原子性：滑动窗口用 Lua 脚本一次完成 `ZREMRANGEBYSCORE`（清理窗口外成员）→ `ZCARD`（当前计数）→ 超限直接返回 -1 → `ZADD`（写入本次请求）→ `PEXPIRE`。Redis 单线程执行脚本，天然串行化，避免并发下「读-判断-写」导致的超发。
- 阈值优先级：`rate-limit.overrides["维度:路径"]` > `rate-limit.overrides["维度"]` > 旧版 `share-rate-limit-per-minute` / `api-rate-limit-per-minute` > `rate-limit.default-per-minute`。
- 超限响应：业务码 `429`，`data` 带 `dimension / limitPerMinute / retryAfterSeconds / store`，前端可据此展示「X 秒后重试」，也便于区分当前是 Redis 还是内存降级。

### 5.2 Widget 嵌入授权（IP / 域名白名单）

- 授权必须服务端校验：`widget.js` 在创建 iframe 前先发起跨域 GET，浏览器自动携带真实 `Origin`；服务端据此判断，前端无法伪造（`Origin` 由浏览器写入，不能由脚本任意设置）。
- 通过后签发短期 grant 并绑定客户端 IP，后续分享页 API 请求必须携带 `X-Embed-Grant`；过期 / IP 变化 / 未授权 → `1004` 可读原因。
- 未开启嵌入时带嵌入标识的请求直接拒绝，避免「分享页可用，但被任意 iframe 引用」的隐式放开。
- 浏览器级兜底：如需禁止手工 iframe 且不经 `widget.js` 的页面，请在网关 / 前端静态服务对分享页设置 CSP `frame-ancestors <允许域名>`（见 §10）。

### 5.3 分享密码 + 验证次数限制

- 密码仍以 BCrypt 校验；失败计数 key 为 `token + 客户端 IP`，Redis `INCR` + `PEXPIRE` 原子执行（Lua 脚本，避免并发下计数丢失）。
- 达到 `max-failures`（默认 5）后写入锁定 key（默认 15 分钟）；锁定期间即使密码正确也直接返回 `3004`，响应带 `lockRemainingSeconds` / `lockMinutes`。
- 未达阈值时返回 `1004`，带 `remainingAttempts` / `maxFailures`，前端可提示「还可尝试 N 次」。
- 验证成功后按 `reset-on-success`（默认 true）清零失败计数与锁定。
- `info` 接口返回 `passwordLocked` / `lockRemainingSeconds`，前端打开分享页即可展示锁定倒计时并禁用输入。

### 5.4 API Key IP 白名单

- `wgh_api_key.ip_whitelist` 存 CIDR 文本（逗号/分号/空白分隔），空/NULL 表示不限制。
- 鉴权过滤器在 `verify` 之后、`requireScope` 之前校验 IP：未命中 → HTTP **403** + `code=1004` +「客户端 IP x 不在该 API Key 的 IP 白名单内」。
- 管理端创建时可直接填写，创建后可通过 `PUT /{id}/ip-whitelist` 更新；更新为空即取消限制。
- 保留原有能力：scope 校验、SHA-256 存储（明文仅创建时返回）、吊销、过期。

---

## 6. 配置项

| key | 默认值 | 说明 |
|-----|--------|------|
| `wgh.rag.redis.enabled` | true | 是否启用 Redis；false 时直接使用内存实现 |
| `wgh.rag.redis.host` | localhost | Redis 主机 |
| `wgh.rag.redis.port` | 6379 | Redis 端口 |
| `wgh.rag.redis.database` | 0 | 逻辑库 |
| `wgh.rag.redis.timeout` | 2s | 连接 / 命令超时；影响 Redis 故障时的降级延迟 |
| `wgh.rag.redis.password` | 空 | 访问密码 |
| `wgh.public-access.rate-limit.default-per-minute` | 60 | 未命中覆盖配置时的默认值 |
| `wgh.public-access.rate-limit.overrides.share-token` | 20 | 分享 token + IP 每分钟 |
| `wgh.public-access.rate-limit.overrides.share-ip` | 60 | 分享单 IP 每分钟 |
| `wgh.public-access.rate-limit.overrides.share-chat` | 20 | 分享聊天接口每分钟 |
| `wgh.public-access.rate-limit.overrides.share-kb` | 20 | 分享知识库检索每分钟 |
| `wgh.public-access.rate-limit.overrides.share-embed` | 30 | 嵌入授权接口每分钟 |
| `wgh.public-access.rate-limit.overrides.api-key` | 60 | 单个 API Key 每分钟 |
| `wgh.public-access.rate-limit.overrides.api-ip` | 120 | 开放 API 单 IP 每分钟 |
| `wgh.public-access.share-password.max-failures` | 5 | 密码错误阈值 |
| `wgh.public-access.share-password.lock-minutes` | 15 | 锁定时长（分钟） |
| `wgh.public-access.share-password.reset-on-success` | true | 成功后是否清零失败计数 |
| `wgh.public-access.embed.grant-ttl-minutes` | 30 | 嵌入 grant 有效期（分钟） |
| `wgh.public-access.trusted-proxies` | `[]` | 可信反向代理 CIDR；为空时不采信任何代理头 |
| `wgh.public-access.share-max-expire-days` | 30 | 分享链接有效期上限（天） |
| `wgh.public-access.share-default-expire-days` | 7 | 未指定有效期时的默认天数 |
| `wgh.public-access.max-images-per-request` | 4 | 单次提问图片数量上限 |

环境变量覆盖：`WGH_REDIS_ENABLED/HOST/PORT/DATABASE/TIMEOUT/PASSWORD`、`WGH_RL_*`、`WGH_SHARE_PWD_*`、`WGH_EMBED_GRANT_TTL_MINUTES`。

**跨域 / CSP 说明**：

- 分享页与 Widget 脚本由同一后端/前端源提供，iframe 内为独立文档，样式与宿主隔离。
- 后端 CORS 复用 `CorsConfigurationSource`（`PublicAccessSecurityConfig` 注入），生产环境请收敛到可信域。
- 第三方宿主页若设置 CSP，需允许 `script-src <rag-host>`、`frame-src <rag-host>`。
- 分享页如需禁止任意站点手工 iframe，需在网关 / 静态服务配置 `Content-Security-Policy: frame-ancestors <允许的宿主域>`；当前纯后端未下发该头（见 §10）。

---

## 7. 关键类与调用链路

### 7.1 关键类

| 类 | 职责 |
|----|------|
| `PublicAccessSecurityConfig` | `@Order(1)` + `securityMatcher("/api/public/**","/api/open/**")` 的独立过滤链；关闭 JWT 过滤器的全局自动注册，避免匿名请求被 401 |
| `ApiKeyAuthenticationFilter` | 仅作用于 `/api/open/**`；Key 校验 → IP 白名单 → scope → `Authentication("api_<id>", ROLE_API_CLIENT)`；异常转 401/403/429 JSON；`finally` 写调用日志 |
| `RagRedisConfig` / `RedisCommandPort` | Redis 连接与命令抽象；`enabled=false` 时提供 disabled 实现 |
| `PublicAccessStore` / `DefaultPublicAccessStore` | Redis 优先 + 内存兜底的限流 / 计数 / 字符串存储；Lua 原子脚本 |
| `PublicAccessRateLimiter` | 按维度 + 主体 + 路径限流，超限抛 `QUOTA_EXCEEDED` 并带结构化数据 |
| `SharePasswordGuard` | 密码失败计数、阈值锁定、剩余秒数、成功清零 |
| `EmbedAccessService` | 嵌入授权签发 grant 与后续请求校验 |
| `DomainWhitelistMatcher` / `IpCidrMatcher` | 域名（含通配）与 IP/CIDR 匹配 |
| `ClientIpResolver` | 基于可信代理的真实 IP 解析，防伪造 XFF |
| `ShareLinkService` | 创建/校验/密码（含锁定）/范围/次数扣减/吊销/嵌入配置 |
| `ApiKeyService` | 创建（明文返回一次 + SHA-256 存储）/IP 白名单/校验/scope/吊销/分页 |
| `PublicShareController` | 免登录分享全部端点 + 嵌入授权；`asVisitor()` 临时注入匿名 `SecurityContext` |
| `ShareLinkAdminController` / `ApiKeyAdminController` | 管理端 CRUD 与白名单配置（`@Audit`） |
| `OpenApiController` | 开放 API 端点；`currentKey()` 从 principal `api_<id>` 解析 |

### 7.2 匿名问答链路（WINDOW 分享）

```
浏览器 PublicShareChat.vue
  └─ POST /api/public/share/{token}/stream  (X-Visitor-Id / X-Share-Password / X-Embed-Grant)
       ├─ PublicAccessSecurityConfig 链（permitAll，无 JWT）
       ├─ PublicShareController.stream
       │    ├─ rateLimiter.acquire("share-token", token + ip, path)
       │    ├─ rateLimiter.acquire("share-ip", ip, path)
       │    ├─ shareLinkService.resolveActive(token)      // 无效/吊销/过期/次数
       │    ├─ embedAccessService.validate(link, request) // 嵌入授权
       │    ├─ shareLinkService.requirePassword(link, pwd, ip)  // 含锁定
       │    ├─ shareLinkService.requireScope(link, WINDOW)
       │    ├─ rateLimiter.acquire("share-chat", token + ip, path)
       │    ├─ windowService.getById(targetId)            // 窗口存在且启用
       │    ├─ shareLinkService.consumeUse(link)          // 原子扣减，0 行 → 429
       │    ├─ windowService.createSession(windowId, "share_<visitorId>")  // 首次
       │    └─ asVisitor("share_<visitorId>"):
       │         RagChatService.streamChat(...) → QuotaService.checkAndIncrementChat(...)
       └─ SSE: start → content* → sources → done
```

### 7.3 开放 API 链路

```
第三方服务
  └─ POST /api/open/v1/chat/stream   (X-API-Key)
       ├─ ApiKeyAuthenticationFilter
       │    ├─ ApiKeyService.verify(raw)                 // 无效/吊销/过期
       │    ├─ ApiKeyService.requireIpAllowed(key, ip)   // 未命中 → 403
       │    ├─ ApiKeyService.requireScope(key, "chat:write")
       │    ├─ SecurityContext: principal="api_<id>", ROLE_API_CLIENT
       │    └─ finally → ApiCallLogService.record(...)   // 谁/接口/状态码/耗时/IP
       ├─ OpenApiController.chatStream
       │    ├─ rateLimiter.acquire("api-key", keyId, path)
       │    ├─ rateLimiter.acquire("api-ip", ip, path)
       │    ├─ windowService.getById / 校验状态
       │    ├─ 缺省 sessionId → windowService.createSession(windowId, "api_<keyId>")
       │    └─ 临时 SecurityContext → RagChatService.streamChat(...)
       │         └─ QuotaService.checkAndIncrementChat("api_<keyId>")
       └─ SSE
```

### 7.4 滥用防护矩阵

| 风险 | 防护 | 落点 |
|------|------|------|
| 链接长期有效 | `expires_at` 校验 + 创建时有效期上限 | `ShareLinkService#resolveActive/#create` |
| 链接被转发滥用 | `max_uses` 原子扣减；`revoked` 可即时吊销 | `consumeUse` / `revoke` |
| 高频刷接口 | Redis Lua 滑动窗口（token+IP / IP / API Key / 路径） | `PublicAccessRateLimiter` / `DefaultPublicAccessStore` |
| 多实例超发 | Lua 原子执行，`store=redis`；降级时单机内存（见 §10） | `DefaultPublicAccessStore` |
| 暴力破解密码 | 失败计数 + 阈值锁定 + 剩余尝试/等待秒数 | `SharePasswordGuard` |
| 任意站点嵌入 | 服务端 Origin/Referer + IP 白名单 + 短期 grant（绑定 IP） | `EmbedAccessService` / `widget.js` |
| 伪造代理 IP | 仅当 TCP 对端是可信代理时才采信 XFF，并从右向左取第一个非可信地址 | `ClientIpResolver` |
| 越权访问资源 | `scope` 强校验 + 目标存在性/状态校验 | `requireScope` / `loadTargetWindow` |
| 绕过业务权限 | 统一复用 `RagChatService` / `RagRetrieveService`（含配额） | 控制器调用链 |
| Key 泄露 | 只存 SHA-256、明文仅创建时返回、scope 收敛、可选 IP 白名单、可吊销 | `ApiKeyService` |

---

## 8. 真实 IP 获取与 XFF 信任边界

`ClientIpResolver.resolve(request)` 规则：

1. 默认只使用 `request.getRemoteAddr()`（TCP 对端地址）。
2. 只有当对端地址命中 `wgh.public-access.trusted-proxies` 时，才读取 `X-Forwarded-For`：
   - 从右向左遍历（最右侧是离服务端最近的代理）；
   - 跳过同样命中可信代理列表的地址；
   - 返回第一个非可信地址。
3. 没有 XFF 时，才使用 `X-Real-IP`。
4. 可信代理列表为空（默认）时，任何代理头都不生效，客户端直接伪造 `X-Forwarded-For` 无法绕过 IP 白名单 / 限流。

部署示例（Nginx 在最外层，地址 `10.0.0.10/32`）：

```yaml
wgh:
  public-access:
    trusted-proxies:
      - 10.0.0.10/32
      - 172.17.0.0/16   # Docker 内网
```

Nginx 需配置 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`。**不要**把 `trusted-proxies` 设置为 `0.0.0.0/0` 或过大的网段，否则等同于信任所有客户端伪造的 XFF。

---

## 9. 验收标准

1. 创建分享链接可指定 7/30 天有效期；超过 30 天创建被拒绝且提示明确。
2. 无密码链接匿名可访问；有密码链接未带密码提示「该分享链接需要访问密码」，错误密码提示剩余尝试次数；达到阈值后锁定并提示剩余秒数，锁定期间正确密码仍被拒绝。
3. 吊销 / 过期 / 次数用尽的链接分别返回明确错误；次数用尽与限流返回 429。
4. `SESSION` 分享只读展示目标会话消息，不提供输入框；`KB` 分享只可检索；`WINDOW` 分享可匿名问答且访客会话互相隔离。
5. 匿名问答正常产生 SSE（start/content/sources/done），并计入 `wgh_quota_usage`；配额耗尽时页面显示可读提示。
6. API Key 创建时返回一次性明文；再次查询不含明文/哈希；无 scope 的请求返回 403 且提示缺失范围；IP 不在白名单返回 403；吊销/过期 Key 返回 401/1002/1003。
7. 每次开放 API 调用在 `wgh_api_call_log` 留痕（Key、方法、路径、状态码、耗时、IP）。
8. 开启嵌入并配置域名白名单后：白名单来源可获取 grant 并加载 iframe；非白名单来源与缺少 Origin 的请求返回 1004 可读原因；后续接口无有效 grant 被拒绝。
9. Redis 可用时超限响应 `data.store=redis`；停止 Redis 后应用不重启、请求自动降级为内存并输出 WARN。
10. `mvn compile` / `mvn test` 全模块通过；`PublicAccessUnitTest` 与 `PublicAccessRedisSecurityTest` 全绿；前端 `npm run build` 成功。

---

## 10. 剩余风险与限制

| 项 | 现状 | 影响 / 建议 |
|----|------|------------|
| Redis 降级 | 内存实现按实例隔离 | 多实例部署时 Redis 故障会导致限流阈值放大、锁定不共享；建议 Redis 高可用（哨兵/集群）并监控连接 |
| 限流降级窗口 | 故障后 10 秒内不重试 Redis | 故障恢复初期仍走内存，最多 10 秒；可接受 |
| 手工 iframe | `frame-ancestors` 未由后端下发 | 攻击者可绕过 `widget.js` 直接 iframe 分享页（普通分享页仍是公开的）；需在网关/静态服务按域名配置 CSP 兜底 |
| 嵌入白名单为空 | 已开启但未配置域名/IP 时允许任意来源 | 管理端已提示；生产应强制填写，或后续增加「必须配置白名单」校验 |
| Origin 缺失 | 默认拒绝（IP 命中白名单除外） | 某些旧浏览器/客户端可能不带 Origin；文档已说明 |
| XFF 信任 | 仅可信代理生效 | 配置过宽会重新引入伪造风险；需与网关拓扑保持一致 |
| 锁定共享 | 计数 key 含 token + IP | 换 IP 可继续尝试；如需更强防护可叠加 token 维度总计数或验证码 |
| Widget 通信 | 仅 iframe 注入，无 `postMessage` | 宿主页无法读取对话事件或自适应高度 |
| 匿名身份 | `share_<visitorId>` / `api_<keyId>` 伪用户名 | 用量与真实用户隔离统计（预期行为） |
| API Key 轮换 | 无自动轮换 | 泄露后需人工吊销；可配合 IP 白名单降低风险 |
