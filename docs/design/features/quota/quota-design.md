# 配额模块设计（rag-quota）

> 状态：已完成（分阶段落地）。本文覆盖模块结构、数据表、接口、配置项、调用链与验收标准。
> 关联用例：[docs/testing/test-cases.md](../../../testing/test-cases.md) 第 14 节。

## 1. 目标与范围

- 按用户维度限制聊天请求数（对话条数）与 token 消耗，支持日/月两种统计周期。
- 解析优先级：**用户自定义配置（启用） > 全局默认**；用户无配置或配置被禁用时回退全局默认。
- 超限返回业务码 `429`（项目统一 HTTP 200 + 业务码，前端拦截器/SSE 解析均会展示可读提示）。
- 管理端可列表/查询/修改/重置用量/恢复默认；用户可自查配额与当期用量，接近上限（≥90%）提示。
- 配额故障（DB 异常）只记录日志，不阻断聊天主流程；并发计数通过 `INSERT ... ON CONFLICT` 原子累加，不丢量。

## 2. 模块结构（新旧包路径）

| 内容 | 旧位置（已删除） | 新位置 |
|------|------------------|--------|
| 配额服务 | `com.wgh.rag.common.quota.QuotaService` | `com.wgh.rag.quota.service.QuotaService` |
| 用量实体 | `com.wgh.rag.common.entity.QuotaUsage` | `com.wgh.rag.quota.entity.QuotaUsage` |
| 用量 Mapper | `com.wgh.rag.common.mapper.QuotaUsageMapper` | `com.wgh.rag.quota.mapper.QuotaUsageMapper` |
| 用户配置实体 | — | `com.wgh.rag.quota.entity.UserQuotaConfig` |
| 用户配置 Mapper | — | `com.wgh.rag.quota.mapper.UserQuotaConfigMapper` |
| 全局默认属性 | `RagProperties.Quota` | `com.wgh.rag.quota.properties.QuotaProperties` |
| 当前用户解析 | — | `com.wgh.rag.quota.service.QuotaUserResolver` / `DefaultQuotaUserResolver` |
| 管理端权限守卫 | — | `com.wgh.rag.quota.service.QuotaAdminGuard` |
| 生效配额模型 | — | `com.wgh.rag.quota.service.EffectiveQuota` |
| 管理端接口 | — | `com.wgh.rag.controller.QuotaAdminController` |
| 用户自查接口 | — | `com.wgh.rag.controller.QuotaUserController` |

依赖方向：`rag-chat`、`rag-controller` → `rag-quota` → `rag-common`；`rag-quota` 不依赖 `rag-auth`，避免与 RBAC 半成品耦合。

## 3. 数据模型

### 3.1 `wgh_quota_usage`（周期用量）

| 列 | 类型 | 说明 |
|----|------|------|
| username | VARCHAR(50) | 用户名 |
| date_key | VARCHAR(10) | 周期键：日=`yyyy-MM-dd`，月=`yyyy-MM` |
| chat_count | INT | 当期对话条数 |
| token_used | BIGINT | 当期 token 用量（实际或估算） |
| deleted | SMALLINT | 逻辑删除标记（0/1） |

唯一键 `(username, date_key)`；累加 SQL 使用 `ON CONFLICT (username, date_key) DO UPDATE` 原子更新。

### 3.2 `wgh_user_quota_config`（用户自定义配额）

| 列 | 类型 | 说明 |
|----|------|------|
| username | VARCHAR(50) | 用户名（唯一） |
| period | VARCHAR(10) | `DAY` / `MONTH` |
| chat_limit | INT | 请求数上限（<=0 不限制） |
| token_limit | BIGINT | token 上限（<=0 不限制） |
| enabled | SMALLINT | 是否启用自定义（0/1，禁用时回退全局默认） |
| remark | VARCHAR(500) | 备注 |

DDL 位于 [rag-backend/sql/schema/01-schema.sql](../../../../rag-backend/sql/schema/01-schema.sql)，均为 `CREATE TABLE IF NOT EXISTS` 幂等增量。
「恢复默认」采用逻辑删除；再次保存时通过 `selectAnyByUsername` + `restoreDeleted` 复活原行，避免 username 唯一键冲突。

### 3.3 `wgh_quota_charge`（扣费流水）

