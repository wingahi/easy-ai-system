# 数据库迁移

> 本文是 [README](../../README.md) 快速开始中「已有库升级」的完整版。新库首次启动直接执行 `sql/schema/01-schema.sql` 与 `sql/seed/02-data.sql`（见 README 的本地启动）；本文只覆盖**已有库升级**与增量脚本状态。

## 执行顺序

已有库不要重跑 schema / seed。按版本顺序执行 `sql/migrations/` 下的增量脚本（资源可见性、2026-11-20 调度 / 配额流水、2026-11-22 `trace_id`、2026-11-24 多副本与计费纪元）：

```bash
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag_dev \
  -f /sql/migrations/20261113/01_resource_visibility.sql

docker exec -i postgres-with-vector psql -U postgres -d wgh_rag_dev \
  -f /sql/migrations/20261120/01_execution_job.sql

# wgh_quota_charge 位于聊天主链路：部署含扣费流水的版本前必须先执行
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag_dev \
  -f /sql/migrations/20261120/02_quota_charge.sql

# wgh_execution_job.trace_id（2026-11-22）：wgh_rag_dev 已执行两次验证幂等；
# 实测 wgh_rag_test 的 trace_id 列已存在，幂等重跑安全
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag_dev \
  -f /sql/migrations/20261122/01_execution_job_trace.sql

# 2026-11-24 两个脚本不纳入迁移 Job，部署前按库核对 / 补跑：
# wgh_rag_dev：usage_epoch 两列实测已存在；wgh_scheduler_instance 此前缺失，由 01 补建；
# wgh_rag_test：两处状态未核实，需先核对再决定是否执行
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag_dev \
  -f /sql/migrations/20261124/01_scheduler_instance.sql
# 02 的两列在 wgh_rag_dev 已存在（脚本幂等，可不执行）；wgh_rag_test 核对后再决定
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag_dev \
  -f /sql/migrations/20261124/02_quota_usage_epoch.sql
```

## 脚本状态

迁移脚本均为幂等（`IF NOT EXISTS`），只补表 / 列 / 索引与必要回填，不重建表。`wgh_execution_job`（含 `trace_id`）、`wgh_quota_charge`、`wgh_scheduler_instance` 与 `usage_epoch` 两列已登记到 `sql/schema/01-schema.sql`，新库无需重复执行增量脚本。

存量库按库核对：

- `wgh_rag_dev` 的 `usage_epoch` 两列已存在、`wgh_scheduler_instance` 正在补建；
- `wgh_rag_test` 两处状态未核实，部署前需先核对（见 [已知限制](known-limitations.md)）。

## 服务启用前置

- 调度中心启用前需先执行 `sql/migrations/20261120/01_execution_job.sql`；
- 配额扣费流水位于聊天主链路，部署对应版本前必须先执行 `sql/migrations/20261120/02_quota_charge.sql`；
- 多副本能力需确认 `sql/migrations/20261124/01_scheduler_instance.sql` 已执行（`wgh_rag_dev` 正在补建该表）；
- 计费纪元需确认 `sql/migrations/20261124/02_quota_usage_epoch.sql` 的列已存在（`wgh_rag_dev` 实测已存在，`wgh_rag_test` 未核实）；
- 2026-11-24 的两个脚本均不纳入迁移 Job。

增量配置片段见 `config/migrations/20261120/02_scheduler.yml`、`03_quota-refund.yml` 与 `config/migrations/20261124/02_scheduler-cluster.yml`、`05_chat-run-registry.yml`、`06_quota-count-policy.yml`。

## 相关限制

迁移相关的风险与未核实项见 [已知限制](known-limitations.md) 中的「`20261124` 迁移脚本」「调度作业 trace_id 迁移」「存量库批次 3 迁移」「资源可见性」条目。

---

[返回 README](../../README.md)
