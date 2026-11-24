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
| usage_epoch | BIGINT | 计数纪元：管理端「重置用量」时 +1，退款按扣费时的纪元精确回退 |
| deleted | SMALLINT | 逻辑删除标记（0/1） |

唯一键 `(username, date_key)`；事务内首插使用 SAVEPOINT 兜住唯一键冲突（PostgreSQL 下语句失败会使整个事务 aborted，不能靠 catch DuplicateKey 续跑），随后用带上限条件的原子 UPDATE 累加；无事务路径使用「先 UPDATE、0 行再 INSERT」的 upsert。

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
| usage_epoch | BIGINT | 扣费时的计数纪元快照；NULL 表示存量流水，不做纪元校验 |
| finish_time | TIMESTAMP | 终态时间（REFUNDED / DEAD） |

状态机：`CHARGED`（已扣未决）→ `SETTLED`（**已确认向客户端下发过有效内容**，扣费生效）；
`CHARGED` → `REFUNDED`（无产出退款成功）；
`CHARGED` → `REFUND_FAILED`（退款失败待重试）→ `REFUNDED` / `DEAD`（补偿耗尽，等人工）；
`DEAD` / `REFUND_FAILED` 仅允许管理端人工「退款」或「核销（置 SETTLED）」，自动作业不扫描 `DEAD`。

退款在**同一个事务**内完成「流水置已退（条件 UPDATE）+ 计数按 `usage_epoch` 条件减一（`chat_count > 0`）」；
幂等键落在 `charge_id` 上，进程重启不丢。纪元列增量脚本：
[20261124/02_quota_usage_epoch.sql](../../../../rag-backend/sql/migrations/20261124/02_quota_usage_epoch.sql)
（幂等，`wgh_rag_dev` / `wgh_rag_test` 均可执行；存量流水的 `usage_epoch` 为 NULL，退款行为向后兼容）。
**上线顺序**：纪元列位于退款比较条件中，必须先执行迁移再部署本版本代码。

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
| `wgh.rag.quota.fail-open-on-count-error` | false | 计数写入异常时的策略：false=拒绝（业务码 5004），true=放行且不扣费（仅限 DB 短时抖动时人工临时兜底） |

配置缺失时 `QuotaProperties` / `QuotaProperties.Charge` 的字段默认值生效，不存在启动失败风险。
增量片段见 [20261120/03_quota-refund.yml](../../../../rag-backend/config/migrations/20261120/03_quota-refund.yml)
与本轮 [20261124/06_quota-count-policy.yml](../../../../rag-backend/config/migrations/20261124/06_quota-count-policy.yml)。

## 5. 接口清单

### 5.1 管理端（`QuotaAdminGuard`：仅 `admin` 角色或内置 admin 账号）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/quota/users?page=&size=&username=` | 分页列出有自定义配置或有用量记录的用户 |
| GET | `/api/admin/quota/users/{username}` | 查询生效配置 + 当期用量 |
| PUT | `/api/admin/quota/users/{username}` | 修改/创建自定义配置（period/chatLimit/tokenLimit/enabled/remark） |
| POST | `/api/admin/quota/users/{username}/reset-usage` | 清零当期用量（不改配置） |
| DELETE | `/api/admin/quota/users/{username}` | 恢复全局默认（逻辑删除自定义配置） |
| GET | `/api/admin/quota/refunds?status=&username=&page=&size=` | 列出 `REFUND_FAILED` / `DEAD` 流水（只读） |
| GET | `/api/admin/quota/refunds/{chargeId}` | 查询单笔流水完整上下文（退款/核销前决策依据） |
| POST | `/api/admin/quota/refunds/{chargeId}/refund` | 人工退款：按该笔流水的一次计费回退，允许 `DEAD`，写审计日志 |
| POST | `/api/admin/quota/refunds/{chargeId}/write-off` | 人工核销：确认扣费有效，置 `SETTLED`（不改变计数），写审计日志 |

列表行字段：`username / custom / period / periodKey / chatLimit / tokenLimit / enabled / remark / chatUsed / tokenUsed`。

