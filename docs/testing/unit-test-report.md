# 单元测试报告

- 执行日期：2026-11-04
- 执行人：自动化补充测试任务
- 代码库：`/Users/joe.wen/Desktop/ai-code/wgh-rag-system`（后端 `rag-backend`，前端 `rag-frontend`）
- JDK：`JAVA_HOME=$(/usr/libexec/java_home -v 21)`（JDK 21，符合 CI 硬约束）
- 构建方式：离线（`-o`）、持锁（`/tmp/wgh-maven-build.lockdir`）、`test` 阶段，未执行 `install/package/clean`，未覆盖运行中服务（8080，PID 41603）的 fat jar

## 一、盘点结论

### 1.1 覆盖率与报告配置

| 项 | 结论 |
| --- | --- |
| JaCoCo 插件 | **未配置**（所有 `pom.xml` 无 jacoco） |
| `target/site/jacoco` | **不存在**，无覆盖率数据 |
| Surefire | 默认配置；父 POM 显式排除 `**/*IntegrationTest.java`（因此新增测试类不得以 `IntegrationTest` 结尾） |
| 前端测试框架 | **无**（`rag-frontend/package.json` 无 `test` 脚本，未引入 vitest/jest） |

> 本次未为了覆盖率数字引入任何新插件。

### 1.2 测试类分布（当前源码统计，`find . -path '*/src/test/*' -name '*Test*.java'`）

| 模块 | 测试类数 |
| --- | --- |
| rag-app | 19 |
| rag-kb | 21 |
| rag-workflow | 13 |
| rag-chat | 10 |
| rag-agent-multi | 9 |
| rag-common / rag-llm | 各 7 |
| rag-auth | 6 |
| rag-agent / rag-storage | 各 3 |
| rag-controller / rag-quota | 各 1 |
| **合计** | **100**（本次新增 5 个） |

## 二、本次实际执行命令与结果

命令均在 `rag-backend` 目录下执行，前缀统一为：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

| # | 命令 | 结果 |
| --- | --- | --- |
| 1 | `mvn -o -pl rag-common test` | **37 tests / 0 fail / 0 error / 0 skip**, BUILD SUCCESS（10:52） |
| 2 | `mvn -o -pl rag-auth -am test` | **61 tests / 0 fail / 0 error / 0 skip**, BUILD SUCCESS（10:51） |
| 3 | `mvn -o -pl rag-kb -am test` | **120 tests / 0 fail / 0 error / 0 skip**, BUILD SUCCESS（10:53） |
| 4 | `mvn -o -pl rag-workflow -am test -Dtest=GraphValidatorTest -Dsurefire.failIfNoSpecifiedTests=false` | **10 tests / 0 fail / 0 error / 0 skip**, BUILD SUCCESS（10:53） |

说明：第 4 条只跑新增类，未重跑 `rag-workflow` 全量（`-am` 会连带 tool/agent 等上游模块，避免接近全量构建）。`rag-workflow` 其余 83 个用例沿用 2026-11-03 21:57 的存量报告（0 失败）。

### 2.1 本次新增的 5 个测试类

| 测试类 | 所属模块 | 用例数 | 覆盖的关键分支 |
| --- | --- | --- | --- |
| `OidcAuthenticationFilterLocalSessionTest` | rag-auth | **13** | 本地凭证 `pv` 版本失配（401/`1007`）、`pv` 为 null 回退 1、用户不存在/禁用/过期（401/`1002`）、凭证篡改、首登强制改密写请求 403/`1006`、读请求与改密接口放行、登录白名单、缺失 Bearer（401/`1001`）、无角色回退 `viewer` 且权限为空集、用户名以数据库为准 |
| `PermissionAspectTest` | rag-auth | **8** | `ANY`/`ALL` 两种模式、权限不足 `1004`、未登录、权限 null 不 NPE、方法级注解优先于类级注解、类级注解回退 |
| `GlobalExceptionHandlerTest` | rag-common | **10** | 未映射 `/api/**` → HTTP 404 + 业务码 `3001`、非 `/api` 静态资源保持空体 404、类型不匹配 400+`2001`、`@Valid` 校验失败 200+`2001`、约束校验、唯一键冲突 409+`3002`、权限拒绝 403+`1004`、`IllegalArgumentException` 200+`2001`、未知异常 500 且不回显内部信息 |
| `GraphValidatorTest` | rag-workflow | **10** | 空图、节点 id 缺失/重复/非法类型、START/END 数量与边约束、悬空连线、LLM/知识库/工具/条件/子流程必填配置、字符串型配置兼容、非法环、指向 LOOP 的回边豁免与 `backEdgeSources`、合法图入边计数 |
| `RagRetrieveServiceModelGroupingTest` | rag-kb | **5** | 不同 `embeddingModelId` 分组独立 embed 查询并 RRF 融合、`topK`/`minScore` 透传向量库请求、某组模型不可用时按组跳过、缺少 `collectionName/modelId` 返回空、空知识库列表不触发检索 |

