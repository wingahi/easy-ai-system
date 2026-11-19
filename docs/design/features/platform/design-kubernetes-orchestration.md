# Kubernetes 级编排规划

> **版本**：v1.0
> **日期**：2026-11-19
> **性质**：规划文档（新增块）。不修改业务代码、不改配置、不构建镜像、不部署、不重启服务、不做 git 操作。
> **定位**：规划「Kubernetes 级编排」的现状差距、目标拓扑、分阶段路径（K0–K3）、改造点、风险与验收。
> **口径声明（重要）**：本文的「Kubernetes 级编排」指**应用与依赖的部署编排**（Deployment / Service / Ingress / HPA / PDB / 迁移 Job / 可观测接入）。**不包含**把业务任务调度迁移到 Kubernetes（不引入自研 Operator、不使用 K8s CronJob 替代应用内调度、不引入 XXL-Job/Temporal）。该取舍与 [design-distributed-tenant-scheduling.md](design-distributed-tenant-scheduling.md) §5.6「明确不推荐」一致。
> **依赖**：多副本能力依赖 [design-execution-scheduler-center.md](design-execution-scheduler-center.md) 的 S0，以及 [design-multi-tenancy-isolation-roadmap.md](design-multi-tenancy-isolation-roadmap.md) 的阶段 0/1。
> **分析方式**：核对 `docker-compose.yml`、Dockerfile、nginx、配置与 CI；未做集群实测。
> **未验证/推断项**：见 §9

---

## 0. 结论摘要

1. **现状是「单机 Compose」，已具备容器化基础但不具备编排能力**：后端/前端各有 Dockerfile、非 root 运行、管理端口独立、有 HEALTHCHECK；但没有 K8s 清单、没有 Helm/Kustomize、CI 只做编译与测试（不构建/不推送镜像）。
2. **三个「上集群即出事」的硬问题**：
   - **本地磁盘**：默认 `wgh.storage.type=local`、`base-dir=./uploads`，且 `docker-compose.yml` 的 backend **没有挂卷**（`#L46-L91`）——容器重建即丢文件，多副本互相读不到。
   - **日志写文件**：prod profile 写 `/var/log/wgh-rag`（`application-prod.yml#L80-L86`），容器内文件日志无法被集群日志采集；Pod 重建即丢。
   - **SSE 长连接**：聊天 30 分钟、多 Agent 10 分钟、工作流 30 分钟，且前端直连单实例；多副本后必须解决粘性或事件重放。
3. **多副本不是「加 replicas」**：CRON 每实例注册、连接器每实例调度、上传清理每实例执行（详见 A–D 文档），扩到 2 副本即 N 倍触发；因此 **K2 多副本必须以 S0 完成为前置**。
4. **数据库初始化方式与集群不兼容**：schema 只通过 `docker-entrypoint-initdb.d` 在「数据卷为空」时执行一次（`docker-compose.yml#L18-L20`），项目没有 Flyway/Liquibase。集群化必须改为**版本化迁移 Job**（按 `sql/migrations/<YYYYMMDD>/NN_*.sql` 顺序执行、幂等、可重入）。
5. **连接池会随副本数线性放大**：生产 `PROD_DB_POOL_MAX=50`（`application-prod.yml#L25`），4 副本 = 200 连接；上集群前必须重新规划池大小或引入 PgBouncer。
6. **推荐路线**：K0 容器基线（不引入编排）→ K1 单副本可编排（Deployment/Ingress/探针/迁移 Job/PVC 或 OSS）→ K2 多副本（前置 S0/A + 共享存储 + SSE 策略 + HPA）→ K3 生产级（GitOps、备份 PITR、灾备、安全策略、容量与成本）。
7. **明确不做**：不做 per-tenant 命名空间（租户隔离在应用层与数据库层完成）；不用 K8s CronJob 替代应用调度；不为「看起来云原生」提前上 Service Mesh。
8. **沙箱是独立风险面**：`rag-sandbox` 通过 `ProcessBuilder` 调用 `bwrap` 做隔离，依赖 Linux user namespace 等能力；在集群里必须单独规划（独立节点池 / 受限 SecurityContext / 默认关闭），不能与主应用同 Pod。

---

## 1. 现状评估

### 1.1 部署拓扑（Compose）