### 5.2 用户自查

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/quota/my` | 当前用户生效配额、来源（CUSTOM/DEFAULT）、当期用量、剩余量、`nearLimit`（≥90%） |

## 6. 调用链

```
前端 ChatPlayground / ShareChat / 公开分享页
  └─ POST /api/chat/windows/{id}/stream（或 /api/chat/share/{token}/stream、/api/public/share/{id}/stream）
       └─ RagChatService.streamChat（两个入口都带 @Transactional）
            ├─ QuotaService.checkAndIncrementChat(username, windowId)
            │     ├─ resolve(username)          // 用户配置(启用) > 全局默认
            │     ├─ usageOf(username, periodKey)
            │     ├─ 超限 → BusinessException(429)
            │     ├─ 计数 +1（事务内 SAVEPOINT 建行 + 条件 UPDATE；无事务走 upsert）
            │     ├─ 读取 usage_epoch 快照
            │     └─ QuotaChargeLedger.create(...)  // 落 wgh_quota_charge(CHARGED, usage_epoch)
            ├─ 会话创建后 attachSession(charge, sessionId)
            ├─ 提交异步任务；任务先等外层事务 afterCommit（提交闸门，10s 超时）
            ├─ 首个非空 content 下发 → markChargeSettled(charge)  // CHARGED → SETTLED
            ├─ 执行结束仍无产出 → refundChat(charge)              // CHARGED → REFUNDED，计数按 epoch 回退
            ├─ 统一执行器拒绝 → 异常上抛，扣费随事务回滚（不打 refunded 指标）
            └─ 回复完成后 QuotaService.addTokens(username, totalTokens)
