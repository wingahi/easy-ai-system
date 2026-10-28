# 设计模式重构方案

> 版本：v1.0｜日期：2026-10-28｜范围：rag-backend 10 个模块
> 关联文档：[overall-design.md](./overall-design.md)｜[detail-design.md](./detail-design.md)｜[test-cases.md](./test-cases.md)
> 原则：**不改变外部行为**（所有 e2e 测试保持绿色）、**小步重构**（每模式独立提交）、**有测试兜底**（重构前补单元测试）

---

## 1. 现状盘点：各模块设计模式应用与坏味道

| 模块 | 已应用模式 | 主要坏味道 | 风险等级 |
|------|-----------|-----------|---------|
| rag-common | — | Result/PageResult 手写 getter/setter 重复 | 低 |
| rag-config | — | RagProperties 平铺 50+ 字段，无分组 | 低 |
| rag-llm | 缓存（ModelCacheManager.computeIfAbsent） | Provider 模型构建 if-else 链（OpenAI/DashScope/DeepSeek/CUSTOM_OPENAI 分支散在一处） | **中** |
| rag-kb | — | 文档解析 if-else 按扩展名分发；切分策略（章节/段落/长度）耦在一个大方法 | **高** |
| rag-chat | — | ContextCompressionService 三级压缩（近期原始/中期摘要/远期事实）硬编码顺序调用 | **中** |
| rag-tool | ✅ 策略+SPI（ToolExecutor.supportedToolType 筛选） | 执行器注册靠 Spring 扫描隐式收集，无显式注册表；SkillToolExecutor 内部职责过重 | 低 |
| rag-agent | — | AgentOrchestrator 近 500 行：规划+调度+持久化+HITL+SSE 五合一（上帝类） | **高** |
| rag-workflow | — | WorkflowEngine.executeNode 9 分支 switch-case；NodeExecutor 与引擎耦合 | **高** |
| rag-agent-multi | ✅ 策略（execution_mode 路由 Coordinator） | MultiAgentContext.render 与 workflow VariableResolver 两套模板渲染逻辑重复 | 中 |
| rag-controller | — | Controller 中残留少量业务装配逻辑（如 MultiAgentController 手工 new Entity） | 低 |

---

## 2. 重构清单（按优先级）

### P0 — 消除上帝类与超长分支（影响可维护性最大）

#### R1. rag-workflow：`executeNode` switch-case → 策略注册表（Strategy + Registry）

**现状**：`WorkflowEngine.executeNode()` 中 9 个 case 分支（START/LLM/RETRIEVAL/TOOL/CONDITION/LOOP/HITL/VARIABLE/END）全部内联在引擎类里，新增节点类型必须改引擎。

**目标结构**：

```java
public interface NodeExecutor {                  // 策略接口
    String supportedType();                       // "LLM" / "CONDITION" / ...
    NodeResult execute(WorkflowNode node, RunContext ctx);
}

@Component public class LlmNodeExecutor implements NodeExecutor { ... }
@Component public class RetrievalNodeExecutor implements NodeExecutor { ... }
// ... 每类节点一个 Component

@Component
public class NodeExecutorRegistry {              // 注册表（Spring 注入所有实现）
    private final Map<String, NodeExecutor> executors;
    public NodeExecutorRegistry(List<NodeExecutor> list) {
        executors = list.stream().collect(toMap(NodeExecutor::supportedType, identity()));
    }
    public NodeExecutor get(String type) { ... }
}
```

**收益**：新增节点类型只需新增一个 `@Component`，引擎零改动（开闭原则）；每个执行器可独立单测。
**工作量**：2-3h｜**风险**：低（9 分支逐一平移，e2e 67 项兜底）

#### R2. rag-agent：`AgentOrchestrator` 拆分为 4 个协作类（Facade + Chain of Responsibility）

**现状**：Orchestrator 同时负责 plan 生成、拓扑排序、步骤调度、HITL 暂停恢复、SSE 推送、持久化。

**目标结构**：