```
postgres(pgvector)  redis  backend(8080/9090)  frontend(80)  prometheus  grafana
      │               │         │                  │
      └───────────────┴─────────┘                  └─ nginx: /api → http://backend:8080
```

| 组件 | 现状 | 集群化差距 |
|---|---|---|
| backend | 单容器；`WGH_PROFILE=prod`；端口 8080 业务、9090 管理；无数据卷 | 无副本、无探针分组、无 HPA、无 PVC |
| frontend | nginx 静态托管；`proxy_pass http://backend:8080` 硬编码服务名 | Service 必须命名为 `backend`，或改为模板注入 |
| postgres | 单实例 `pgvector/pgvector:pg15`；初始化脚本挂载 | 无 HA、无备份、无迁移 Job |
| redis | 单实例，`appendonly yes`；应用可降级内存 | 无 Sentinel/Cluster；降级语义已在代码中声明 |
| prometheus/grafana | 静态配置 + provisioning | 需要 ServiceMonitor/ConfigMap 或保留独立部署 |
| 存储 | local `./uploads`（无卷） | 必须 OSS 或 PVC（ReadWriteMany） |
| 日志 | 文件 `/var/log/wgh-rag` | 必须 stdout 或 sidecar 采集 |

### 1.2 已有的可编排基础

| 能力 | 现状 | 结论 |
|---|---|---|
| 镜像 | 后端多阶段构建、JRE alpine、非 root（`Dockerfile#L14-L22`） | 可直接用于集群 |
| 健康检查 | `HEALTHCHECK` 打 9090 `/actuator/health`（`Dockerfile#L33-L34`） | 需拆 liveness/readiness/startup |
| 管理端口隔离 | 业务 8080 不暴露 actuator（`application.yml#L243-L249`） | 便于网络策略与抓取 |
| 配置外置 | 全量环境变量 + 可选 Nacos/外部 properties（`application-prod.yml#L14-L17`） | 易映射 ConfigMap/Secret |
| 优雅停机 | **未配置** `server.shutdown` | K1 必改 |
| 迁移 | 无 Flyway/Liquibase；`sql/migrations/<日期>/NN_*.sql` 手工执行 | 需要迁移 Job |
| CI | 只做规范检查、检索门禁、编译与测试（`.github/workflows/ci.yml`） | 缺镜像构建/推送/部署流水线 |
| 资源 | `JAVA_OPTS=-XX:MaxRAMPercentage=75.0 -XX:MaxMetaspaceSize=256m` | 必须配 limits，否则按节点内存计算堆 |
| 沙箱 | `ProcessBuilder` + `bwrap` 隔离 | 需要独立安全域 |

### 1.3 现有探针语义风险

`management.health.db.enabled=true` 且 Health 端点默认聚合 DB 检查：

- 若把 `/actuator/health` 直接当作 **liveness**，数据库短暂不可用会导致所有 Pod 被重启——这是典型的「级联故障放大」。
- 正确做法：启用 `management.endpoint.health.probes.enabled=true`，使用 `/actuator/health/liveness`（不查 DB）与 `/actuator/health/readiness`（查 DB/Redis/关键依赖），并配置 `startupProbe` 覆盖冷启动（现有 `start-period=60s` 可作参考）。

### 1.4 现状缺口清单

| ID | 缺口 | 影响 | 阶段 |
|---|---|---|---|
| K-G01 | 无 K8s 清单/Helm/Kustomize | 无法声明式部署与回滚 | K1 |
| K-G02 | 无 `server.shutdown=graceful` | 滚动发布丢在途请求 | K1 |
| K-G03 | 日志写文件 | 集群采集不到、重建即丢 | K1 |
| K-G04 | 本地存储无卷 | 重建丢文件、多副本不一致 | K1 |
| K-G05 | 无迁移 Job | 新环境/升级无法自动建表 | K1 |
| K-G06 | 探针未分组 | DB 抖动触发全量重启 | K1 |
| K-G07 | CI 不产出镜像 | 部署依赖本地构建 | K1 |
| K-G08 | 多副本重复触发（CRON/连接器/清理） | 成本翻倍、数据重复 | K2（依赖 S0） |
| K-G09 | SSE 无粘性/无事件重放 | 多副本断流、刷新丢进度 | K2 |
| K-G10 | 连接池按副本放大 | DB 连接耗尽 | K2 |
| K-G11 | 无 PDB/HPA | 滚动发布与扩缩容不可控 | K2 |
| K-G12 | 无备份/PITR | 灾难恢复不可承诺 | K3 |
| K-G13 | 沙箱隔离依赖宿主能力 | Pod 安全策略冲突 | K3 |

