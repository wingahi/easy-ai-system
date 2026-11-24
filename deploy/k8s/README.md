# Kubernetes 级编排清单与部署说明

> 对应设计：[design-kubernetes-orchestration.md](../../docs/design/features/platform/design-kubernetes-orchestration.md)
> 范围：只覆盖应用与依赖的**部署编排**，不把应用内任务调度迁移到 Kubernetes。
> 本文路径均相对仓库根目录。

## 1. 阶段判断（本次落地到哪一步）

| 阶段        | 状态                   | 说明                                                                                                                                                                                                                               |
| --------- | -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| K0 容器基线   | **部分完成**             | 优雅停机与 `management.endpoint.health.probes` 分组已落地，探针使用真实分组路径；文件日志仍写本地盘，集群采集走 stdout 尚未改造。                                                                                                                                          |
| K1 单副本可编排 | **已产出，可直接使用**        | Deployment/Service/Ingress/ConfigMap/Secret 模板/PVC/迁移 Job；探针、资源、滚动更新、SSE 参数已落地。                                                                                                                                                  |
| K2 多副本    | **清单已启用（默认 OSS 存储）** | `overlays/k2-multi` 已是可用配置：2 副本、HPA、PDB、连接池 25、`WGH_STORAGE_TYPE=oss` + `mode=shared`、过渡期回落读、SSE Cookie 亲和。前置能力已在代码层落地，shared 存储开关已绑定（见 §5.1）；尚未做集群实测。                                                                           |
| K3 生产级    | **清单已产出（未集群实测）**     | `overlays/k3-prod` 在 K2 之上补：3 副本 + HPA 3–8、跨可用区打散与反亲和、PDB 2、ResourceQuota/LimitRange、默认拒绝 NetworkPolicy、Pod 注解抓取 + 告警规则 ConfigMap、TLS、镜像 digest；`optional/` 增加对象存储备份、恢复演练与 Secret 外置示例。GitOps、准入策略、镜像扫描/ SBOM 不在本次清单范围，见 §10.10。 |

结论：**K1、K2 与 K3 清单都可用于部署**，差异在副本语义与运维边界：

- K1：单副本 + 本地 PVC，适用于小规模或验证环境；
- K2：多副本 + OSS 主存储，用于需要滚动不中断与横向扩容的环境；
- K3：在 K2 之上加生产加固（3 副本/HPA 3–8、打散与 PDB、配额、网络策略、TLS、digest、备份与恢复演练），
  适用于需要可用性/安全/恢复承诺的环境，详见 §10。

K2/K3 上线前须按 §4.7 注入 OSS 密钥、替换 endpoint/bucket 占位值，并确认应用侧共享存储配置已生效（见 §5.1）。

### 1.1 探针语义

后端探针使用管理端口 9090 的真实分组端点（应用侧配置见 `rag-backend/rag-app/src/main/resources/application.yml` 的 `management.endpoint.health` 段）：

| 探针               | 路径                           | 聚合内容                | 行为                                    |
| ---------------- | ---------------------------- | ------------------- | ------------------------------------- |
| `startupProbe`   | `/actuator/health/readiness` | readinessState + db | 启动完成且 DB 可用前不放行后续探针（最多等待 180s）        |
| `readinessProbe` | `/actuator/health/readiness` | readinessState + db | 启动未完成或 DB 不可用时返回 503，Endpoint 摘除该 Pod |
| `livenessProbe`  | `/actuator/health/liveness`  | livenessState       | 只反映进程自身活性，不查 DB/Redis，依赖抖动不会触发重启      |

Redis 可降级（限流、密码锁定、取消标记回退单机内存），磁盘余量不足只影响上传，二者均不纳入 readiness，避免非关键依赖导致整体摘流。聚合端点 `/actuator/health` 仍会因 Redis 不可用返回 503，因此探针不使用该路径。

前端探针仍为 nginx 的 `GET /`，属于该镜像的真实就绪语义，不做分组改造。

## 2. 目录结构

```
deploy/k8s/
├── README.md                       # 本文件
├── base/                           # 通用清单（单副本 + 本地 PVC，最小可运行）
│   ├── kustomization.yaml          # 资源列表 + SQL 打包（configMapGenerator）
│   ├── namespace.yaml              # 平台命名空间 wgh
│   ├── serviceaccount.yaml
│   ├── configmap-backend.yaml      # 非敏感配置
│   ├── secret.example.yaml         # 敏感配置模板（刻意不纳入 kustomization）
│   ├── pvc-uploads.yaml            # 上传卷（K1 本地存储；K2 过渡期回落读/聊天图片）
│   ├── services-external.yaml      # 外部 PG/Redis 的 ExternalName Service
│   ├── deployment-backend.yaml
│   ├── service-backend.yaml        # 名称固定为 backend（前端 nginx 硬编码）
│   ├── deployment-frontend.yaml
│   ├── service-frontend.yaml
│   ├── ingress.yaml                # SSE 超时/缓冲/包体参数
│   ├── job-migrate.yaml            # 迁移 Job
│   └── migrate/run-migrations.sh   # 迁移执行脚本（按序执行 sql/ 下的脚本）
├── overlays/
│   ├── k1-single/                  # K1：镜像、域名
│   ├── k2-multi/                   # K2：2 副本、HPA、PDB、连接池 25、OSS 共享存储、SSE 亲和
│   └── k3-prod/                    # K3：以 k2-multi 为基线 + 生产加固（见 §10）
│       ├── kustomization.yaml      # 副本/HPA/PDB/打散/池/存储/TLS/digest/规则 ConfigMap
│       ├── networkpolicy.yaml      # 默认拒绝 + 8 条最小放行
│       └── quota-limitrange.yaml   # ResourceQuota + LimitRange
└── optional/
    ├── cronjob-db-backup.yaml      # 每日逻辑备份（落集群内 PVC）
    ├── cronjob-db-backup-oss.yaml  # 每日逻辑备份（直传 OSS，推荐）
    ├── cronjob-restore-drill.yaml  # 恢复演练（默认 suspend）
    ├── restore/restore-drill.sh    # 演练脚本（临时库 + 校验）
    └── sealedsecret.example.yaml   # Secret 外置模板（不参与渲染）
```

SQL 不复制到 `deploy/k8s/`：`base/kustomization.yaml` 通过 `configMapGenerator` 直接引用 `rag-backend/sql/**`，保持单一真相。

## 3. 前置条件

| 项              | 要求                                   | 备注                                                  |
| -------------- | ------------------------------------ | --------------------------------------------------- |
| Kubernetes     | 1.27+（CronJob 使用 `timeZone`）         | 已在 1.30 schema 下校验                                  |
| Ingress        | nginx ingress controller             | 注解与 `rag-frontend/nginx.conf` 对齐                    |
| StorageClass   | 默认 SC 可供给 RWO；K2 的上传卷需 RWX           | OSS 为主存储时不依赖 SC；回落读与聊天图片仍需共享卷                       |
| 外部 PostgreSQL  | 15+，安装 `vector`、`pg_trgm`            | 连接串含 `stringtype=unspecified`                       |
| 外部 Redis       | 单实例可接受，多副本必须共享同一实例                   | 应用可降级内存，但限流会失真；SSE 事件重放也依赖它                         |
| OSS            | bucket + endpoint + AK/SK（K2 必填）     | shared 自检缺项即启动失败，见 §5.1                             |
| 镜像             | 后端/前端镜像已推送并可拉取                       | 仓库 CI 目前不构建镜像（K-G07），需先补流水线或手工推送                    |
| 密钥             | 已创建 `wgh-backend-secrets`            | 缺 `WGH_JWT_SECRET` 时 prod profile 启动失败              |
| metrics-server | 仅 K2/K3 需要                           | HPA 依赖                                              |
| CNI            | 支持 NetworkPolicy（Calico/Cilium 等）    | K3 默认拒绝；不支持时策略静默失效                                  |
| 监控             | Prometheus 在 `monitoring` 命名空间       | K3 用 Pod 注解抓取，需在抓取配置加 relabel，见 §10.6               |
| 入口控制器          | nginx ingress 在 `ingress-nginx` 命名空间 | K3 NetworkPolicy 按 `kubernetes.io/metadata.name` 选择 |
| TLS            | Ingress TLS Secret `wgh-tls`         | K3 开启强制 HTTPS，缺失时 Ingress 不生效                       |

## 4. 部署步骤（K1）

### 4.1 创建命名空间与 Secret

```sh
kubectl create namespace wgh --dry-run=client -o yaml | kubectl apply -f -

kubectl -n wgh create secret generic wgh-backend-secrets \
  --from-literal=SPRING_DATASOURCE_PASSWORD='<db-password>' \
  --from-literal=WGH_JWT_SECRET='<32+ chars>' \
  --from-literal=WGH_AUTH_LOCAL_JWT_SECRET='<32+ chars>' \
  --from-literal=WGH_INIT_ADMIN_PASSWORD='<initial-admin-password>'
```

