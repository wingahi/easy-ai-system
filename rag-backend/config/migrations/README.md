# 增量配置片段（config/migrations/）

本目录与 `sql/migrations/` 对称，存放**按日期归档的增量配置片段**，用于记录每一次改动新增/变更了哪些配置键、值是什么、落在哪个配置文件。

与 `sql/migrations/` 的区别只有一处：SQL 片段变更的是数据库，配置片段变更的是应用配置；组织约定完全一致。

## 目录约定

```
config/
└── migrations/
    └── YYYYMMDD/              # 与 sql/migrations/YYYYMMDD/ 一一对应
        ├── 01_<分区>.yml       # 编号 + 分区名，与 sql 的 01_xxx.sql 编号习惯一致
        ├── 02_<分区>.yml
        └── ...
```

- **只放增量**：每个文件只包含当次新增或变更的键，不复制整份 `application-features.yml`。
- **值必须与落地文件一致**：片段中的值等于 `application-features.yml` / `application.yml` 的当前值（也等于对应配置类的代码默认值）。
- **不参与自动加载**：这些文件是变更记录/覆盖素材，Spring 不会读取；运行时生效的只有 `application-features.yml` 与 `application.yml`。
- **空值项不进片段**：空串/`null` 默认项（如 `wgh.sandbox.work-root`、`wgh.rag.memory.summary-model-id`）保留在代码中，避免 YAML 空值绑定改变语义。

## 与 sql/migrations 的对应关系

| 维度 | `sql/migrations/` | `config/migrations/` |
| --- | --- | --- |
| 日期目录 | `20261118/` | `20261119/` |
| 文件编号 | `01_eval_dataset_versioning.sql` | `01_sandbox.yml` |
| 内容范围 | 只含本次 DDL 增量 | 只含本次配置键增量 |
| 适用对象 | 存量库（新库走 `sql/schema/`） | 存量部署（新部署直接用当前 `application-features.yml`） |
| 幂等策略 | `IF NOT EXISTS` / 覆盖 COMMENT | 按 key 覆盖（同名键后者生效） |
| 执行方式 | `psql -f ...` | 合并进应用配置后重启（见下） |

同一天产生的 SQL 与配置增量共用同一个日期目录名。本次配置增量随 2026-11-19 的代码提交产生（`20261119/`），对应的库结构增量在 `sql/migrations/20261118/`，两者不是同一天产生，因此日期目录不同属正常。

## 如何合并进应用配置

**推荐：手工合并（保持单一事实来源）**

1. 打开 `rag-backend/rag-app/src/main/resources/application-features.yml`；
2. 按分区把片段中的键粘贴到对应位置（片段与目标文件的分区顺序一致：memory → storage → sandbox → multi-agent → workflow-context）；
3. 已存在的键直接改值，缺失的键补上；片段中的注释可一并保留；
4. `06_app-runtime.yml` 的键合并到 `application.yml`（`wgh.rag.redis.key-prefix`、`management.metrics.tags.wgh.instance`）；
5. 重启服务使配置生效。

**可选：作为额外属性源加载**

若想保留片段原样，可在 `application.yml` 追加：

```yaml
spring:
  config:
    import:
      - optional:classpath:application-features.yml
      - optional:file:./config/migrations/20261119/01_sandbox.yml
```

优先级为「后加载者覆盖先加载者」，按 key 深度合并，不会整体替换。生产环境更推荐直接改 `application-features.yml`，避免多份文件叠加造成排障困难。

## 2026-11-19 片段清单

| 文件 | 分区 | 键数 | 目标文件 |
| --- | --- | --- | --- |
| `01_sandbox.yml` | 代码沙箱 | 15（新增） | `application-features.yml` |
| `02_memory.yml` | 检索式长期记忆 | 9（新增） | `application-features.yml` |
| `03_storage-maintenance.yml` | 存储维护 | 16（新增） | `application-features.yml` |
| `04_multi-agent.yml` | 多 Agent 上下文预算 | 4（既有键收敛，值未变） | `application-features.yml` |
| `05_workflow-context.yml` | 工作流节点上下文预算 | 4（既有键收敛，值未变） | `application-features.yml` |
| `06_app-runtime.yml` | 多实例运行标识 | 2（新增/修改） | `application.yml` |

合计：今日新增/修改 42 个键 + 收敛既有键 8 个。任务恢复（`TaskRecoveryService`）无可配置项，故不设片段；`wgh.sandbox.work-root`、`wgh.rag.memory.summary-model-id` 两个空值默认项保留在代码中。

变更对应的业务背景与逐项说明见 [1.2.0-change-summary-2026-11-19.md](../../../docs/version/1.2.0-change-summary-2026-11-19.md)。

## 2026-11-20 片段清单

| 文件 | 分区 | 键数 | 目标文件 |
| --- | --- | --- | --- |
| `01_async-executor.yml` | 统一异步执行器 | 7（新增） | `application.yml` |
| `02_scheduler.yml` | 统一调度中心 | 27（新增） | `application-features.yml` |
| `03_quota-refund.yml` | 配额退款补偿 | 7（新增） | `application-features.yml` |

说明：统一异步执行器替代 `new Thread(...).start()`，键前缀为 `wgh.async.executor`，绑定类为 `com.wgh.rag.common.config.RagAsyncExecutorProperties`。业务背景见 [1.3.0-change-summary-2026-11-20.md](../../../docs/version/1.3.0-change-summary-2026-11-20.md) 第 3.8 节。

统一调度中心键前缀为 `wgh.scheduler`，绑定类为 `com.wgh.rag.scheduler.config.SchedulerProperties`；启用前必须先执行 `sql/migrations/20261120/01_execution_job.sql`。业务背景见同一文档第 3.9 节。

配额退款补偿键前缀为 `wgh.rag.quota.charge`，绑定类为 `com.wgh.rag.quota.properties.QuotaProperties.Charge`；扣费流水位于聊天主链路，**部署本代码前**必须先执行 `sql/migrations/20261120/02_quota_charge.sql`（本轮只创建脚本，执行需我方批准）。业务背景见同一文档第 3.11 节。