---

## 2. 目标架构

### 2.1 工作负载划分

| 工作负载 | 类型 | 副本 | 说明 |
|---|---|---|---|
| `wgh-backend` | Deployment | 1（K1）→ 2+（K2） | 无状态；会话/租约/状态全部在 DB 与 Redis |
| `wgh-frontend` | Deployment | 2 | nginx 静态资源；Service 名保持 `backend` 供代理 |
| `wgh-postgres` | 托管服务或 Operator（如 CloudNativePG） | 主 + 备 | 不手写裸 StatefulSet；必须支持 PITR |
| `wgh-redis` | 托管服务或 Sentinel/Cluster | ≥2 | 应用可降级内存，但多副本下限流会失真 |
| 向量库/搜索引擎（按需） | 独立工作负载 / 托管 | — | 见 [design-vector-store-and-search-engine.md](design-vector-store-and-search-engine.md) |
| `wgh-migrate` | Job（Helm pre-upgrade hook） | 1 | 幂等执行 `sql/migrations/**` |
| `wgh-sandbox` | 独立 Deployment + 独立节点池 | 0 或 1 | 默认关闭；受限 SecurityContext |
| 观测 | Prometheus Operator / 现有 compose | — | 优先 ServiceMonitor |

### 2.2 拓扑

```
Ingress (SSE 关闭缓冲、超时 3600s、body 210m)
   │
   ├──▶ Service frontend ──▶ Deployment frontend (nginx)
   │                              │ /api → http://backend:8080（Service 必须叫 backend）
   └──▶ Service backend ─────▶ Deployment backend (N 副本)
                                   │  ├── Service postgres
                                   │  ├── Service redis
                                   │  └── Service search/vector（可选）
                                   │
                              Job wgh-migrate（升级前）
                              PVC（仅 local 存储）或 OSS（推荐）
```

### 2.3 关键设计约定

| 项 | 约定 |
|---|---|
| 探针 | `startupProbe` → `/actuator/health/readiness`；`livenessProbe` → `/actuator/health/liveness`；`readinessProbe` → `/actuator/health/readiness` |
| 优雅停机 | `server.shutdown=graceful`；`terminationGracePeriodSeconds` ≥ 45s（大于 drain 时间）；`preStop: sleep 5` 让 Endpoint 摘除先行 |
| 滚动发布 | `maxUnavailable: 0` / `maxSurge: 1`；PDB `minAvailable: N-1` |
| 资源 | requests = limits（CPU 可弹性）；堆由 `MaxRAMPercentage=75` 决定，Metaspace 256m 固定 |
| 配置 | ConfigMap 存非敏感；Secret 存 `POSTGRES_PASSWORD`、`WGH_AUTH_LOCAL_JWT_SECRET`、`WGH_INIT_ADMIN_PASSWORD`、OIDC/OSS 凭据 |
| 存储 | 首选 `WGH_STORAGE_TYPE=oss`；若必须 local，PVC 需 `ReadWriteMany`（多副本） |
| 日志 | 改为 stdout（logback 增加 console appender）或 sidecar；保留文件时挂 emptyDir 仅供排障 |
| 网络 | NetworkPolicy 默认拒绝：Ingress→frontend、frontend→backend、backend→PG/Redis/搜索、backend→外部模型与 OSS |
| 安全 | `runAsNonRoot`、`readOnlyRootFilesystem`（需挂载 `/tmp` 与日志目录）、`seccompProfile: RuntimeDefault`、`drop: ALL` |
| HPA | 仅在 K2 且 S0 完成后启用；初始按 CPU，后续可接 `wgh.scheduler.queued` 自定义指标 |
| 命名空间 | 平台命名空间（如 `wgh`）；**不按租户建命名空间** |
| 标签 | `app.kubernetes.io/name/component/instance`、`wgh.io/component`；不打租户标签 |

---

## 3. 分阶段落地路径（K0–K3）

### 3.1 K0：容器基线（不引入编排）

**目标**：让镜像本身「云原生就绪」，不改变应用架构。

