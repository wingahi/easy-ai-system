# 观测栈：Prometheus + Grafana

独立可运行的观测栈，复用既有业务容器（`wgh-rag-postgres`、`wgh-rag-redis`、后端），
不重建、不重启、不改业务代码。所有路径均为相对仓库根目录的相对路径。

## 1. 组成

| 组件 | 容器名 | 宿主机端口 | 说明 |
| --- | --- | --- | --- |
| Prometheus | `wgh-observability-prometheus` | `127.0.0.1:9091` | 抓取后端与调度作业指标，保留 15 天 |
| Grafana | `wgh-observability-grafana` | `127.0.0.1:3000` | 数据源与面板自动加载 |
| postgres-exporter | `wgh-observability-pg-exporter` | `127.0.0.1:19187` | 只读导出 `wgh_execution_job` 状态 |

文件：

- `deploy/observability/docker-compose.yml`：观测栈编排（独立项目名与容器名）
- `deploy/observability/postgres-exporter/queries.yml`：调度作业状态查询
- `deploy/prometheus/prometheus.yml`：抓取配置（多实例 DNS 发现、job 标签）
- `deploy/prometheus/rules/wgh-rag-alerts.yml`：告警规则
- `deploy/grafana/provisioning/`：数据源与 dashboard provider
- `deploy/grafana/dashboards/wgh-rag-overview.json`：业务总览（含调度/配额/存储/实例）
- `deploy/grafana/dashboards/wgh-rag-runtime.json`：JVM / HTTP / 实例运行时

## 2. 启动与停止

前置：既有业务容器已在运行，且其网络为 `wgh-rag-system_default`（可用
`WGH_EXISTING_NETWORK` 覆盖）。凭据来自环境变量，`.env` 不纳入版本管理。

```bash
# 启动（在仓库根目录执行；--env-file 提供 POSTGRES_* 与 Grafana 管理员账号）
docker compose --env-file .env -f deploy/observability/docker-compose.yml up -d

# 仅停止观测栈，不影响 postgres/redis/后端
docker compose --env-file .env -f deploy/observability/docker-compose.yml down
```

可覆盖的环境变量：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `WGH_PROMETHEUS_PORT` | `9091` | Prometheus 宿主端口 |
| `WGH_GRAFANA_PORT` | `3000` | Grafana 宿主端口 |
| `WGH_PG_EXPORTER_PORT` | `19187` | 作业导出器宿主端口 |
| `WGH_PROMETHEUS_RETENTION` | `15d` | TSDB 保留周期 |
| `WGH_EXISTING_NETWORK` | `wgh-rag-system_default` | 既有业务容器网络 |
| `WGH_GRAFANA_ADMIN_USER` / `WGH_GRAFANA_ADMIN_PASSWORD` | `admin` / `admin` | Grafana 管理员，生产必须覆盖 |

> 现有根目录 `docker-compose.yml` 里也定义了 `prometheus`/`grafana` 服务（端口同为 9091/3000）。
> 两者不要同时启动；本仓库推荐使用本文的独立编排。

## 3. 抓取配置要点

- 后端：`dns_sd_configs` 解析服务名 `backend`，端口 `9090`，路径 `/actuator/prometheus`。
  多副本时同一 DNS 名解析出多个 A 记录，Prometheus 逐实例抓取，`instance` 标签天然区分副本；
  另加 `service=wgh-rag-backend`、`env=local` 标签。
- 调度作业：`postgres-exporter:9187` 只读查询 `wgh_execution_job`，生成
  `wgh_execution_job_count{status,lane}` 与 `wgh_execution_job_oldest_age_seconds{status,lane}`。
- 自监控：Prometheus 抓取自身，便于验证抓取链路。
- 保留周期：`--storage.tsdb.retention.time=15d`（见 compose，可用变量覆盖）。

## 4. 后端 actuator 现状（无需改业务代码）

- 管理端口：`9090`（`management.server.port=${WGH_MANAGEMENT_PORT:9090}`，监听 `0.0.0.0`）。
- Prometheus 端点：`GET /actuator/prometheus`。
- 暴露列表已包含 `health,info,metrics,prometheus`。
- 公共指标标签：`application`、`wgh.instance`（来自 `WGH_INSTANCE_ID`，未设置时用容器 `HOSTNAME`）。
- 建议：多副本部署时为每个实例显式设置唯一 `WGH_INSTANCE_ID`，便于面板按实例聚合；
  管理端口不要映射到宿主机，只允许 Prometheus 在容器网络内访问。

## 5. 告警规则

文件：`deploy/prometheus/rules/wgh-rag-alerts.yml`，共 5 组 12 条。