需要 OSS 时追加 `--from-literal=WGH_OSS_ACCESS_KEY_ID=... --from-literal=WGH_OSS_ACCESS_KEY_SECRET=...`；Redis 有密码时追加 `WGH_REDIS_PASSWORD`。完整键位见 [secret.example.yaml](base/secret.example.yaml)。

### 4.2 修改环境相关值

- [overlays/k1-single/kustomization.yaml](overlays/k1-single/kustomization.yaml)：镜像仓库与 tag、Ingress 域名。
- [base/services-external.yaml](base/services-external.yaml)：把 `postgres.example.internal` / `redis.example.internal` 换成真实域名。
- 如数据库名/用户名不是 `wgh_rag_prod`/`postgres`（base 默认正式库；K1 / K2 overlay 分别覆盖为 `wgh_rag_dev` / `wgh_rag_test`），改 [base/configmap-backend.yaml](base/configmap-backend.yaml) 与迁移 Job 的环境变量。

### 4.3 渲染与校验

```sh
kubectl kustomize --load-restrictor LoadRestrictionsNone deploy/k8s/overlays/k1-single > /tmp/wgh-k1.yaml
kubeconform -strict -summary -kubernetes-version 1.30.0 /tmp/wgh-k1.yaml
```

必须带 `--load-restrictor LoadRestrictionsNone`，否则 kustomize 不允许读取 `base/` 之外的 SQL 文件。
注意：`kubectl apply -k` 不支持该参数，因此部署与卸载都基于渲染后的文件。

### 4.4 先迁移、后启动业务

```sh
# 1) 只应用迁移 Job 及其 SQL ConfigMap（二者都带 component=migrate 标签）
kubectl apply -f /tmp/wgh-k1.yaml -l app.kubernetes.io/component=migrate

# 2) 等待迁移完成（失败即停止，检查日志后再继续）
kubectl -n wgh wait --for=condition=complete job/wgh-migrate --timeout=10m
kubectl -n wgh logs job/wgh-migrate --tail=50

# 3) 应用全部资源
kubectl apply -f /tmp/wgh-k1.yaml

# 4) 观察
kubectl -n wgh get pods,svc,ingress
kubectl -n wgh rollout status deploy/wgh-backend --timeout=5m
kubectl -n wgh rollout status deploy/wgh-frontend --timeout=2m
```

重复部署/升级时 Job 的 Pod 模板不可变，先删除：

```sh
kubectl -n wgh delete job wgh-migrate --ignore-not-found
```

### 4.5 冒烟检查

```sh
# Pod 内健康（管理端口，不经业务端口）
kubectl -n wgh exec deploy/wgh-backend -- wget -qO- http://127.0.0.1:9090/actuator/health
kubectl -n wgh exec deploy/wgh-backend -- wget -qO- http://127.0.0.1:9090/actuator/health/liveness
kubectl -n wgh exec deploy/wgh-backend -- wget -qO- http://127.0.0.1:9090/actuator/health/readiness

# 经 Ingress 访问前端与 API
curl -H 'Host: wgh.example.com' http://<ingress-address>/ 
curl -H 'Host: wgh.example.com' http://<ingress-address>/api/...
```

SSE 验证需实际发起一次聊天/工作流请求，确认事件逐条到达、不被缓冲；Ingress 侧参数见 [ingress.yaml](base/ingress.yaml)。

### 4.6 卸载

```sh
kubectl delete -f /tmp/wgh-k1.yaml
```

注意：`wgh-uploads`（以及 optional 中的 `wgh-backup`）是集群内 PVC，删除资源会一并删除 PVC。生产环境应先确认回收策略与备份。

### 4.7 启用 K2 多副本（含 OSS）

#### 1) 确认前置

| 项              | 要求                                                                                                        |
| -------------- | --------------------------------------------------------------------------------------------------------- |
| 调度与对账          | 调度中心 S0（实例心跳、失联回收、作业/业务对账）已随镜像上线                                                                          |
| 应用配置           | 已完成：`application.yml` 的 `wgh.rag.storage` 段已含 `mode` / `read-fallback-enabled` 占位符（默认 `single` / `false`） |
| 连接池            | `25 × 副本数 ≤ PG max_connections × 0.7`，HPA 上限 4 副本即按 100 连接核算                                              |
| metrics-server | 已安装，否则 HPA 无法取到 CPU 指标                                                                                    |
| OSS            | bucket 已创建，AK/SK 具备该 bucket 的读写权限                                                                         |
| 存储卷            | 多副本仍需共享 `wgh-uploads`（回落读 + `ChatImageService` 本地写），要求 StorageClass 支持 RWX                                |

> **绑定方式**：`StorageProperties` 前缀是 `wgh.rag.storage`，环境变量必须经 `application.yml` 占位符映射（`mode: ${WGH_STORAGE_MODE:single}`、`read-fallback-enabled: ${WGH_STORAGE_READ_FALLBACK:false}`），项目没有自定义 PropertySource，仅以环境变量名不会自动绑定。
>
> **从启动日志确认**：`kubectl -n wgh logs deploy/wgh-backend | grep "文件存储部署模式"` 应输出 `shared；后端 oss，bucket=<your-bucket>`；若输出 `single（单实例）`，说明 `WGH_STORAGE_MODE` 未生效或 Pod 未重建。回落读开启时还会出现 `已启用迁移期兼容读`。

#### 2) 准备 Secret（OSS 必填）

`base/secret.example.yaml` 中的 OSS 两项是占位值 `REPLACE_ME_*`，**不是真实密钥**，不要直接 apply。实际注入方式：

```sh
kubectl -n wgh create secret generic wgh-backend-secrets \
  --from-literal=SPRING_DATASOURCE_PASSWORD='<db-password>' \
  --from-literal=WGH_JWT_SECRET='<32+ chars>' \
  --from-literal=WGH_AUTH_LOCAL_JWT_SECRET='<32+ chars>' \
  --from-literal=WGH_INIT_ADMIN_PASSWORD='<initial-admin-password>' \
  --from-literal=WGH_OSS_ACCESS_KEY_ID='<oss-access-key-id>' \
  --from-literal=WGH_OSS_ACCESS_KEY_SECRET='<oss-access-key-secret>' \
  --dry-run=client -o yaml | kubectl apply -f -
```

已有 Secret 时用 `kubectl -n wgh patch secret wgh-backend-secrets --type merge -p '{"stringData":{...}}'` 追加，避免覆盖其它键。Secret 中缺 OSS 键时，`mode=shared` 自检会让容器直接启动失败（CrashLoop），不会带病提供上传。

#### 3) 替换环境相关值（占位项）

K2 清单中所有待替换项统一以 `REPLACE_ME_` 开头：

| 占位项 | 位置 | 替换为 |
|---|---|---|
| `REPLACE_ME_REGISTRY` | [overlays/k2-multi/kustomization.yaml](overlays/k2-multi/kustomization.yaml) 的 `images` | 镜像仓库地址 |
| `REPLACE_ME_TAG` | 同上 | 镜像 tag |
| `REPLACE_ME_OSS_ENDPOINT` | 同上 ConfigMap patch | 如 `https://oss-cn-hangzhou.aliyuncs.com` |
| `REPLACE_ME_OSS_BUCKET` | 同上 ConfigMap patch | 实际 bucket |
| `REPLACE_ME_OSS_ACCESS_KEY_ID` / `REPLACE_ME_OSS_ACCESS_KEY_SECRET` | `wgh-backend-secrets`（模板 [base/secret.example.yaml](base/secret.example.yaml)） | 真实 AK/SK，只进 Secret |
| `wgh.example.com` | [base/ingress.yaml](base/ingress.yaml) | 实际域名 |

- Ingress 域名 K2 未覆盖，直接改 base 或按 K1 的方式在 overlay 加 patch。
- 外部 PG/Redis 同 K1，见 [base/services-external.yaml](base/services-external.yaml)。

#### 4) 渲染与校验

```sh
kubectl kustomize --load-restrictor LoadRestrictionsNone deploy/k8s/overlays/k2-multi > /tmp/wgh-k2.yaml
kubeconform -strict -summary -kubernetes-version 1.30.0 /tmp/wgh-k2.yaml
```

#### 5) 手动执行 20261124 迁移（清单未收录）

`base/kustomization.yaml` 的 `configMapGenerator` 目前只收录到 `20261122`，以下两个脚本**不在迁移 Job 内**，必须在后端启动前手动执行（脚本自身幂等）：