**改动**
1. 日志改 stdout（保留文件输出为可选 profile）。
2. 补 `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase`。
3. 启用 health probes 分组；确认 liveness 不包含 DB。
4. 镜像补齐：`tini` 或确认 PID1 信号处理（当前 `exec java` 已可接收 SIGTERM）；固定基础镜像 digest。
5. 镜像构建/推送进入 CI（多架构可选），产物与 commit 关联。
6. 配置项盘点：输出「环境变量 → ConfigMap/Secret」映射表（见 §4）。

**验收**：本地 Compose 下 `docker stop` 能在 45s 内完成排水；日志可被 `docker logs` 采集；健康端点返回 liveness/readiness 两组。

**风险**：日志改 stdout 后文件轮转策略失效，需要采集侧保留策略。

### 3.2 K1：单副本可编排

**目标**：用 K8s 替换 Compose 跑通单副本，具备声明式部署、探针与迁移能力。

**改动**
1. Kustomize/Helm 清单：Namespace、Deployment（backend/frontend）、Service、Ingress、ConfigMap、Secret、ServiceAccount。
2. 迁移 Job（Helm pre-install/pre-upgrade hook）：按 `sql/migrations/<日期>/NN_*.sql` 顺序执行；失败即阻断发布。
3. 存储：切换 `WGH_STORAGE_TYPE=oss`（推荐）或挂 PVC。
4. Ingress 注解：`proxy-buffering: "off"`、`proxy-read-timeout: "3600"`、`proxy-body-size: "210m"`（对齐 `rag-frontend/nginx.conf#L8-L26`）。
5. 观测：ServiceMonitor 抓 9090；Grafana 看板以 ConfigMap 交付。
6. 安全：`runAsNonRoot`、只读根文件系统、默认拒绝 NetworkPolicy。

**验收**
- 新集群从零可一键部署（含建表与种子数据）。
- Pod 重建后文件不丢（OSS 或 PVC）。
- 滚动重启期间无 5xx（单副本时允许短暂不可用，需明确 SLA）。
- 迁移 Job 重复执行幂等。

**风险**：迁移脚本非幂等会导致升级失败；必须逐个核对 `CREATE ... IF NOT EXISTS`。

### 3.3 K2：多副本

**前置条件（硬门槛）**
1. S0 完成：受管池、有界队列、优雅停机、CRON/连接器/清理单飞。
2. A 阶段完成：越权修复、执行者租约、启动对账。
3. 共享存储就绪（OSS 或 RWX PVC）。
4. SSE 策略确定（粘性会话或事件重放）。

**改动**
1. `replicas: 2+`；PDB；`maxUnavailable: 0`。
2. Ingress 会话亲和（`session-cookie` 或按 header 哈希）作为过渡；S3 后以事件重放为主。
3. HPA（CPU 或队列深度）；注意 SSE 长连接不会被快速释放，扩缩容要保守。
4. 连接池重算：`PROD_DB_POOL_MAX` × 副本数 ≤ PG `max_connections` 的 70%；超出时引入 PgBouncer。
5. Redis 高可用或接受降级（需在管理端显示 `store=memory`）。
6. JWKS 缓存、工具熔断、黑板的跨实例语义明确（见 A–D 文档 §2.1）。

**验收**
- 2 副本下同一 CRON/连接器/作业只执行一次。
- 滚动发布零 5xx；SSE 断线可在 5s 内重连并恢复进度。
- HPA 2→4 扩容后租户并发仍符合策略（集群口径）。
- 单 Pod kill 后 ≤90s 无悬挂 RUNNING。

**风险**：没有 S0 就上多副本会直接放大重复执行与成本；连接池与 DB 上限冲突。

### 3.4 K3：生产级

**改动**：GitOps（Argo CD/Flux）；多环境（dev/staging/prod）分离；PG 备份 + PITR 演练；跨可用区部署；灾备切换手册；成本与容量看板；沙箱独立节点池与镜像白名单；密钥轮换；审计与合规（镜像扫描、SBOM、准入策略）。

**验收**：RTO/RPO 达到承诺；镜像无高危漏洞；密钥可轮换且不重启业务；租户导出/删除在集群环境可执行。

---

## 4. 与现有模块的改造点

### 4.1 代码与配置改造