```

- 计数发生在请求线程内（可读取登录用户），token 累加在异步回调内执行；失败仅 `WARN` 日志，不影响 SSE（除非显式拒绝计数失败，见规则 5）。
- 事务形态：普通入口 `streamChat` 与分享入口 `streamShareChat` 都带 `@Transactional`（controller 经代理调用）；`streamShareChat` 内部对 `streamChat` 的自调用在已有事务中 REQUIRED 加入同一事务，因此「计数 + 流水 + 会话 + 用户消息」在两个入口都原子，不存在孤儿计数。
- 提交闸门：异步任务不会早于外层事务提交运行；事务回滚或等待超时则不生成。超时则流水停留 `CHARGED`，由补偿作业按「无产出」退款。
- 补偿作业 `QUOTA_REFUND` 走维护车道，`wgh.scheduler.enabled=false` 时由实例内守护线程本地兜底（二者互斥，不双跑）；退款迁移本身是带状态条件的 UPDATE，多实例并发也不会重复退款。
- 前端 `streamChatApi` / `streamShareApi` 会识别 JSON 错误体（含 `code=429`）并抛出 `message`，聊天页展示「请求失败: <配额提示>」。
- 管理端页面：`rag-frontend/src/views/admin/QuotaManagement.vue`（菜单「用户配额」）。
- 用户自查页面：`rag-frontend/src/views/admin/MyQuota.vue`（右上角用户菜单「我的配额」）。

## 7. 关键规则

1. **用户配置 > 全局默认**：仅当记录存在且 `enabled=1` 时使用自定义周期与上限；否则回退默认（日周期）。
2. **周期重置**：日键 `yyyy-MM-dd`、月键 `yyyy-MM`；新周期写入新行，旧周期计数自然隔离，无需定时任务。
3. **上限语义**：`chatLimit/tokenLimit <= 0` 视为不限制；相等即拒绝（达到上限不再放行）。
4. **并发**：检查与累加分离，累加使用带上限条件的原子 UPDATE；极端并发下允许少量越界（检查→计数窗口），但计数不丢失。
5. **降级**：配额查询/写入异常默认**拒绝**本次对话（业务码 5004「计费服务暂不可用」），并打 `[ALERT]` 日志与 `wgh.quota.count.failure{result=reject}` 指标；运维可通过 `wgh.rag.quota.fail-open-on-count-error=true` 临时切回放行（放行时不扣费，指标记为 `result=allow`，需事后排查免费请求）。例外：已计数但流水落库失败且存在外层事务时，上抛让扣费一起回滚，避免产生不可对账的扣费。
6. **权限**：管理端接口在 RBAC 未完成前使用最小守卫（admin 角色/内置 admin），后续可平滑切换为 `@RequiresPermission("quota:manage")`。
7. **计费口径（无产出不计费）**：只有「向客户端下发过至少一个有效内容增量」才结算扣费。有效产出包括非空 `content` 增量（流式首包、语义缓存、无结果兜底话术）、工作流最终文本、Agent 最终答复；纯状态/心跳/元信息/错误事件/空回答不计产出。已有部分内容后失败/停止/超时/断连，仍视为有产出、不退款。
8. **持久化幂等与人工出口**：退款以 `wgh_quota_charge.charge_id` 为幂等键；重复退款返回 `DUPLICATE`，已结算/已 DEAD 返回 `NOTHING_TO_REFUND`。补偿失败按指数退避写回 `next_retry_at`，超过 `refund-max-attempts` 置 `DEAD`，同时打 `wgh.quota.refund{result=dead}` 与 `[ALERT]` 日志；管理端可按单笔流水人工退款或核销（均写审计）。
9. **精确回退**：退款只回退与扣费时 `usage_epoch` 相同的计数；管理端「重置用量」会自增纪元，因此重置前旧流水退款不会退掉重置后新产生的扣费。存量流水 `usage_epoch=NULL` 时保持原行为。
10. **分享次数**：公开分享链接在准入时原子占用 `use_count`（保住 `max_uses` 语义）；本次无产出或准入阶段同步失败时回补一次。

## 8. 验收标准

- [x] `rag-quota` 独立模块，原调用方签名与语义不变（`checkAndIncrementChat` / `addTokens` / `todayUsage` 等）。
- [x] 全局默认、用户覆盖、禁用回退、超限 429、日/月周期键、管理端修改/重置用量/恢复默认、并发计数、配置缺失回退均有单元测试。
- [x] 端到端：配额耗尽 → 业务码 429 → 统一响应体 `code=429` 且提示可读。
- [x] 前端 `npm run build` 通过；管理页/自查页路由与菜单可用。
- [x] schema 增量幂等，可在 live DB 重复执行。
- [x] 后端全量 `mvn test` 绿（配额模块 19 项）。
- [x] 扣费流水 `wgh_quota_charge` 落地（迁移脚本 + 基线登记），退款同事务原子幂等，不依赖进程内存。
- [x] 补偿作业 `QUOTA_REFUND` 可在维护车道运行：保护期扫描、指数退避、超限 DEAD；单测覆盖退款/重试/DEAD/关闭分支。
- [x] 无产出不计费：首个非空内容下发才结算；全链失败/无内容/停止/断连取消无产出时退款，语义缓存命中与部分内容后失败仍计费。
- [x] 异步生成不早于外层事务提交；事务回滚/闸门超时不生成（消除「已交付却被退款」）。
- [x] 管理端可查询单笔流水上下文，并人工退款或核销 `DEAD`（admin 守卫 + 审计）。
- [x] 纪元列迁移可重复执行，存量 NULL 流水行为不变。
- [ ] `sql/migrations/20261124/02_quota_usage_epoch.sql` 在 `wgh_rag_dev` / `wgh_rag_test` 执行（待我方批准）。

## 9. 修订记录

### 2026-11-24：无产出不计费与退款风险项修复

- 结算语义调整：`SETTLED` 由「执行已开始」改为「已确认下发有效内容」；删除异步首行的提前结算，改为**首个非空内容增量下发后结算**，执行结束无产出则立即退款。
- 新增提交闸门：异步生成与结算都不早于外层事务提交；事务回滚/等待超时（10s）不生成。普通入口与分享入口（`streamShareChat` 已补 `@Transactional`）事务边界一致。
- 分享入口原子化：controller 经代理调用，`streamShareChat` 内对 `streamChat` 的自调用加入同一事务，「计数 + 流水 + 会话 + 用户消息」同事务。
- 计数精确回退：新增 `usage_epoch` 两列（[20261124/02_quota_usage_epoch.sql](../../../../rag-backend/sql/migrations/20261124/02_quota_usage_epoch.sql)），重置用量自增纪元；退款仅回退同纪元计数，存量 NULL 流水保持旧行为。
- 计数异常默认拒绝：业务码 5004，`wgh.rag.quota.fail-open-on-count-error` 可临时切回放行；指标区分 `reject` / `allow`。
- 计数首插改用 SAVEPOINT，修复 PostgreSQL 下 catch-DuplicateKey 重试导致事务 aborted 的问题。
- `DEAD` 出口：管理端单笔查询 + 人工退款 + 人工核销（admin 守卫 + 审计）；置 DEAD 打 `wgh.quota.refund{result=dead}` 与 `[ALERT]` 日志。
- 补偿作业在调度中心关闭时由实例内守护线程兜底，与调度提交互斥。
- 公开分享 `use_count`：准入时占位，无产出或准入同步失败时回补。
- 已知残留窗口：首包已下发但进程在结算写入前崩溃时，流水会停留 `CHARGED` 并被补偿作业退款，形成欠计费。