```java
AgentOrchestrator (Facade, 只编排流程)
  ├─ PlanLifecycleService     // plan/step 持久化 + 状态机迁移
  ├─ StepScheduler            // 拓扑排序 + 就绪步骤选取（拓扑逻辑从 orchestrate 抽出）
  ├─ HitlGate                 // HITL 暂停/恢复/确认（现有逻辑平移）
  └─ AgentEventPublisher      // SSE 事件统一出口（Observer 模式）
```

**收益**：每类职责单一可单测；HITL 逻辑可被 rag-agent-multi 复用（当前多 Agent 模块重新实现了暂停恢复）。
**工作量**：4-6h｜**风险**：中（Agent e2e 34 项兜底，须逐方法平移）

#### R3. rag-kb：文档解析与切分 → 策略 + 工厂（Strategy + Simple Factory）

**现状**：按文件扩展名 if-else 选择解析器；切分策略（章节分割 > 段落分割 > 长度分割）以优先级硬编码在一个方法里。

**目标结构**：

```java
public interface DocumentParser {              // 解析策略
    boolean supports(String extension);         // "pdf" / "docx" / "txt" / "md"
    String parse(byte[] content);
}
@Component public class PdfParser implements DocumentParser { ... }
// ...

public interface ChunkingStrategy {            // 切分策略
    int priority();                             // 章节=1 段落=2 长度=3（替代硬编码 if 顺序）
    List<TextSegment> split(String text, SplitConfig cfg);
}
```

**收益**：新增格式（如 HTML 解析）或新切分策略（语义切分）零改动主链路；优先级由 `priority()` 声明而非代码顺序。
**工作量**：3-4h｜**风险**：中（知识库检索链路 e2e 兜底）

### P1 — 通用能力模式化（提升扩展性）

#### R4. rag-llm：Provider 模型构建 → 抽象工厂（Abstract Factory + Builder）

**现状**：`ModelCacheManager` / 模型服务中以 if-else 按 provider 构建 ChatModel（OPENAI/DASHSCOPE/DEEPSEEK/CUSTOM_OPENAI 分支），新增 Provider 需改多处。

**目标结构**：

```java
public interface ChatModelFactory {
    String supportedProvider();                 // "OPENAI" / "DASHSCOPE" / ...
    ChatModel build(ModelConfig cfg);
    StreamingChatModel buildStreaming(ModelConfig cfg);
    EmbeddingModel buildEmbedding(ModelConfig cfg);
}
@Component public class OpenAIModelFactory implements ChatModelFactory { ... }
@Component public class DashScopeModelFactory implements ChatModelFactory { ... }

// ModelCacheManager 改为:
ChatModel model = factoryRegistry.get(cfg.getProvider()).build(cfg);
```

**收益**：新增 Provider（如智谱/月之暗面）只加一个 Factory 类；构建参数收敛到一处，消除 API Key 拼接错误类 bug（历史教训：qwen-plus provider 错配导致 404）。
**工作量**：3-4h｜**风险**：中（所有 LLM 调用必经路径，需完整回归）

#### R5. rag-chat：三级压缩 → 责任链（Chain of Responsibility）

**现状**：`ContextCompressionService` 按固定顺序执行"近期原始→中期摘要→远期事实"，层级策略硬编码，新增层级（如"超远期归档"）需改核心方法。

**目标结构**：

```java
public interface CompressionHandler {
    boolean shouldHandle(CompressionContext ctx);   // 按消息年龄/数量判断
    CompressedBlock handle(List<ChatMessage> msgs, CompressionContext ctx);
    int order();                                     // 链上顺序
}
// RecentKeepHandler → SummaryHandler → FactExtractHandler
```

**收益**：层级可插拔；每级降级策略（LLM 异常→简单截断）封装在各自 handler 内。
**工作量**：2-3h｜**风险**：低（压缩有完整降级兜底）

#### R6. rag-agent-multi：模板渲染统一到 rag-common（DRY）

**现状**：`MultiAgentContext.render()` 与 `VariableResolver` 两套 `{{path}}` 渲染逻辑并存，行为有细微差异（MultiAgentContext 无 nodeOutputs 层）。

**目标**：提取 `TemplateRenderer` 到 rag-common，统一语法（`{{nodeId.text}}` / `{{agentId.text}}` / `{{vars.x}}` / `{{loop.iteration}}`），两处调用方共用。