| 模块/文件 | 改造 | 阶段 |
|---|---|---|
| `application.yml` / `application-prod.yml` | `server.shutdown`、`spring.lifecycle.timeout-per-shutdown-phase`、health probes 分组 | K0 |
| `logback-spring.xml` | 增加 console appender（默认启用）、文件 appender 改为可选 | K0 |
| `rag-app` | 启动自检（配置缺失快速失败）、就绪状态暴露（DB/Redis/搜索依赖） | K0/K1 |
| `rag-storage` | 多副本下强制 OSS 或校验共享卷；启动时探测写入能力 | K1 |
| `rag-frontend`（`nginx.conf`） | 后端地址改为可注入（envsubst 模板），避免硬编码 `backend` | K1 |
| `rag-scheduler` | 实例 ID 从 `HOSTNAME` 注入；租约/单飞与副本数无关 | K2（依赖 S0） |
| `rag-chat` / `rag-workflow` / `rag-agent-multi` | SSE 事件的 `Last-Event-ID` 支持（S3）或状态轮询兜底 | K2/K3 |
| `rag-kb`（迁移相关） | 迁移脚本幂等化；启动时不再依赖 entrypoint 初始化 | K1 |
| `rag-sandbox` | 运行时探测 `bwrap` 可用性；不可用时明确禁用并告警 | K3 |
| `.github/workflows/ci.yml` | 增加镜像构建、扫描、推送、清单渲染校验 | K0/K1 |

### 4.2 配置映射（节选）

| 环境变量 | 来源 | 敏感 |
|---|---|---|
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | Secret | 是 |
| `PROD_DB_POOL_MAX` | ConfigMap | 否 |
| `WGH_REDIS_*` | ConfigMap + Secret（密码） | 部分 |
| `WGH_AUTH_LOCAL_JWT_SECRET` / `WGH_JWT_SECRET` | Secret | 是 |
| `WGH_INIT_ADMIN_PASSWORD` | Secret | 是 |
| `WGH_OIDC_ISSUER/AUDIENCE/JWKS_URI` | ConfigMap | 否 |
| `WGH_STORAGE_TYPE` / `WGH_OSS_*` | ConfigMap + Secret | 部分 |
| `WGH_INSTANCE_ID` | Downward API（`metadata.name`） | 否 |
| `JAVA_OPTS` | ConfigMap | 否 |
| `WGH_MANAGEMENT_PORT` | ConfigMap（固定 9090） | 否 |

---

## 5. 风险与不做的代价

### 5.1 主要风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 无 S0 直接多副本 | CRON/连接器/作业重复执行，成本翻倍 | K2 硬门槛；灰度 1→2 副本并观察指标 |
| 本地存储 | Pod 重建丢文件、副本间不一致 | 强制 OSS；或 RWX PVC + 启动写探测 |
| 连接池放大 | PG 连接耗尽，全站不可用 | 重算池大小；PgBouncer；压测 |
| 探针误用 | DB 抖动触发全量重启 | liveness/readiness 分离；startupProbe |
| SSE 断流 | 用户刷新丢进度 | 粘性会话过渡 + 事件重放；状态轮询兜底 |
| 迁移 Job 非幂等 | 升级失败、半迁移状态 | 幂等校验 + 事务 + 备份 |
| 沙箱要求特权 | Pod 被拒绝或安全策略冲突 | 独立节点池；默认关闭；显式能力清单 |
| 日志/指标缺失 | 故障无法定位 | stdout + ServiceMonitor + 统一标签 |

### 5.2 不做的代价

| 不做 | 代价 |
|---|---|
| 不做 K0 | 镜像无法优雅停机，任何编排都会丢在途请求 |
| 不做 K1 | 依赖人工 Compose，环境不可复制，升级不可回滚 |
| 不做 K2 | 单副本容量与可用性上限固定，发布即中断 |
| 不做 K3 | 无备份/PITR，无法承诺 RTO/RPO；无审计与合规证据 |

---

## 6. 验收标准与测试场景

### 6.1 验收标准

| 编号 | 标准 | 阶段 |
|---|---|---|
| K-A1 | 新集群从零部署成功，含建表与迁移 Job，重复执行幂等 | K1 |
| K-A2 | Pod 重建后上传文件仍可访问（OSS 或 PVC） | K1 |
| K-A3 | 滚动发布零 5xx；SIGTERM 后在途请求完成或明确失败 | K1/K2 |
| K-A4 | liveness 不依赖 DB；DB 抖动不触发 Pod 重启 | K1 |
| K-A5 | 2 副本下 CRON/连接器/作业只执行一次 | K2 |
| K-A6 | SSE 断线 5s 内可恢复（粘性或重放） | K2 |
| K-A7 | HPA 扩容后租户并发仍为集群口径 | K2 |
| K-A8 | 连接池总量 ≤ PG 上限 70% | K2 |
| K-A9 | 备份可恢复，RTO/RPO 达标 | K3 |
| K-A10 | 镜像扫描无高危；Secret 不出现在镜像/ConfigMap | K3 |