| 脚本 | 变更 |
|---|---|
| `rag-backend/sql/migrations/20261124/01_scheduler_instance.sql` | 新建 `wgh_scheduler_instance`（实例注册与心跳） |
| `rag-backend/sql/migrations/20261124/02_quota_usage_epoch.sql` | `wgh_quota_usage.usage_epoch`、`wgh_quota_charge.usage_epoch` 两列 |

```sh
# 集群内 PG（或能直连库的任意环境）
kubectl -n wgh exec -i deploy/wgh-postgres -- \
  psql -v ON_ERROR_STOP=1 -U postgres -d wgh_rag_dev \
  < rag-backend/sql/migrations/20261124/01_scheduler_instance.sql
kubectl -n wgh exec -i deploy/wgh-postgres -- \
  psql -v ON_ERROR_STOP=1 -U postgres -d wgh_rag_dev \
  < rag-backend/sql/migrations/20261124/02_quota_usage_epoch.sql

# 确认对象存在
kubectl -n wgh exec deploy/wgh-postgres -- psql -U postgres -d wgh_rag_dev -c '\d wgh_scheduler_instance'
kubectl -n wgh exec deploy/wgh-postgres -- psql -U postgres -d wgh_rag_dev \
  -c "SELECT table_name,column_name FROM information_schema.columns WHERE column_name='usage_epoch'"
```

预期：第二条查询返回 `wgh_quota_usage`、`wgh_quota_charge` 两行。重复执行两个脚本应无报错（`IF NOT EXISTS`）。使用托管数据库时改用 `psql` 直连执行同样的两个文件。

#### 6) 部署

前置：PG/Redis 可达，且第 5 步的两个 20261124 脚本已执行（否则实例注册写表失败）。

```sh
# 1) 先跑迁移 Job（与 K1 相同，重复部署前先删旧 Job）
kubectl -n wgh delete job wgh-migrate --ignore-not-found
kubectl apply -f /tmp/wgh-k2.yaml -l app.kubernetes.io/component=migrate
kubectl -n wgh wait --for=condition=complete job/wgh-migrate --timeout=10m

# 2) 应用全部资源
kubectl apply -f /tmp/wgh-k2.yaml

# 3) 观察
kubectl -n wgh rollout status deploy/wgh-backend --timeout=10m
kubectl -n wgh get pods,hpa,pdb
kubectl -n wgh logs deploy/wgh-backend --tail=50 | grep -E '文件存储部署模式|shared'
```

启动日志应出现 `文件存储部署模式: shared；后端 oss，bucket=...`；出现 `OSS 配置不完整` 说明 Secret 未注入。

#### 7) 冒烟

```sh
# 两个副本都就绪、HPA 读到指标、PDB 生效
kubectl -n wgh get hpa wgh-backend
kubectl -n wgh get pdb
# 上传一个文件后删除 Pod，确认文件仍可预览（走 OSS）
```

#### 8) 关闭回落读（迁移完成后）

```sh
# 1) 先确认旧对象已全部迁到 OSS（应用内 local→oss 迁移作业完成）
# 2) 在 overlays/k2-multi 的 ConfigMap patch 中把 WGH_STORAGE_READ_FALLBACK 改为 "false"，
#    或用 kubectl set env 临时覆盖（env 优先于 envFrom，Pod 重建后生效）
kubectl -n wgh set env deploy/wgh-backend WGH_STORAGE_READ_FALLBACK=false
```

`WGH_STORAGE_MODE` 保持 `shared`。**关闭回落读后仍不能摘除 PVC 与 RWX patch**：`ChatImageService` 仍直接写 `WGH_UPLOAD_DIR`（`/app/uploads/chat-images`），多副本需要共享卷。等聊天图片也接入 `FileStorageService`（业务改动，不在本清单范围）后，才能移除 overlay 的 PVC patch 与 Deployment 的 uploads 挂载，并删除 PVC。

## 5. 多实例约束如何落实

| 约束                         | 落实位置                                                             | 说明                                           |
| -------------------------- | ---------------------------------------------------------------- | -------------------------------------------- |
| 应用无状态                      | Deployment 不挂任何本地可写状态（除 PVC 文件）                                  | 本地临时目录全部 `emptyDir`                          |
| 实例标识                       | Downward API `metadata.name` → `WGH_INSTANCE_ID`                 | 指标按实例区分                                      |
| 认证会话                       | 无 Spring Session，JWT 无状态                                         | 不需要会话复制；粘性仅用于 SSE                            |
| 取消标记 / 限流 / 密码锁定 / JWKS 缓存 | `WGH_REDIS_*` 全部指向同一 Redis                                       | 多副本必须共享；Redis 宕机时部分能力降级                      |
| 调度作业租约                     | 应用内（Redis 单飞锁，`wgh.rag.storage.migration.lock-ttl-seconds` 等）    | 不由 K8s CronJob 替代；K2 前置 S0                   |
| 文件存储                       | `WGH_STORAGE_TYPE=oss` + `WGH_STORAGE_MODE=shared`（K2）；K1 本地 PVC | 多副本禁止写各自本地盘；PVC 仅用于回落读与聊天图片                  |
| SSE                        | Ingress 缓冲关闭、3600s 超时、K2 Cookie 亲和                               | `Last-Event-ID` 重放与前端重连已实现；独立工作流/多 Agent 流未接共享缓冲，故暂保留亲和 |

**副本数与环境变量对应（K2）**

| 工作负载 | 副本 | 关键环境变量 / 配置 | 说明 |
|---|---|---|---|
| `wgh-backend` | 2（HPA 2–4） | `PROD_DB_POOL_MAX=25` | 4 副本上限 100 连接，须满足 PG `max_connections × 0.7` |
| `wgh-backend` | 同上 | `WGH_STORAGE_TYPE=oss`、`WGH_STORAGE_MODE=shared`、`WGH_STORAGE_READ_FALLBACK=true` | shared 自检缺 OSS 必填项即启动失败 |
| `wgh-backend` | 同上 | `WGH_INSTANCE_ID`（Downward API `metadata.name`） | 指标与调度实例按 Pod 区分 |
| `wgh-backend` | 同上 | `WGH_MANAGEMENT_PORT=9090` | 探针走分组路径，不经业务端口 |
| `wgh-frontend` | 2（固定） | 无业务变量 | nginx 反代 `http://backend:8080`，Service 名不可改 |
| Ingress | — | 缓冲关闭、3600s 超时、`210m` 包体、Cookie 亲和 | 见 §5.2 |

| 滚动更新                       | `maxUnavailable: 0` / `maxSurge: 1` + `minReadySeconds: 10`      | 单副本升级会出现短暂双 Pod，需要节点余量                       |
| 中断预算                       | K2 `PDB minAvailable: 1`                                         | 只约束主动驱逐                                      |
| 连接池                        | 单副本 50；K2 降为 25                                                  | 25 × 副本数 ≤ PG `max_connections` 的 70%        |

### 5.1 文件存储切换（local → OSS）

K2 的清单已经把存储切到 OSS，相关键位：

| 键                                                     | 位置        | K2 取值       | 说明                                                   |
| ----------------------------------------------------- | --------- | ----------- | ---------------------------------------------------- |
| `WGH_STORAGE_TYPE`                                    | ConfigMap | `oss`       | 选择 OSS 实现                                            |
| `WGH_STORAGE_MODE`                                    | ConfigMap | `shared`    | 开启多实例启动自检：OSS 必填项缺失即启动失败                             |
| `WGH_STORAGE_READ_FALLBACK`                           | ConfigMap | `true`（过渡期） | 主存储未命中时回落读 PVC 上的旧对象；迁移完成后改 `false`                  |
| `WGH_OSS_ENDPOINT`                                    | ConfigMap | 占位值         | 公网 endpoint，按环境替换；可选 `WGH_OSS_INTERNAL_ENDPOINT` 走内网 |
| `WGH_OSS_BUCKET`                                      | ConfigMap | 占位值         | 按环境替换；多业务共用时补 `WGH_OSS_KEY_PREFIX`                   |
| `WGH_OSS_ACCESS_KEY_ID` / `WGH_OSS_ACCESS_KEY_SECRET` | Secret    | 不写入清单       | 由 `wgh-backend-secrets` 注入，见 §4.7                    |

切换步骤：

1. 创建或补充 Secret 中的 OSS 两键（真实值，不进仓库）；
2. 替换 ConfigMap 的 endpoint / bucket 占位值；
3. 分批滚动重启，先观察启动日志中的 `文件存储部署模式: shared；后端 oss`；
4. 历史文件用应用内 local→oss 迁移作业搬运（`StorageMigration` 能力，具备进度、取消与跨实例单飞）；
5. 迁移完成后将 `WGH_STORAGE_READ_FALLBACK` 设为 `false`，再观察一个发布周期。