| 列 | 类型 | 说明 |
|----|------|------|
| charge_id | VARCHAR(64) | 扣费标识（UUID），唯一键；退款幂等键 |
| username | VARCHAR(50) | 用户名 |
| period_key | VARCHAR(10) | 扣费时的周期键；退款按此键回退 |
| amount | INT | 本次扣减的对话条数（当前固定 1） |
| status | VARCHAR(16) | `CHARGED` / `SETTLED` / `REFUNDED` / `REFUND_FAILED` / `DEAD` |
| window_id / session_id / task_id | BIGINT / BIGINT / VARCHAR(64) | 关联窗口、会话与任务 |
| retry_count | INT | 补偿已重试次数 |
| next_retry_at | TIMESTAMP | 下次可补偿时间（指数退避） |
| last_error | VARCHAR(500) | 最近一次退款失败原因 |
| finish_time | TIMESTAMP | 终态时间（REFUNDED / DEAD） |

状态机：`CHARGED`（已扣未决）→ `SETTLED`（执行已开始，扣费生效）；
`CHARGED` → `REFUNDED`（退款成功）；
`CHARGED` → `REFUND_FAILED`（退款失败待重试）→ `REFUNDED` / `DEAD`（补偿耗尽等人工）。

退款在**同一个事务**内完成「流水置已退（条件 UPDATE）+ 计数条件减一（`chat_count > 0`）」；
幂等键落在 `charge_id` 上，进程重启不丢。增量脚本：
[20261120/02_quota_charge.sql](../../../../rag-backend/sql/migrations/20261120/02_quota_charge.sql)
（本轮只创建，执行需在 `wgh_rag` / `wgh_rag_fresh` 两个库分别批准）。
**上线顺序**：扣费流水位于聊天主链路，必须先执行迁移再部署本版本代码。

## 4. 配置项

| 键 | 默认值 | 说明 |
|----|-------|------|
| `wgh.rag.quota.chat-daily-limit` | 200（`WGH_QUOTA_CHAT_DAILY_LIMIT`） | 全局默认每人每天对话条数上限，<=0 不限制 |
| `wgh.rag.quota.token-daily-limit` | 2000000（`WGH_QUOTA_TOKEN_DAILY_LIMIT`） | 全局默认每人每天 token 上限，<=0 不限制 |
| `wgh.rag.quota.charge.refund-enabled` | true | 是否启用退款补偿作业 |
| `wgh.rag.quota.charge.refund-protection` | 5m | 已扣未决保护期，超过后才补偿 |
| `wgh.rag.quota.charge.refund-scan-interval` | 60s | 补偿作业提交间隔（维护车道） |
| `wgh.rag.quota.charge.refund-max-attempts` | 5 | 单条流水最大退款尝试次数（含首次） |
| `wgh.rag.quota.charge.refund-backoff` | 60s | 退避基数（按尝试次数指数递增） |
| `wgh.rag.quota.charge.refund-backoff-max` | 30m | 退避上限 |
| `wgh.rag.quota.charge.refund-batch-size` | 200 | 单次扫描最多处理条数 |

配置缺失时 `QuotaProperties` / `QuotaProperties.Charge` 的字段默认值生效，不存在启动失败风险。
保留历史键位 `wgh.rag.quota.*`，从 `RagProperties` 迁出后行为不变。增量片段见
[20261120/03_quota-refund.yml](../../../../rag-backend/config/migrations/20261120/03_quota-refund.yml)。

## 5. 接口清单

### 5.1 管理端（`QuotaAdminGuard`：仅 `admin` 角色或内置 admin 账号）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/quota/users?page=&size=&username=` | 分页列出有自定义配置或有用量记录的用户 |
| GET | `/api/admin/quota/users/{username}` | 查询生效配置 + 当期用量 |
| PUT | `/api/admin/quota/users/{username}` | 修改/创建自定义配置（period/chatLimit/tokenLimit/enabled/remark） |
| POST | `/api/admin/quota/users/{username}/reset-usage` | 清零当期用量（不改配置） |
| DELETE | `/api/admin/quota/users/{username}` | 恢复全局默认（逻辑删除自定义配置） |

列表行字段：`username / custom / period / periodKey / chatLimit / tokenLimit / enabled / remark / chatUsed / tokenUsed`。