### 6.2 测试场景

| 编号 | 场景 | 期望 |
|---|---|---|
| K-T1 | 单 Pod `kubectl delete pod` | 30s 内重建；无 5xx；在途任务 ≤90s 收敛 |
| K-T2 | 滚动升级 2→2 | 全程可用；SSE 自动重连 |
| K-T3 | HPA 2→4（压测流量） | 扩容后 P95 改善；无重复任务 |
| K-T4 | 迁移 Job 重复执行 | 无报错、无重复数据 |
| K-T5 | DB 短暂不可用 30s | readiness 变红、liveness 不变；恢复后自动就绪 |
| K-T6 | Redis 宕机 | 限流降级、管理端可见；聊天不中断 |
| K-T7 | 文件上传后重建 Pod | 文件仍可读 |
| K-T8 | 从备份恢复到新集群 | 数据完整；索引可重建 |
| K-T9 | 沙箱不可用 | 应用启动成功、功能显式禁用并告警 |
| K-T10 | 网络策略生效 | 仅允许的调用链可达 |

---

## 7. 与其它三块的衔接

| 块 | 衔接点 |
|---|---|
| 任务调度中心 | K2 的硬门槛是 S0；HPA 与调度队列指标联动；迁移/回填作业走 BACKGROUND |
| 多租户 | 阶段 0 完成前禁止多副本；阶段 1 完成前不做租户级资源配额；不按租户建命名空间 |
| 向量库/搜索引擎 | 作为独立工作负载部署；使用托管服务时以 Service/Secret 接入；容量与 PITR 要求写入 §3.4 |

---

## 8. 时间与成本说明

本文不承诺工期。分阶段的原因是可独立回滚：K0/K1 不改变应用架构，风险最低；K2 才触及并发语义；K3 是运营能力。任何阶段都可以停在当前形态继续使用 Compose。

---

## 9. 未验证与推断声明

1. **未做集群实测**：所有探针、滚动发布、HPA 行为基于 Kubernetes 通用语义，未在本项目验证。
2. **生产副本数与流量未知**：仓库只有单副本 Compose。
3. **DB 连接上限未知**：`PROD_DB_POOL_MAX=50` 为应用侧配置；PG `max_connections` 未在仓库中确认。
4. **SSE 粘性方案未选型**：Ingress 控制器能力未核对（是否存在、是否支持 cookie 亲和）。
5. **沙箱权限需求未实测**：`bwrap` 在容器内所需 capability 未逐项验证。
6. **迁移脚本幂等性未逐一核对**：仅确认脚本存在，未全部执行验证。
7. **成本估算缺失**：未给出集群与托管服务的费用模型。

---

## 附录 A：证据索引

| 结论 | 证据 |
|---|---|
| Compose 单副本与无卷 | `docker-compose.yml#L46-L104` |
| 管理端口与健康暴露 | `rag-backend/rag-app/src/main/resources/application.yml#L243-L249` |
| 生产日志路径 | `rag-backend/rag-app/src/main/resources/application-prod.yml#L80-L86` |
| 生产连接池 50 | `application-prod.yml#L23-L32` |
| 本地存储默认 | `application.yml#L116-L134` |
| 后端镜像与健康检查 | `rag-backend/Dockerfile#L12-L36` |
| 前端代理与 SSE 参数 | `rag-frontend/nginx.conf#L8-L26` |
| 迁移脚本目录 | `rag-backend/sql/migrations/20261113`、`20261118` |
| 初始化只在空卷执行 | `docker-compose.yml#L18-L20` |
| CI 仅编译测试 | `.github/workflows/ci.yml#L52-L79` |
| 沙箱进程与隔离 | `rag-backend/rag-sandbox/.../exec/SandboxProcessRunner.java#L17-L37`、`.../isolation/BwrapIsolation.java` |
| 多副本重复触发 | `docs/design/features/platform/design-distributed-tenant-scheduling.md` §2.2 |