**收敛条件**：`WGH_STORAGE_MODE` 保持 `shared`；PVC 与 K2 的 RWX patch 只有在 `ChatImageService` 也接入存储抽象后才能移除（它当前仍写 `/app/uploads/chat-images`）。在此之前，摘卷会导致聊天图片上传失败或随 Pod 丢失。

**本地磁盘不可用时**：不要临时改回 `emptyDir` 承载业务文件——Pod 重建即丢；应使用 OSS，或使用支持 RWX 的共享卷。

**绑定已验证（2026-11-24）**：`application.yml` 已补两个占位符，用临时 Spring 上下文装载真实配置验证：不设变量 → `single` + `LocalFileStorageService`；`WGH_STORAGE_MODE=shared` → 日志 `文件存储部署模式: shared；后端 local ...`；`type=oss` + `shared` + `read-fallback=true` → 日志 `shared；后端 oss，bucket=...` 与 `已启用迁移期兼容读`，主 Bean 为 `FallbackReadFileStorageService`；`shared` 但 OSS 缺必填项 → 启动失败并报 `OSS 配置不完整`。[config/migrations/20261124/03\_storage-shared-multi-replica.yml](../../rag-backend/config/migrations/20261124/03_storage-shared-multi-replica.yml) 仅作变更记录，运行时以 `application.yml` 为准。

### 5.2 SSE 在 Ingress 上的参数

| 参数                   | 值      | 原因                               |
| -------------------- | ------ | -------------------------------- |
| `proxy-buffering`    | `off`  | 事件逐条下发，避免整段缓冲                    |
| `proxy-read-timeout` | `3600` | 聊天 30 分钟、多 Agent 10 分钟、工作流 30 分钟 |
| `proxy-send-timeout` | `3600` | 同上                               |
| `proxy-body-size`    | `210m` | 后端单文件 200MB + multipart 余量       |
| `proxy-http-version` | `1.1`  | SSE 需要                           |

K2 额外启用 `affinity: cookie`；它只解决「同一浏览器落在同一 Pod」，Pod 被驱逐后仍会丢进度，真正的续传依赖事件重放。

**为什么现阶段保留**（依据 [design-sse-event-replay.md](../../docs/design/features/platform/design-sse-event-replay.md) §7）：

1. 前端已支持 `Last-Event-ID` 自动重连，但事件重放只覆盖对话流与聊天窗口 HITL；独立工作流与多 Agent 流仍是进程内 emitter，亲和是它们唯一的跨副本保障；
2. 重放只能补已写入缓冲的事件，生成实例被杀后仍需状态接口兜底；跨副本重连尚未实测。

**可以去掉亲和的全部前置条件**（须同时满足）：

- 前端与开放 API 示例客户端在断线时携带 `Last-Event-ID` 自动重连，并能处理 `stream_replay_gap` / `stream_replay_incomplete`；
- 独立工作流与多 Agent 流接入共享缓冲，或明确下线其 SSE（改为状态轮询）；
- 完成跨副本重连实测（定向断连一个 Pod，验证回放条数与最终状态一致）。

满足后可从 [overlays/k2-multi/kustomization.yaml](overlays/k2-multi/kustomization.yaml) 删除四条 `affinity` / `session-cookie-*` 注解，其余 SSE 参数不变。

## 6. 外部中间件接入

推荐 **托管 PG/Redis + ExternalName Service**，应用配置中的主机名保持 `postgres` / `redis` 不变。

- PostgreSQL：必须 15+ 且具备 `vector`、`pg_trgm`；迁移 Job 需要网络可达。
- Redis：`appendonly` 与持久化由托管侧保证；应用侧已声明可降级内存，但多副本下限流与锁定语义会失真。
- 若不使用 ExternalName：直接把 `SPRING_DATASOURCE_URL`、`WGH_REDIS_HOST` 改成外部域名/IP。注意 ExternalName 只接受域名，且部分集群的 NetworkPolicy 与 DNS 模式对其支持有限。
- 不提供集群内 PostgreSQL 的裸 StatefulSet：无 HA/无 PITR，且与设计文档「不做手写裸 StatefulSet」一致。需要自建时用 CloudNativePG 等 Operator，另行规划。
- 连接池计算：`PROD_DB_POOL_MAX × backend 副本数 + 迁移/备份 Job 连接数 ≤ PG max_connections × 0.7`。

## 7. 应用侧建议与风险（本次不改代码）

| 项         | 当前事实                                                                                       | 风险                                                      | 建议（后续）                                                                        |
| --------- | ------------------------------------------------------------------------------------------ | ------------------------------------------------------- | ----------------------------------------------------------------------------- |
| 优雅停机      | 已配置 `server.shutdown=graceful`、`spring.lifecycle.timeout-per-shutdown-phase=45s`           | 超过 45s 的 SSE 长连接仍会被断开；`preStop sleep 5` 只负责先摘除 Endpoint | 保持「preStop 5s + 优雅停机 45s < terminationGracePeriodSeconds 60s」；SSE 断点恢复仍依赖事件重放 |
| 探针分组      | 已开启 `management.endpoint.health.probes`：liveness=livenessState，readiness=readinessState+db | Redis 不可用不影响就绪，但限流/锁定会降级                                | 维持现状；若要把 Redis 纳入就绪，需先确认其高可用与降级边界                                             |
| 日志        | logback 同时有 CONSOLE 与 FILE，prod 写 `/var/log/wgh-rag`                                       | 文件日志占本地盘且随 Pod 丢失；采集应走 stdout                           | 集群采集走 stdout；文件 appender 降为可选                                                 |
| 前端后端地址    | `nginx.conf` 硬编码 `http://backend:8080`                                                     | 换 Service 名或跨命名空间即失效                                    | 改 envsubst 模板注入                                                               |
| 连接池       | 单副本 `PROD_DB_POOL_MAX=50`，K2 已降为 25                                                        | 4 副本 = 100 连接，仍受 PG `max_connections` 约束                | 扩容前按上限复算，或引入 PgBouncer                                                        |
| SSE 进度恢复  | 事件重放（`Last-Event-ID`）与前端重连已实现                                                          | 独立工作流/多 Agent 流未覆盖；跨副本重连未实测                               | 覆盖剩余流并做定向断连实测；完成前保留 Cookie 亲和                                                |
| 聊天图片      | `ChatImageService` 直接写 `WGH_UPLOAD_DIR`，未走存储抽象                                             | 多副本必须保留 RWX 卷；Pod 重建依赖共享卷                               | 接入 `FileStorageService` 后可摘 PVC                                               |
| HPA       | 仅 CPU 指标                                                                                   | SSE 长连接使 CPU 下降滞后，扩缩容不准                                 | 后续接 `wgh.scheduler.queued` 自定义指标                                              |
| 沙箱        | `bwrap` 依赖宿主 user namespace，默认关闭                                                           | 开启后与 Pod 安全策略冲突                                         | 独立节点池 + 受限 SecurityContext；不可用时显式禁用并告警                                        |
| 迁移幂等      | 全部脚本使用 `IF NOT EXISTS`/`ON CONFLICT`，可重复执行                                                 | 新增非幂等脚本会破坏升级                                            | 引入版本表或 Flyway；Job 中增加已执行记录                                                    |
| 镜像流水线     | CI 只编译测试，不构建/推送                                                                            | 清单只能用占位镜像                                               | CI 增加构建、扫描、推送                                                                 |
| PVC 权限    | 依赖镜像 `wgh` 用户 UID/GID（按 100 设置 `fsGroup`）                                                  | 更换基础镜像后可能不可写                                            | 用 `docker run --rm --entrypoint id <image>` 复核                                |
| Redis 高可用 | 单实例（托管或自建）                                                                                 | 单点故障时限流/锁定降级                                            | 托管主从或 Sentinel；监控降级状态                                                         |

## 8. 本地校验方式与实际结果

无可用集群，采用离线渲染 + schema 校验：

```sh
kubectl kustomize --load-restrictor LoadRestrictionsNone deploy/k8s/overlays/k1-single > /tmp/k8s-render/k1.yaml
kubectl kustomize --load-restrictor LoadRestrictionsNone deploy/k8s/overlays/k2-multi  > /tmp/k8s-render/k2.yaml
kubectl kustomize --load-restrictor LoadRestrictionsNone deploy/k8s/optional            > /tmp/k8s-render/optional.yaml

kubeconform -strict -summary -kubernetes-version 1.30.0 \
  /tmp/k8s-render/k1.yaml /tmp/k8s-render/k2.yaml /tmp/k8s-render/optional.yaml
```

kubeconform 默认从 GitHub 拉取 schema。网络不稳定时可预先下载 `v1.30.0-standalone-strict` 的 schema 到本地目录，再用
`-schema-location '/path/to/schema/{{.ResourceKind}}{{.KindSuffix}}.json'` 离线校验。