| 告警 | 触发条件（摘要） | 级别 |
| --- | --- | --- |
| `WghBackendInstanceDown` | `up{job="wgh-rag-backend"} == 0` 持续 2m | critical |
| `WghBackendNoTargets` | 5m 内没有任何后端抓取目标 | critical |
| `WghStateStoreDegraded` | 15m 内出现共享状态降级 | warning |
| `WghSchedulerJobsPendingHigh` | 单车道 PENDING > 50 持续 10m | warning |
| `WghSchedulerPendingTooOld` | 最老 PENDING 等待 > 10m 持续 5m | warning |
| `WghSchedulerDeadLetter` | 1h 内 DEAD 作业数增加 | critical |
| `WghWorkflowExecutorQueueSaturated` | `workflowExecutor` 排队 > 50 持续 5m | warning |
| `WghQuotaRefundFailed` | 10m 内 `result="failed"` 增加 | critical |
| `WghStorageOrphanGrowth` | 1h 内 `action="detected"` 增加 | warning |
| `WghStorageMissingReference` | 1h 内 `action="missing"` 增加 | critical |
| `WghStorageOrphanDeleteFailed` | 15m 内 `action="delete_failed"` 增加 | warning |
| `WghHttp5xxRatioHigh` | 5m 内 5xx 占比 > 5% | critical |

## 6. 面板覆盖

`WGH RAG 业务总览`（28 个面板）：

- 调度队列与作业状态：作业状态分布、车道/状态、最老等待时长、`workflowExecutor` 队列与活跃线程
- 配额退款结果分布：退款结果占比（饼图）、速率、失败计数
- 存储孤儿与缺失引用：`detected`/`missing` 计数、处理动作速率
- 实例级状态：在线实例数、共享状态降级、按实例抓取状态
- 既有业务指标：对话、检索、LLM、工作流

`WGH RAG 运行时与实例`（15 个面板）：

- JVM：堆内存、GC 暂停、线程状态、CPU
- HTTP：请求速率按状态、P95 按 URI、5xx 比例与速率、Hikari 连接池
- 实例：抓取目标表、JVM 运行时信息、抓取耗时

## 7. 本次实际验证（2026-11-24）

验证方式：临时启动 2 个后端实例（`wgh-backend-obs-tmp`、`wgh-backend-obs-tmp-2`，
独立名称、管理端口 19090/19091，复用既有 postgres/redis），验证后可按需删除。

```bash
# 1) 目标状态：3 个 job 全部 UP，后端 2 个实例
curl -s http://127.0.0.1:9091/api/v1/targets
#   prometheus                    up
#   wgh-rag-backend               up（172.19.0.4:9090）
#   wgh-rag-backend               up（172.19.0.7:9090）
#   wgh-rag-postgres-exporter     up

# 2) 规则加载：5 组 12 条，均为 inactive（无触发）
curl -s http://127.0.0.1:9091/api/v1/rules

# 3) 关键指标可查
#   sum(jvm_memory_used_bytes{area="heap"}) = 121601384
#   sum(rate(http_server_requests_seconds_count{job="wgh-rag-backend"}[5m])) = 0.0123
#   count(jvm_info{job="wgh-rag-backend"}) = 2
#   sum by (status) (wgh_execution_job_count) = {SUCCEEDED: 654}

# 4) Grafana 数据源健康
curl -s -u "$GRAFANA_USER:$GRAFANA_PASSWORD" \
  http://127.0.0.1:3000/api/datasources/uid/wgh-prometheus/health
#   {"status":"OK","message":"Successfully queried the Prometheus API."}

# 5) 面板可打开
curl -s -u "$GRAFANA_USER:$GRAFANA_PASSWORD" \
  http://127.0.0.1:3000/api/dashboards/uid/wgh-rag-overview   # 200，28 panels
curl -s -u "$GRAFANA_USER:$GRAFANA_PASSWORD" \
  http://127.0.0.1:3000/api/dashboards/uid/wgh-rag-runtime    # 200，15 panels
```

配置语法校验：

```bash
docker run --rm -v "$PWD/deploy/prometheus:/etc/prometheus:ro" \
  --entrypoint /bin/promtool prom/prometheus:v2.55.1 \
  check config /etc/prometheus/prometheus.yml
# SUCCESS: 1 rule files found；config valid
```

## 8. 已知限制与后续建议

- `wgh.*` 为惰性计数器：对应业务事件发生后才出现序列，面板初始为空属正常现象。
- 调度车道（`LaneExecutors` 的 3 个裸线程池）未注册 Micrometer Gauge，因此只有
  `workflowExecutor`/`workflowTriggerScheduler` 的队列指标；作业状态改由 Postgres 导出器覆盖。
  若需要车道级队列深度，建议应用侧为 `LaneExecutors` 注册 Gauge（本次按边界未改业务代码）。
- postgres-exporter 使用 `--extend.query-path`（上游已标记弃用，0.21.1 仍可用）；
  升级到大版本时需迁移到新的查询配置格式。
- 告警仅定义规则，未接入 Alertmanager；需要通知渠道时再增加 Alertmanager 与 receiver。
- 临时验证后端可清理：
  `docker rm -f wgh-backend-obs-tmp wgh-backend-obs-tmp-2`