### 5.2 用户自查

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/quota/my` | 当前用户生效配额、来源（CUSTOM/DEFAULT）、当期用量、剩余量、`nearLimit`（≥90%） |

## 6. 调用链

```
前端 ChatPlayground / ShareChat
  └─ POST /api/chat/windows/{id}/stream（或 /api/chat/share/{token}/stream）
       └─ RagChatService.streamChat
            ├─ QuotaService.checkAndIncrementChat(username, windowId)
            │     ├─ resolve(username)          // 用户配置(启用) > 全局默认
            │     ├─ usageOf(username, periodKey)
            │     ├─ 超限 → BusinessException(429)
            │     ├─ incrementChatAtomically(+1 chat)
            │     └─ QuotaChargeLedger.create(...)  // 落 wgh_quota_charge(CHARGED)
            ├─ 会话创建后 attachSession(charge, sessionId)
            ├─ 异步执行开始 → markChargeSettled(charge)  // CHARGED → SETTLED
            ├─ 统一执行器拒绝 → refundChat(charge)       // 同一事务：流水置退 + 计数减一
            └─ 回复完成后 QuotaService.addTokens(username, totalTokens)
```

- 计数发生在请求线程内（可读取登录用户），token 累加在异步回调内执行；失败仅 `WARN` 日志，不影响 SSE。
- 事务边界：普通聊天入口 `streamChat` 带 `@Transactional`，拒绝异常上抛时扣费与流水一起回滚；
  分享入口 `streamShareChat` 内部直接调用 `streamChat`（自调用绕过代理，**无事务**），
  因此拒绝分支的退款是真实提交的独立补偿，失败由补偿作业按流水重试。
- 补偿作业 `QUOTA_REFUND` 走维护车道：扫描 `CHARGED` 超过保护期与 `REFUND_FAILED` 到期的流水，
  复用调度中心的作业表、租约与 Redis 单飞；Redis 只做锁，不存待退款数据。
- 前端 `streamChatApi` / `streamShareApi` 会识别 JSON 错误体（含 `code=429`）并抛出 `message`，聊天页展示「请求失败: <配额提示>」。
- 管理端页面：`rag-frontend/src/views/admin/QuotaManagement.vue`（菜单「用户配额」）。
- 用户自查页面：`rag-frontend/src/views/admin/MyQuota.vue`（右上角用户菜单「我的配额」）。

## 7. 关键规则

1. **用户配置 > 全局默认**：仅当记录存在且 `enabled=1` 时使用自定义周期与上限；否则回退默认（日周期）。
2. **周期重置**：日键 `yyyy-MM-dd`、月键 `yyyy-MM`；新周期写入新行，旧周期计数自然隔离，无需定时任务。
3. **上限语义**：`chatLimit/tokenLimit <= 0` 视为不限制；相等即拒绝（达到上限不再放行）。
4. **并发**：检查与累加分离，累加使用带上限条件的原子 UPDATE；极端并发下允许少量越界（检查→计数窗口），但计数不丢失。
5. **降级**：配额查询/写入异常不阻断聊天；配置缺失使用代码默认值。例外：已计数但流水落库失败且存在外层事务时，上抛让扣费一起回滚，避免产生不可对账的扣费。
6. **权限**：管理端接口在 RBAC 未完成前使用最小守卫（admin 角色/内置 admin），后续可平滑切换为 `@RequiresPermission("quota:manage")`。
7. **退款范围**：只退「已扣费但执行未真正开始」的场景。任务提交成功后执行失败不退款，保持按次计费语义不变。
8. **持久化幂等**：退款以 `wgh_quota_charge.charge_id` 为幂等键；重复退款返回 `DUPLICATE`，已结算/已 DEAD 返回 `NOTHING_TO_REFUND`。补偿失败按指数退避写回 `next_retry_at`，超过 `refund-max-attempts` 置 `DEAD` 等人工处理。

## 8. 验收标准

- [x] `rag-quota` 独立模块，原调用方签名与语义不变（`checkAndIncrementChat` / `addTokens` / `todayUsage` 等）。
- [x] 全局默认、用户覆盖、禁用回退、超限 429、日/月周期键、管理端修改/重置用量/恢复默认、并发计数、配置缺失回退均有单元测试。
- [x] 端到端：配额耗尽 → 业务码 429 → 统一响应体 `code=429` 且提示可读。
- [x] 前端 `npm run build` 通过；管理页/自查页路由与菜单可用。
- [x] schema 增量幂等，可在 live DB 重复执行。
- [x] 后端全量 `mvn test` 绿（配额模块 19 项）。
- [x] 扣费流水 `wgh_quota_charge` 落地（迁移脚本 + 基线登记），退款同事务原子幂等，不依赖进程内存。
- [x] 补偿作业 `QUOTA_REFUND` 可在维护车道运行：保护期扫描、指数退避、超限 DEAD；单测覆盖退款/重试/DEAD/关闭分支。
- [ ] `sql/migrations/20261120/02_quota_charge.sql` 在 `wgh_rag` / `wgh_rag_fresh` 执行（待我方批准）。