**实际结果（2026-11-24）**

| 检查                               | 工具/版本                                       | 结果                                                                                                        |
| -------------------------------- | ------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| 渲染 K1                            | kubectl v1.32.2 / Kustomize v5.5.0          | 成功，13 个资源                                                                                                 |
| 渲染 K2                            | 同上                                          | 成功，16 个资源                                                                                                 |
| 渲染 optional                      | 同上                                          | 成功，2 个资源                                                                                                  |
| schema 校验                        | kubeconform v0.8.0（1.30.0 schema，`-strict`） | `31 resources - Valid: 31, Invalid: 0, Errors: 0, Skipped: 0`                                             |
| Secret 模板                        | YAML 解析（模板不参与渲染）                            | 语法通过，含 7 个键（新增 OSS AK/SK 占位）；本地 schema 缓存无 Secret，未做 schema 校验                                            |
| 迁移脚本语法                           | `sh -n`                                     | 通过                                                                                                        |
| `kubectl apply --dry-run=client` | kubectl v1.32.2                             | **不可用**：无集群时需 API discovery，报 `dial tcp [::1]:8080: connect: connection refused`；因此以 kubeconform 作为等价校验手段 |
| 渲染 K3                            | kubectl v1.32.2 / Kustomize v5.5.0          | 成功，27 个资源                                                                                                 |
| 渲染 optional（K3 版）                | 同上                                          | 成功，5 个资源                                                                                                  |
| schema 校验 K3 + optional          | kubeconform v0.8.0（1.30.0 schema，`-strict`） | `32 resources - Valid: 32, Invalid: 0, Errors: 0, Skipped: 0`                                             |

说明：本次在线校验在超时时间内未返回（GitHub raw 反复读超时），改用本地缓存的 1.30.0 schema 完成离线校验。
K3 新增的 NetworkPolicy / ResourceQuota / LimitRange 三类 schema 预下载到本地缓存后离线校验；CronJob、Secret 之外的其余类型复用 K2 时的缓存。

K2 渲染结果额外抽查：ConfigMap 含 `WGH_STORAGE_TYPE=oss`、`WGH_STORAGE_MODE=shared`、`WGH_STORAGE_READ_FALLBACK=true`、`WGH_OSS_ENDPOINT`、`WGH_OSS_BUCKET`、`PROD_DB_POOL_MAX=25` 且保留 `WGH_UPLOAD_DIR`；Deployment 为 2 副本并保留 `wgh-uploads` 挂载；PVC 为 `ReadWriteMany`；Ingress 含四条 Cookie 亲和注解；HPA 2–4、两个 PDB `minAvailable: 1` 均存在。

未验证项（如实标注）：
1. 真实集群上的 2+2 调度与就绪（未在目标集群做过部署）；
2. 双副本 readiness 探针、PDB 驱逐、`maxUnavailable: 0` 滚动期间零 5xx；
3. HPA 2→4 的实际扩缩容与 CPU 指标采集（依赖 metrics-server）；
4. 跨副本 SSE 重连（Pod A 生成、Pod B 接管）与事件回放完整性；
5. OSS 真实上传/下载/删除、local→oss 回落读链路；
6. `mode=shared` 启动自检在容器内的真实行为（本地仅用临时进程验证过绑定）；
7. Ingress 控制器注解行为、PVC 权限（fsGroup）、迁移 Job 对真实库的执行；
8. §4.7 第 5 步的 20261124 两个手动迁移未在任何集群库执行过（脚本幂等，需部署时按步骤跑并核对表/列）。

**本机 kind 残留**：本次曾用 `kind` 创建单节点集群 `wgh`（host 8080→80、8443→443，含 ingress-nginx）用于探索，未完成应用部署；`deploy/k8s/` 中未保留任何 kind 专用 overlay 或依赖清单（临时的 `optional/local-deps.yaml` 已移除），K2 仍为 OSS 生产形态。如需清理：

```sh
kind delete cluster --name wgh
docker rmi wgh-rag-backend:local wgh-rag-frontend:local
```

Docker Desktop 的资源在本次探索中调整为 4 CPU / 5 GB，可按需在设置中改回。

## 9. 已知限制

1. NetworkPolicy 只在 `overlays/k3-prod` 提供；`base/`、`k1-single`、`k2-multi` 仍无策略。
   K3 的 PG/Redis 网段（`10.0.0.0/8`）与命名空间名是占位值，必须按环境替换，否则会误拦。
2. ServiceMonitor 未提供：K3 选择 Pod 注解抓取（依据见 §10.6）。需要 Prometheus Operator 时按 §10.6 的等价片段改写。
3. 迁移 Job 每次全量重跑脚本，没有版本表；脚本本身幂等，但不防「新增非幂等脚本」。
4. Kubernetes 清单不部署 PG/Redis；集群内自建属于独立议题。
5. K2 overlay 过渡期把 PVC 改为 RWX（回落读 + 聊天图片本地目录），要求 StorageClass 支持；PVC `accessModes` 不可原地修改，已有卷需重建。OSS 数据迁移完成且聊天图片接入存储抽象后，才可移除该卷。
6. `WGH_STORAGE_MODE` / `WGH_STORAGE_READ_FALLBACK` 已在 `application.yml` 绑定并验证，见 §5.1；环境变量不设时保持 `single` / `false`，与历史行为一致。
7. `optional/cronjob-db-backup.yaml` 与 `cronjob-db-backup-oss.yaml` 只解决「备份存在且可校验」；
   RTO/RPO 取决于托管库快照/WAL 归档与恢复演练结论，见 §10.4。

## 10. K3 生产级（overlays/k3-prod）

K3 的目标不是「再加副本」，而是让清单具备**可用性边界、安全边界、容量边界、恢复能力与运维动作**。
本 overlay 直接以 [overlays/k2-multi](overlays/k2-multi/kustomization.yaml) 为基线（不复制其文件），
因此 **k2-multi 的后续改动会自动进入 K3**，合并前必须复核。

### 10.1 与 K2 的差异

| 维度          | K2                     | K3                                                               |
| ----------- | ---------------------- | ---------------------------------------------------------------- |
| backend 副本  | 2                      | 3（HPA 3–8）                                                       |
| frontend 副本 | 2                      | 2（打散）                                                            |
| HPA         | 2–4，CPU 70%            | 3–8，CPU 65%；扩 100%/60s，缩 1 Pod/120s                              |
| PDB         | backend 1 / frontend 1 | backend **2** / frontend 1                                       |
| 打散          | 无                      | zone + hostname `topologySpreadConstraints`（ScheduleAnyway）+ 反亲和 |
| 连接池         | 25                     | **15**（8 × 15 = 120）                                             |
| 存储回落读       | `true`                 | **`false`**                                                      |
| 网络          | 无策略                    | 默认拒绝 + 8 条 NetworkPolicy（1 拒绝 + 7 放行）                            |
| 资源边界        | 无                      | ResourceQuota + LimitRange                                       |
| 指标抓取        | 靠外部配置                  | Pod 注解 9090 + 告警规则 ConfigMap                                     |
| 入口          | TLS 注释状态               | TLS + 强制 HTTPS                                                   |
| 镜像          | tag                    | **digest**（tag 仅可读）                                              |

### 10.2 部署步骤

```sh
# 1) 渲染与校验（必须带 LoadRestrictionsNone，因为要读 base 之外的 SQL 与告警规则文件）
kubectl kustomize --load-restrictor LoadRestrictionsNone deploy/k8s/overlays/k3-prod > /tmp/wgh-k3.yaml
kubeconform -strict -summary -kubernetes-version 1.30.0 /tmp/wgh-k3.yaml

# 2) 先确认前置：Secret、TLS Secret、监控/入口命名空间名、PG/Redis 网段
kubectl -n wgh get secret wgh-backend-secrets
kubectl -n wgh get secret wgh-tls

# 3) 清单外的手动迁移（见下方说明，必须在 apply 之前完成）
psql -v ON_ERROR_STOP=1 -h <pg-host> -p 5432 -U postgres -d wgh_rag_prod \
  -f rag-backend/sql/migrations/20261124/01_scheduler_instance.sql
psql -v ON_ERROR_STOP=1 -h <pg-host> -p 5432 -U postgres -d wgh_rag_prod \
  -f rag-backend/sql/migrations/20261124/02_quota_usage_epoch.sql

# 4) 迁移 Job 先行（执行清单内脚本；重复部署前先删旧 Job）
kubectl -n wgh delete job wgh-migrate --ignore-not-found
kubectl apply -f /tmp/wgh-k3.yaml -l app.kubernetes.io/component=migrate
kubectl -n wgh wait --for=condition=complete job/wgh-migrate --timeout=15m

# 5) 应用全部资源
kubectl apply -f /tmp/wgh-k3.yaml

# 6) 观察
kubectl -n wgh rollout status deploy/wgh-backend --timeout=15m
kubectl -n wgh get pods,hpa,pdb,netpol,quota
kubectl -n wgh describe quota wgh-quota
```