**新增用例合计：36 个。**

## 三、存量测试报告快照（非本次执行，仅用于缺口判断）

| 模块 | tests | fail | err | skip | 最近报告时间 |
| --- | --- | --- | --- | --- | --- |
| rag-app | 210 | 0 | **7** | 0 | 2026-11-03 17:46 |
| rag-agent | 23 | 0 | 0 | 0 | 2026-11-03 21:57 |
| rag-agent-multi | 55 | 0 | 0 | 0 | 2026-11-03 17:46 |
| rag-chat | 59 | 0 | 0 | 0 | 2026-11-03 21:57 |
| rag-tool（报告位于 rag-app/target） | — | — | — | — | 同上 |
| rag-quota | 19 | 0 | 0 | 0 | 2026-11-03 21:56 |
| rag-config | 18 | 0 | 0 | 0 | 2026-11-03 16:09（对应源码已不存在，属历史残留报告） |
| rag-controller | 1 | 0 | 0 | 0 | 2026-11-03 17:46 |

`rag-app` 的 7 个 error 均为 **构建产物陈旧导致的编译错误类**（`Unresolved compilation problems: HttpConstants cannot be resolved`，涉及 `HttpToolExecutorTest` 的 6 个 `@Nested` 套件），不是业务断言失败，也不是本次新增测试引入。复现方式：在 `rag-app` 未重新编译 main 的情况下直接跑 test，JVM 加载了旧版错误 `.class`。处理建议见第五节。

## 四、缺口清单与后续建议

### 4.1 已确认覆盖充分（本次不重复补测）

- **OIDC/JWKS 验签**：算法白名单、iss/aud、fail-closed、kid 刷新、无 kid 回退已在 `OidcResourceServerTest`（18 例）覆盖。
- **配额解析优先级与并发 upsert**：`rag-quota` 19 例已覆盖优先级与并发更新。
- **公开访问限流/锁定/CIDR/可信代理**：`PublicAccessUnitTest` + `PublicAccessRedisSecurityTest` 已覆盖内存/Redis 降级、锁定解锁、IPv4/IPv6 CIDR、`X-Forwarded-For` 伪造、API Key 白名单。
- **存储 key 规则与路径穿越**：`rag-storage` 的 `StorageKeysTest`/`LocalFileStorageServiceTest` 已覆盖非法文件名、路径穿越与相对路径。
- **RAG 检索组装**：Rerank 候选放大与降级、混合检索 RRF、多查询变换、HYDE、topK 覆盖均已有测试；本次补充分组维度。
- **SSE 结束语义**：`rag-chat` 的 `ModelFailoverStreamTest` 等已覆盖流式降级路径。

### 4.2 仍存在的缺口（本次未补，按价值排序）

1. **前端完全无单元测试**：聊天渲染、引用去重、SSE 事件状态机、提示词前缀联想均依赖手工/浏览器验证。建议后续引入 vitest + @vue/test-utils（属新增依赖，需单独评审）。
2. **SSE 协议契约未单测**：`content` 事件 JSON 包装（防换行丢失）、`done/error` 终止语义目前靠集成/浏览器回归，建议后续补轻量单测。
3. **`rag-app` 装配层**：Controller 到 Service 的异常码映射（404/3001、400/2001、409/3002）只在 `GlobalExceptionHandler` 单测覆盖，未覆盖 Spring MVC 真实分发路径（需要 `MockMvc`，较慢）。
4. **工作流全量用例未在本次重跑**：`rag-workflow` 其余 83 例沿用 2026-11-03 报告，建议任务收尾时统一全量回归一次。

### 4.3 未发现需要修复的 main 代码 bug

本次新增的 36 个用例全部通过，未触发 `src/main` 缺陷。唯一异常现象是**陈旧编译产物**（`rag-auth/LocalLoginService.class`、`rag-app` 下 `HttpToolExecutorTest` 的 ECJ 错误类），已在执行中通过删除 `rag-auth/target/classes` 重编译解决；`rag-app` 同类问题未处理（当前禁止 `clean`，且为避免覆盖运行中 fat jar，未动 `rag-app`）。

复现（环境问题，非产品缺陷）：

```bash
# 现象：模块未重新编译 main，JVM 加载旧的 "Unresolved compilation problems" 错误类
mvn -o -pl rag-auth -am test
```

临时规避（已采用，仅删除构建产物，不动源码）：

```bash
rm -rf rag-auth/target/classes && mvn -o -pl rag-auth -am test
```

## 五、结论

- 新增 **5 个测试类 / 36 个用例**，全部通过。
- 本次转绿/保持全绿：`rag-common`（37）、`rag-auth`（61）、`rag-kb`（120）、`rag-workflow`（新增 10 例全绿）。
- 无新增失败；`rag-app` 的 7 个 error 为历史陈旧编译产物，非本次改动引入。
- 未引入 JaCoCo，故**无覆盖率数据**。
- 前端无测试框架，未新增依赖。