**工作量**：2h｜**风险**：低（渲染行为需补齐双方现有用例的单测）

### P2 — 横切关注点模式化（锦上添花）

#### R7. SSE 事件推送 → 观察者模式统一出口（Observer）

**现状**：rag-agent / rag-workflow / rag-agent-multi 各自手写 `emitter.send(SseEmitter.event()...)`，事件名硬编码字符串散落各处，曾出现 SSE 换行符丢失 bug。

**目标结构**：

```java
public class DomainEventPublisher {              // rag-common
    public void publish(String channel, DomainEvent event);  // 统一 JSON 序列化 + 单行保证
}
public record DomainEvent(String name, Map<String,Object> payload, Instant ts) {}
// 事件名用枚举常量类：AgentEvents.STEP_START / WorkflowEvents.NODE_DONE / MultiAgentEvents.AGENT_START
```

**收益**：事件名编译期可检查；单行 JSON 约束集中到一处。
**工作量**：3h｜**风险**：低

#### R8. rag-config：RagProperties 分组（复合配置对象）

**现状**：50+ 字段平铺。改为分组嵌套：`rag.llm.*` / `rag.kb.*` / `rag.agent.*` / `rag.agent-multi.*` / `rag.workflow.*`，配合 `@ConfigurationProperties` 嵌套绑定。

**工作量**：1-2h｜**风险**：低（YAML 兼容需同步改 application.yml）

#### R9. rag-controller：业务装配下沉（Controller → Service）

**现状**：`MultiAgentController.createRun` 中手工 new MultiAgentRun、逐字段 set（约 40 行装配代码）。下沉为 `MultiAgentService.createRun(RunCreateRequest)`，Controller 只做参数解析与响应包装。

**工作量**：1h｜**风险**：低

### P3 — 不做的事

- **不引入 EventBus/MQ**：当前 SSE + 同步调用足够，异步消息化属于过度设计
- **不对 Entity 用 Builder 模式**：MyBatis-Plus 实体保持 JavaBean 风格（反射映射需要）
- **不重写 ModelCacheManager 为双层缓存**：单层 ConcurrentHashMap + computeIfAbsent 已满足需求

---

## 3. 实施顺序与验证矩阵

| 序号 | 重构项 | 预估 | 前置测试 | 验证方式 |
|------|--------|------|---------|---------|
| 1 | R6 模板渲染统一 | 2h | 补 VariableRenderer 单测 | workflow + multi-agent 用例回归 |
| 2 | R9 Controller 下沉 | 1h | MA-C/MA-R API 冒烟 | curl 冒烟 |
| 3 | R1 节点执行器注册表 | 3h | 先跑通 workflow e2e 67 项 | 全量 workflow e2e |
| 4 | R5 压缩责任链 | 3h | 补 CompressionService 单测 | 聊天 e2e + 单测 |
| 5 | R2 Orchestrator 拆分 | 6h | agent e2e 34 项先行 | 全量 agent e2e |
| 6 | R3 解析/切分策略化 | 4h | 补 KB 解析单测 | KB e2e |
| 7 | R4 模型工厂 | 4h | 模型连通性 e2e | 全量回归 |
| 8 | R7 SSE 统一出口 | 3h | SSE 事件断言脚本 | 全量 e2e |
| 9 | R8 配置分组 | 2h | 启动校验 | 启动 + 冒烟 |

**总工作量预估**：约 28 小时纯编码，按 9 个独立提交落地（每个重构项一个 commit，可独立回滚）。

---

## 4. 每个重构的统一执行纪律

1. **重构前先补测试**：针对将改的类补单元测试（覆盖当前行为，含边界）
2. **行为不变式**：所有现有 e2e 脚本必须保持绿色（test-workflow-e2e 67 项 / test-agent-e2e 18 项 / test-agent-hitl-e2e 16 项）
3. **小步提交**：一个模式一个 commit，commit message 格式 `refactor(module): 模式名 — 问题 → 方案`
4. **禁止顺手改**：重构提交中不夹带功能变更