> **清单外的手动迁移（已定方案，不补进 base）**：
> `20261124/01_scheduler_instance.sql`（调度实例注册表 + 索引）与 `20261124/02_quota_usage_epoch.sql`
> （`usage_epoch` 两列）**不纳入** [base/kustomization.yaml](base/kustomization.yaml) 的 `configMapGenerator`，
> 由部署前在目标库上手动执行；迁移 Job 只执行清单内已打包的脚本。
>
> - **顺序**：手动迁移 → `wgh-migrate` Job → apply 应用。两个脚本均为增量结构变更，
>   必须先于新版本 Pod 启动完成，否则新代码读写缺表/缺列会直接 readiness 失败。
> - **执行方式**：必须带 `-v ON_ERROR_STOP=1`，任一语句失败立刻非零退出（不要用 `psql -1`，
>   脚本内部已有 `BEGIN/COMMIT`）。执行机需能直连 PG（不在集群内也可）。
> - **幂等**：建表/建索引用 `IF NOT EXISTS`，加列用 `ADD COLUMN IF NOT EXISTS`，重复执行不会改数据，可安全重跑。
> - **执行后确认**：
>   ```sql
>   -- 1) 实例注册表存在
>   SELECT to_regclass('public.wgh_scheduler_instance');
>   -- 2) 两个纪元列存在
>   SELECT table_name, column_name FROM information_schema.columns
>    WHERE (table_name = 'wgh_quota_usage'  AND column_name = 'usage_epoch')
>       OR (table_name = 'wgh_quota_charge' AND column_name = 'usage_epoch');
>   ```
>   第一条返回非 NULL，第二条返回 2 行。
> - **漏执行的后果**：缺 `wgh_scheduler_instance` 时实例心跳/失联回收写入失败（调度实例注册不可用，
>   失联实例持有的作业不会被及时回收）；缺 `usage_epoch` 时扣费/退款 SQL 报列不存在，
>   表现为配额相关接口 5xx 与 `WghQuotaRefundFailed`。

### 10.3 高可用与容量

**副本与边界**

| 项          | 取值                        | 依据                                      |
| ---------- | ------------------------- | --------------------------------------- |
| backend 副本 | 3（HPA 3–8）                | 目标 3 个可用区各 1 个；单区故障后仍剩 2，PDB 2 可再驱逐 1 个 |
| CPU 目标     | 65% utilization           | SSE 长连接使 CPU 回落滞后，阈值留出缓冲                |
| 扩容         | 60s 窗口，最多翻倍               | 冷启动 180s（startupProbe），扩容过快会同时拖慢就绪      |
| 缩容         | 600s 窗口，1 Pod/120s        | 避免掐断长连接；SSE 不会随请求结束释放                   |
| 中断预算       | backend `minAvailable: 2` | 只约束 drain/eviction，不阻止滚动发布              |

**为什么不启用内存指标**：`JAVA_OPTS=-XX:MaxRAMPercentage=65.0` 让堆常驻到 limit 的约 65%，
内存利用率无法区分「常驻」与「压力」，用它扩容会持续空转。需要按业务压力扩容时，
接 `wgh.scheduler.queued` 等自定义指标（需 Prometheus Adapter），而不是内存。

**打散与反亲和**：`topology.kubernetes.io/zone` 与 `kubernetes.io/hostname` 两级
`topologySpreadConstraints(maxSkew: 1)` 均为 `ScheduleAnyway`，配合 100/50 权重的
`podAntiAffinity(preferred)`。这是刻意的取舍：硬约束（`DoNotSchedule`）在节点数 < 副本数的
dev/kind 集群会把 Pod 卡成 Pending。节点数 ≥ 3 且确认容量后，可把 hostname 一级改为
`DoNotSchedule` 以获得强打散。

**连接池复算**

```
每副本池 × HPA 上限 + 迁移/备份/演练 Job 连接数 ≤ PG max_connections × 0.7
15 × 8 + 2 = 122  ≤  max_connections × 0.7  →  max_connections ≥ 175（建议 200）
```

若 PG 上限只有 100，必须把 `PROD_DB_POOL_MAX` 降到 12 以下，或引入 PgBouncer；
否则扩容到第 8 个副本时会把数据库打满，表现为全站 readiness 503。

**ResourceQuota / LimitRange**：见 [quota-limitrange.yaml](overlays/k3-prod/quota-limitrange.yaml)。
配额按 HPA 上限 8 副本 + 滚动余量计算；LimitRange 提供容器默认 requests/limits 与单容器上限（4C/8Gi），
防止漏写 requests 的 Pod 打满节点。修改副本数或资源规格时必须同步这两个文件，否则发布会被配额拒绝。

### 10.4 存储与备份

**存储形态选择条件**

| 场景            | 选择             | 说明                                                     |
| ------------- | -------------- | ------------------------------------------------------ |
| 单副本、小规模、无恢复承诺 | 本地 PVC（K1）     | 最简单，Pod 重建依赖卷                                          |
| 多副本（K2/K3）    | **OSS**        | 唯一不依赖 RWX StorageClass 的方案                             |
| 必须用共享卷        | RWX PVC        | 需 StorageClass 支持；`accessModes` 不可原地改，切 OSS 不靠改 PVC 完成 |
| 需要 RPO/RTO 承诺 | OSS + 版本化 + 备份 | 见下方备份分层                                                |

**切换点**：`WGH_STORAGE_READ_FALLBACK` 只在 local→OSS 迁移窗口为 `true`；迁移完成后改 `false` 并观察一个发布周期。
K3 默认 `false`。但 **PVC 与 RWX patch 仍不能摘**：`ChatImageService` 直接写 `WGH_UPLOAD_DIR`，
未接入存储抽象（业务改动，不在本清单范围）。无法提供 RWX 时，只能退回单副本。

**备份分层**

| 层 | 手段                                   | RPO（目标）  | RTO（目标）     | 用途                 |
| - | ------------------------------------ | -------- | ----------- | ------------------ |
| 1 | 托管 PG 自动快照（建议 7–14 天保留）              | 5–15 min | 30–60 min   | 实例级故障、误删库          |
| 2 | 每日逻辑备份（`cronjob-db-backup-oss.yaml`） | 24 h     | 2 h（含迁移与切流） | 跨版本/跨云迁移、恢复演练、审计留档 |
| 3 | WAL 归档 / PITR（托管能力）                  | ≤ 5 min  | 1–2 h       | 误操作定点恢复            |

备份 CronJob 的取舍：

- `cronjob-db-backup-oss.yaml`：dump 落 emptyDir + rclone 直传 OSS，不依赖集群卷，**生产推荐**；
- `cronjob-db-backup.yaml`：dump 落集群内 PVC，仅适合没有对象存储的环境；
- 两者**不要同时**在同一时间点运行（都默认在凌晨，已错开 30 分钟），且都要开启 bucket 版本化与生命周期。

**对象存储版本化与保留**（bucket 级，一次性配置）：

```sh
# 示例：开启版本化 + 非当前版本 30 天过期 + 90 天彻底删除
ossutil bucket-versioning --method put oss://<bucket>
ossutil lifecycle --method put oss://<bucket> lifecycle.xml   # 规则：db-backup/ 前缀，非当前版本 30d，历史版本 90d
```

版本化保证「误删/覆盖备份文件可回溯」；生命周期保证成本可控。不要把保留策略写进 Job 的 shell，
否则两个备份任务会互相删除对方的文件。

**恢复演练（每月，或每次大版本发布前）**

1. 触发：`kubectl -n wgh create job --from=cronjob/wgh-restore-drill restore-drill-$(date +%s)`；
2. 看日志：`kubectl -n wgh logs job/restore-drill-<ts>`，必须出现 `[drill] PASS`；
3. 脚本行为：校验 `.sha256` → 建临时库 `wgh_rag_prod_restore_drill` → `pg_restore --exit-on-error` →
   校验 `vector`/`pg_trgm` 扩展、public 表数量与核心表行数 → 删除临时库；
4. 失败即视为备份不可用，按 runbook 定位（dump 损坏、扩展缺失、权限不足）；
5. 真实灾备恢复：新建 PG 实例 → `pg_restore` → 跑迁移 Job → 切 ExternalName → 冒烟，
   全流程记录实际耗时作为 RTO 依据。演练默认从 PVC 读；`RESTORE_SOURCE=oss` 时 init 容器会从 bucket 拉最新 dump，
   注意 init 与主容器两处 env 要同时改。

### 10.5 安全

**NetworkPolicy 允许矩阵**（[networkpolicy.yaml](overlays/k3-prod/networkpolicy.yaml)，共 8 条：1 条默认拒绝 + 7 条放行）

| 方向 | 来源 → 目标                            | 端口          |
| -- | ---------------------------------- | ----------- |
| 入站 | ingress-nginx → frontend           | 80          |
| 入站 | ingress-nginx → backend（`/api` 直达） | 8080        |
| 入站 | frontend → backend                 | 8080        |
| 入站 | monitoring → backend（管理端口）         | 9090        |
| 出站 | frontend → backend                 | 8080        |
| 出站 | backend → PG / Redis               | 5432 / 6379 |
| 出站 | backend / backup → 对象存储与模型服务       | 443         |
| 出站 | migrate / backup → PG              | 5432        |
| 出站 | 所有 Pod → kube-dns                  | 53          |

必须按环境替换的占位值：PG/Redis 网段（当前 `10.0.0.0/8`）、`ingress-nginx` 与 `monitoring` 命名空间名。
使用 OSS 内网 endpoint 时，把其网段并入私网 `ipBlock`；公网 443 规则已排除 RFC1918 与 `169.254.169.254`（元数据地址）。

**Pod/容器安全上下文取舍**

| 工作负载     | 现状                                                                                           | 取舍说明                                                                                          |
| -------- | -------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| backend  | `runAsNonRoot`、`readOnlyRootFilesystem`、`drop: ALL`、`seccomp: RuntimeDefault`、`fsGroup: 100` | 未硬编码 `runAsUser`，避免换基础镜像后 PVC 不可写；换镜像时必须 `docker run --rm --entrypoint id <image>` 复核 UID/GID |
| frontend | 保留 `NET_BIND_SERVICE`，未设非 root                                                               | `nginx:1.27-alpine` 需要 root 启动并绑定 80；彻底非 root 需换 `nginx-unprivileged` 镜像（构建改动，超出本清单）          |
| migrate  | `drop: ALL` + 只读根 + 仅挂 `/scripts`、`/tmp`（base 未设 `runAsNonRoot`）                             | 用 `/bin/sh` 覆盖了镜像 entrypoint，实际以镜像默认用户运行；靠能力收敛与只读根约束，未做宿主挂载。要强制非 root 需改 base 的 Job（本次未改）     |
| backup   | `runAsNonRoot`（UID 70）+ `drop: ALL` + 只读根                                                    | 备份与演练只需 `/tmp`、`/work`、`/backup`                                                              |
| sandbox  | 不部署                                                                                          | `bwrap` 依赖宿主 user namespace，与受限 SecurityContext 冲突，需独立节点池                                     |

只读根文件系统的代价是**任何隐式写盘都会启动失败**（这是刻意的早期发现机制）：
现有挂载已覆盖 `/tmp`、`/var/log/wgh-rag`、`/app/skills`、`/app/uploads` 与 nginx 的 cache/run。
新增写路径时必须显式加卷，而不是关掉只读。

**Secret 外置**：当前是 `kubectl create secret` 模板注入，无法进 Git、无审计。落地路径用 Sealed Secrets：
集群装 controller → `kubeseal` 用集群公钥封存 → 只提交密文清单 → controller 在集群内解密为
`wgh-backend-secrets`。完整命令与轮换说明见 [sealedsecret.example.yaml](optional/sealedsecret.example.yaml)。
轮换时注意：应用在启动时读取环境变量，JWT/DB 密码轮换后需一次滚动重启（`maxUnavailable: 0` 保证不断流）；
数据库侧用「双凭据窗口」，先加新密码再切 Secret，最后回收旧凭据。

**镜像固定 digest**：K3 的 `images` 使用 `name:tag@sha256:...`，tag 只作可读说明。
digest 是仓库 + 架构相关的，必须用 `crane digest` 或 `docker inspect --format '{{index .RepoDigests 0}}'` 重新取值；
多架构镜像建议固定 manifest list 的 digest，而不是某个架构的 digest。
注意 k2-multi 已把镜像名改成 `newName`，K3 必须按改名后的完整仓库名匹配，否则 digest 不生效（只剩 tag）。

### 10.6 可观测与日志

**抓取方式：Pod 注解，而不是 ServiceMonitor。** 依据：

1. 现有告警中有多条按 `job="wgh-rag-backend"` 过滤（实例下线、无目标、5xx 比例）；
   注解 + 抓取端 relabel 能精确控制 job 标签，而 ServiceMonitor 的 job 标签由 Operator 规则推导，容易与既有告警对不上；
2. ServiceMonitor 依赖 Prometheus Operator CRD；集群监控由另一条线维护，是否装 Operator 未确认，
   而 kustomize 里放 CRD 资源会让 `kubeconform -strict` 需要 `-ignore-missing-schemas`，失去校验意义；
3. 注解对任何采集实现（原生 Prometheus、云厂商 agent）都成立，迁移成本最低。

需要集群侧在抓取配置里补的等价片段（只列关键 relabel）：

```yaml
- job_name: wgh-rag-backend
  kubernetes_sd_configs:
    - role: pod
      namespaces: { names: [wgh] }
  relabel_configs:
    - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
      action: keep
      regex: "true"
    - source_labels: [__meta_kubernetes_pod_ip, __meta_kubernetes_pod_annotation_prometheus_io_port]
      separator: ":"
      target_label: __address__
    - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
      target_label: __metrics_path__
    - source_labels: [__meta_kubernetes_pod_name]
      target_label: pod
    - target_label: job
      replacement: wgh-rag-backend
```

**告警规则接入**：K3 通过 `configMapGenerator` 直接引用
[deploy/prometheus/rules/wgh-rag-alerts.yml](../prometheus/rules/wgh-rag-alerts.yml)，生成带
`prometheus=wgh-rag` 标签的 ConfigMap（不复制内容）。消费方式二选一：

- Prometheus Operator：`Prometheus` 资源加 `ruleSelector: {matchLabels: {prometheus: wgh-rag}}`；
- 裸 Prometheus：把该 ConfigMap 挂到 `/etc/prometheus/rules/`，compose 版 `prometheus.yml` 的 `rule_files` 已含 `/etc/prometheus/rules/*.yml`，然后 `/-/reload`。

**日志（K0 遗留项）**：应用 logback 同时有 CONSOLE 与 FILE appender，因此
`kubectl logs` / 集群 stdout 采集**已经可用**，不需要改代码；
`/var/log/wgh-rag` 落在 emptyDir 上，只作单 Pod 排障，Pod 重建即丢。
集群侧用 DaemonSet 或云日志 agent 采集 stdout（容器运行时日志），保留策略由采集端负责。
若要彻底去掉文件 appender，属于 logback 配置改动，超出本清单范围。

### 10.7 发布与回滚

**顺序（不可颠倒）**

1. 备份/快照：发布前确认当日备份成功；有破坏性变更时先做一次全量 dump；
2. 清单外手动迁移：若本次涉及 `20261124/01`、`20261124/03` 这类未打包进 base 的脚本，先按 §10.2 执行并确认表/列存在；
3. 迁移 Job：`wgh-migrate` 必须完成且日志无误；
4. 后端滚动：`maxUnavailable: 0` / `maxSurge: 1`，3 副本下始终有 3 个就绪；
5. 前端滚动：无状态，最后做；
6. 观察 15 分钟：5xx 比例、`up`、PENDING 作业、SSE 建流。

为什么迁移必须先于滚动：新 Pod 启动即按新 schema 读写，缺表/缺列会直接 readiness 失败。
迁移脚本必须满足**向前兼容（expand-contract）**：先加表/列/索引，再切读写，最后在后续版本清理；
同一次发布里不允许 `DROP COLUMN` / `RENAME` 等破坏旧版本读的操作。

**金丝雀/蓝绿可行性**

| 方案                                     | 可行性        | 结论                                                                                             |
| -------------------------------------- | ---------- | ---------------------------------------------------------------------------------------------- |
| nginx ingress canary（header/cookie 分流） | 技术上可行      | 前后端共用 Schema，新旧版本必须同时兼容；SSE 已用 Cookie 亲和，canary 会把同一用户稳定打到新旧两个版本，需先完成 expand-contract。**暂不启用** |
| 单副本灰度（先扩 1 个新版本 Pod，观察 30 min）         | 可行         | 当前推荐做法：让单个 Pod 跑新版本需要绕过 Deployment，不可控；用滚动发布 + 30 min 观察窗口即可达到同等效果                             |
| 蓝绿（双 Deployment + 双 Service）           | 前端可行；后端成本高 | 需要 2 倍容量与 DB 双版本兼容，且不解决 schema 兼容问题，不作为常规手段                                                    |

在**前端接入 `Last-Event-ID` 自动重连之前**，不建议做流量比例金丝雀：生成中途被切到另一版本会直接丢进度。

**回滚预案**

```sh
kubectl -n wgh rollout undo deploy/wgh-backend
kubectl -n wgh rollout status deploy/wgh-backend --timeout=10m
kubectl -n wgh rollout history deploy/wgh-backend
```

- 镜像用 digest 固定，回滚是**精确**的（不会因为 tag 被覆盖而回滚到错误镜像）；
- 迁移 Job 没有 down 脚本：回滚只能靠应用向后兼容 + 数据库备份；
- **可回滚**：新增表、新增可空列/带默认值列、新增索引（旧代码不写这些结构，无影响）；
- **不可逆**：`DROP TABLE/COLUMN`、`ALTER COLUMN TYPE`、`SET NOT NULL`（已有脏数据时）、数据回填与删除、
  以及任何改写既有数据的迁移。这类变更必须单独一次发布，并在此之前完成可恢复备份；
- 已按关键字扫描：`sql/migrations/20261113`–`20261122` 与 `20261124` 现有脚本均为新增表/列/索引，无 `DROP`/`ALTER TYPE`；
  后续新增脚本必须按上面的分类在 MR 中标注（扫描只覆盖关键字，不替代逐脚本评审）。

### 10.8 运维交付物

**容量基线**

| 项        | 基线                                     | 依据                               |
| -------- | -------------------------------------- | -------------------------------- |
| backend  | 3–8 副本；requests 500m/1Gi；limits 2C/2Gi | base + HPA；堆 ≈ 65% × 2Gi ≈ 1.3Gi |
| frontend | 2 副本；50m/64Mi；limits 500m/256Mi        | 静态资源                             |
| PG 连接    | 122（15 × 8 + 2）                        | ≤ `max_connections × 0.7`，建议 200 |
| PG 存储    | 按知识库文件数 × 向量维度估算；预留 30%                | pgvector HNSW 索引会额外占空间           |
| 上传       | OSS（无集群磁盘上限）；本地 PVC 20Gi 仅聊天图片         | 见 §10.4                          |
| 备份       | dump ≈ 库大小 × 0.2–0.4，bucket 版本化 + 生命周期 | 见 §10.4                          |
| Redis    | 建议 2GB 起、主从                            | SSE 缓冲 + 限流键                     |
| 节点余量     | 至少 1 个节点可容纳 1 个 backend Pod（2C/2Gi）    | 支撑 maxSurge 与驱逐                  |

**故障 runbook**

| 场景          | 识别信号                                                      | 处置                                                                              | 验证                                           |
| ----------- | --------------------------------------------------------- | ------------------------------------------------------------------------------- | -------------------------------------------- |
| Pod 失联/反复重启 | `WghBackendInstanceDown`、`kubectl get pods` 非 Ready       | `describe` 看 events（探针/配额/镜像拉取）；`logs --previous`；确认 NetworkPolicy 未拦 9090 探针   | `up{job="wgh-rag-backend"}` 恢复；Pod 稳定 10 min |
| PG 抖动       | readiness 503、`WghHttp5xxRatioHigh`、`pg_stat_activity` 暴涨 | 先区分实例故障与连接耗尽；连接耗尽时临时下调 `PROD_DB_POOL_MAX` 并滚动；kill 长事务/空闲事务                     | 5xx 回落；PENDING 作业回补                          |
| Redis 不可用   | `WghStateStoreDegraded`、管理端 `store=memory`                | 接受降级（限流/锁定失真），必要时把入口限流交给 Ingress；多副本期间避免依赖精确限流                                  | `wgh_state_store_fallbacks_total` 停止增长       |
| SSE 长连接异常   | 生成中途断开、Ingress 499、单 Pod 连接数偏高                            | 核对 Ingress 超时 3600/`proxy-buffering: off` 未被覆盖；确认 Cookie 亲和生效；Pod 驱逐后用户需刷新      | 新建会话可流式输出且不整段缓冲                              |
| 调度实例失联      | 心跳超阈值、`WghSchedulerDeadLetter`/`PendingTooOld`            | 查 `wgh_scheduler_instance` 的 `last_heartbeat`/`stopped_at`；确认失联回收未造成重复执行；只读排查优先 | PENDING 下降、无重复执行（对账）                         |
| 备份失败        | Job `Failed`、bucket 无当日对象                                 | 看 `kubectl logs job/wgh-db-backup-oss-*`（凭据/网络/磁盘）；注意 443 与 PG 网段策略             | 手动重跑一次并跑恢复演练                                 |

**SLO 与现有 12 条告警的对应关系**

| #  | 告警                                  | 阈值                  | 对应 SLO/影响       | K3 注意点                            |
| -- | ----------------------------------- | ------------------- | --------------- | --------------------------------- |
| 1  | `WghBackendInstanceDown`            | 2 min               | 可用性（实例级）        | 3 副本下剩 2 个不触发，单实例仍报               |
| 2  | `WghBackendNoTargets`               | 5 min               | 可用性（全量）         | 抓取配置的 job 标签必须是 `wgh-rag-backend` |
| 3  | `WghStateStoreDegraded`             | 15 min 有回落          | 限流/锁定准确性        | Redis 单点期间持续告警，属预期                |
| 4  | `WghSchedulerJobsPendingHigh`       | PENDING > 50，10 min | 调度吞吐            | 扩容看 CPU，不看队列；必要时接自定义指标            |
| 5  | `WghSchedulerPendingTooOld`         | 最老 PENDING > 600s   | 交付时延            | 与 4 同时出现优先查执行器阻塞                  |
| 6  | `WghSchedulerDeadLetter`            | 1 h 有 DEAD          | 正确性（critical）   | 发布后新出现优先怀疑作业类型未注册                 |
| 7  | `WghWorkflowExecutorQueueSaturated` | 队列 > 50，5 min       | 工作流时延           | 与下游 LLM/检索耗时联动                    |
| 8  | `WghQuotaRefundFailed`              | 10 min 有失败          | 计费正确性（critical） | 与 `usage_epoch` 迁移配套检查            |
| 9  | `WghStorageOrphanGrowth`            | 1 h 有新增             | 成本/一致性          | OSS 版本化后孤儿对象成本上升，注意生命周期           |
| 10 | `WghStorageMissingReference`        | 1 h 有缺失             | 数据可用性（critical） | 切换存储后重点观察                         |
| 11 | `WghStorageOrphanDeleteFailed`      | 15 min 有失败          | 成本/权限           | 检查 AK 的 DeleteObject 权限           |
| 12 | `WghHttp5xxRatioHigh`               | 5xx > 5%，5 min      | 可用性（用户可感）       | 发布/迁移窗口的重点观测项                     |

SLO 建议：可用性 99.9%（按月，Ingress 5xx + 无目标时间折算）；调度 PENDING>50 持续 10 min 视为 SLO 事件；
备份 SLO 为「每日有且仅有 1 份成功备份，且每月恢复演练通过」。SSE 建流成功率暂不承诺
（前端未接自动重连，见 §5.2）。当前 12 条告警**没有**覆盖：PG 连接池饱和、备份 Job 失败、
证书过期、磁盘（OSS 无本地盘）——这几类属于 K3 之后的告警补齐项，不在本次清单内新增。

### 10.9 本地可验证与必须上生产集群

| 能力                         | 本地 kind 可验证              | 必须真实生产集群             |
| -------------------------- | ------------------------ | -------------------- |
| kustomize 渲染 / schema      | 是（本次已做）                  | —                    |
| NetworkPolicy 连通性          | 是（需 Calico/Cilium）       | 网段与命名空间真实值           |
| HPA 扩缩                     | 是（需 metrics-server，压测工具） | 真实流量下的阈值             |
| PDB / 滚动 / 探针              | 是                        | 跨可用区真实打散             |
| ResourceQuota / LimitRange | 是                        | 容量余量                 |
| PG 备份 / 恢复演练               | 是（kind 内 PG）             | 托管 PG 快照、PITR、RTO 实测 |
| 对象存储备份                     | 否（除非有真实 bucket 与凭据）      | OSS 版本化、生命周期、跨区复制    |
| TLS / Ingress 注解           | 部分（需 controller 与证书）     | 证书签发与强制跳转            |
| digest 拉取                  | 否（占位 digest）             | 私有仓库权限与多架构 digest    |

### 10.10 仍未覆盖（K3 之外）

1. GitOps（Argo CD/Flux）与多环境（dev/staging/prod）分离；
2. 准入策略（Kyverno/Gatekeeper）、镜像扫描与 SBOM、审计与合规证据；
3. PITR 的具体实现（依赖托管库或 CloudNativePG，涉及基础设施选型）；
4. 沙箱独立节点池与镜像白名单；
5. 跨地域灾备切换（当前只到备份可用 + 演练通过）；
6. 成本与容量看板（需与集群监控侧联调）。
