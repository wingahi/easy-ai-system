-- ========================================================
-- WGH RAG System 数据库结构定义
-- PostgreSQL 15+ with pgvector
-- 包含所有 migration 合入后的完整 DDL + 索引 + 全字段 COMMENT
-- 用法：psql -d wgh_rag -f schema.sql  （已切换到目标库）
-- ========================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ============ LLM 模型 ============

CREATE TABLE IF NOT EXISTS wgh_llm_model (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  model_type VARCHAR(20) NOT NULL,
  provider VARCHAR(50) NOT NULL,
  model_name VARCHAR(100) NOT NULL,
  base_url VARCHAR(500),
  api_key VARCHAR(500),
  max_context_tokens INT DEFAULT 4096,
  temperature DOUBLE PRECISION DEFAULT 0.7,
  compression_model BOOLEAN DEFAULT FALSE,
  status SMALLINT DEFAULT 1,
  remark VARCHAR(500),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
COMMENT ON TABLE wgh_llm_model IS 'LLM 模型配置';
COMMENT ON COLUMN wgh_llm_model.id IS '主键';
COMMENT ON COLUMN wgh_llm_model.name IS '显示名称（如 通义 qwen-plus）';
COMMENT ON COLUMN wgh_llm_model.model_type IS '模型类型：CHAT / EMBEDDING';
COMMENT ON COLUMN wgh_llm_model.provider IS '提供厂商：DEEPSEEK / DASHSCOPE / CUSTOM_OPENAI 等';
COMMENT ON COLUMN wgh_llm_model.model_name IS '厂商侧模型名（API 请求用，如 qwen-plus / deepseek-chat）';
COMMENT ON COLUMN wgh_llm_model.base_url IS 'API Base URL';
COMMENT ON COLUMN wgh_llm_model.api_key IS 'API Key（加密存储，生产环境建议 KMS）';
COMMENT ON COLUMN wgh_llm_model.max_context_tokens IS '最大上下文 token 数';
COMMENT ON COLUMN wgh_llm_model.temperature IS '生成温度 0-2';
COMMENT ON COLUMN wgh_llm_model.compression_model IS '是否为分层压缩专用模型（0 否 / 1 是）';
COMMENT ON COLUMN wgh_llm_model.status IS '状态（1 启用 / 0 禁用）';
COMMENT ON COLUMN wgh_llm_model.remark IS '备注';
COMMENT ON COLUMN wgh_llm_model.create_time IS '创建时间';
COMMENT ON COLUMN wgh_llm_model.update_time IS '更新时间';
COMMENT ON COLUMN wgh_llm_model.create_by IS '创建人';
COMMENT ON COLUMN wgh_llm_model.update_by IS '更新人';
COMMENT ON COLUMN wgh_llm_model.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

CREATE TABLE IF NOT EXISTS wgh_embedding_model (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  provider VARCHAR(50) NOT NULL,
  model_name VARCHAR(100) NOT NULL,
  base_url VARCHAR(500),
  api_key VARCHAR(500),
  dimension INT NOT NULL,
  status SMALLINT DEFAULT 1,
  is_default SMALLINT DEFAULT 0,
  remark VARCHAR(500),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0,
  CONSTRAINT uk_embedding_default UNIQUE NULLS NOT DISTINCT (is_default)
);
COMMENT ON TABLE wgh_embedding_model IS 'Embedding 嵌入模型配置';
COMMENT ON COLUMN wgh_embedding_model.id IS '主键';
COMMENT ON COLUMN wgh_embedding_model.name IS '显示名称';
COMMENT ON COLUMN wgh_embedding_model.provider IS '提供厂商';
COMMENT ON COLUMN wgh_embedding_model.model_name IS '厂商侧模型名';
COMMENT ON COLUMN wgh_embedding_model.base_url IS 'API Base URL';
COMMENT ON COLUMN wgh_embedding_model.api_key IS 'API Key';
COMMENT ON COLUMN wgh_embedding_model.dimension IS '向量维度（如 1536）';
COMMENT ON COLUMN wgh_embedding_model.status IS '状态（1 启用 / 0 禁用）';
COMMENT ON COLUMN wgh_embedding_model.is_default IS '是否默认嵌入模型（唯一约束，仅一个默认）';
COMMENT ON COLUMN wgh_embedding_model.remark IS '备注';
COMMENT ON COLUMN wgh_embedding_model.create_time IS '创建时间';
COMMENT ON COLUMN wgh_embedding_model.update_time IS '更新时间';
COMMENT ON COLUMN wgh_embedding_model.create_by IS '创建人';
COMMENT ON COLUMN wgh_embedding_model.update_by IS '更新人';
COMMENT ON COLUMN wgh_embedding_model.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- ============ 知识库 ============

CREATE TABLE IF NOT EXISTS wgh_knowledge_base (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500),
  embedding_model_id BIGINT NOT NULL,
  dimension INT NOT NULL,
  collection_name VARCHAR(100) NOT NULL UNIQUE,
  file_count INT DEFAULT 0,
  chunk_count INT DEFAULT 0,
  status SMALLINT DEFAULT 1,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_kb_status_create ON wgh_knowledge_base(status, create_time);
COMMENT ON TABLE wgh_knowledge_base IS '知识库';
COMMENT ON COLUMN wgh_knowledge_base.id IS '主键';
COMMENT ON COLUMN wgh_knowledge_base.name IS '知识库名称';
COMMENT ON COLUMN wgh_knowledge_base.description IS '知识库描述';
COMMENT ON COLUMN wgh_knowledge_base.embedding_model_id IS '使用的嵌入模型 ID';
COMMENT ON COLUMN wgh_knowledge_base.dimension IS '向量维度（冗余存储，与嵌入模型一致）';
COMMENT ON COLUMN wgh_knowledge_base.collection_name IS 'pgvector 向量表名（唯一）';
COMMENT ON COLUMN wgh_knowledge_base.file_count IS '文件总数';
COMMENT ON COLUMN wgh_knowledge_base.chunk_count IS '向量 chunk 总数';
COMMENT ON COLUMN wgh_knowledge_base.status IS '状态（1 启用 / 0 禁用）';
COMMENT ON COLUMN wgh_knowledge_base.create_time IS '创建时间';
COMMENT ON COLUMN wgh_knowledge_base.update_time IS '更新时间';
COMMENT ON COLUMN wgh_knowledge_base.create_by IS '创建人';
COMMENT ON COLUMN wgh_knowledge_base.update_by IS '更新人';
COMMENT ON COLUMN wgh_knowledge_base.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

CREATE TABLE IF NOT EXISTS wgh_knowledge_file (
  id BIGSERIAL PRIMARY KEY,
  knowledge_base_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_type VARCHAR(20) NOT NULL,
  file_size BIGINT,
  file_path VARCHAR(500),
  split_strategy VARCHAR(20) DEFAULT 'PARAGRAPH',
  split_config TEXT,
  chunk_count INT DEFAULT 0,
  status VARCHAR(20) DEFAULT 'PENDING',
  error_message TEXT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_kfile_status ON wgh_knowledge_file(status);
CREATE INDEX IF NOT EXISTS idx_kfile_kb_status_create ON wgh_knowledge_file(knowledge_base_id, status, create_time);
COMMENT ON TABLE wgh_knowledge_file IS '知识库文件';
COMMENT ON COLUMN wgh_knowledge_file.id IS '主键';
COMMENT ON COLUMN wgh_knowledge_file.knowledge_base_id IS '所属知识库 ID';
COMMENT ON COLUMN wgh_knowledge_file.file_name IS '文件名';
COMMENT ON COLUMN wgh_knowledge_file.file_type IS '文件类型：PDF / DOCX / TXT / MD / HTML';
COMMENT ON COLUMN wgh_knowledge_file.file_size IS '文件大小（字节）';
COMMENT ON COLUMN wgh_knowledge_file.file_path IS '服务器存储路径';
COMMENT ON COLUMN wgh_knowledge_file.split_strategy IS '切分策略：PARAGRAPH / FIXED / RECURSIVE / SEMANTIC / AUTO';
COMMENT ON COLUMN wgh_knowledge_file.split_config IS '切分策略配置 JSON（如 max_length / overlap）';
COMMENT ON COLUMN wgh_knowledge_file.chunk_count IS '切分后 chunk 数';
COMMENT ON COLUMN wgh_knowledge_file.status IS '处理状态：PENDING / PARSING / SPLITTING / EMBEDDING / SUCCESS / FAILED';
COMMENT ON COLUMN wgh_knowledge_file.error_message IS '失败原因';
COMMENT ON COLUMN wgh_knowledge_file.create_time IS '创建时间';
COMMENT ON COLUMN wgh_knowledge_file.update_time IS '更新时间';
COMMENT ON COLUMN wgh_knowledge_file.create_by IS '创建人';
COMMENT ON COLUMN wgh_knowledge_file.update_by IS '更新人';
COMMENT ON COLUMN wgh_knowledge_file.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- ============ 聊天窗口 ============

CREATE TABLE IF NOT EXISTS wgh_chat_window (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500),
  llm_model_id BIGINT NOT NULL,
  knowledge_base_ids VARCHAR(500),
  system_prompt TEXT,
  share_token VARCHAR(100),
  share_enabled SMALLINT DEFAULT 0,
  status SMALLINT DEFAULT 1,
  tool_ids VARCHAR(500),
  max_history_messages INT DEFAULT 20,
  temperature DOUBLE PRECISION DEFAULT 0.7,
  evaluation_scheme VARCHAR(20) DEFAULT 'NONE',
  judge_model_ids VARCHAR(200),
  agent_enabled SMALLINT DEFAULT 0,
  agent_max_steps INT DEFAULT 15,
  agent_planner_model_id BIGINT,
  agent_executor_model_id BIGINT,
  agent_critic_model_ids VARCHAR(200),
  agent_reflection_threshold DOUBLE PRECISION DEFAULT 7.0,
  agent_hitl_enabled SMALLINT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_cw_share_token ON wgh_chat_window(share_token);
CREATE INDEX IF NOT EXISTS idx_cw_share_enabled ON wgh_chat_window(share_enabled);
CREATE INDEX IF NOT EXISTS idx_cw_status_create ON wgh_chat_window(status, create_time);
COMMENT ON TABLE wgh_chat_window IS '聊天窗口（对话配置模板）';
COMMENT ON COLUMN wgh_chat_window.id IS '主键';
COMMENT ON COLUMN wgh_chat_window.name IS '窗口名称';
COMMENT ON COLUMN wgh_chat_window.description IS '窗口描述';
COMMENT ON COLUMN wgh_chat_window.llm_model_id IS '使用的 Chat 模型 ID';
COMMENT ON COLUMN wgh_chat_window.knowledge_base_ids IS '关联的知识库 ID 列表（逗号分隔）';
COMMENT ON COLUMN wgh_chat_window.system_prompt IS '系统提示词';
COMMENT ON COLUMN wgh_chat_window.share_token IS '分享链接 token';
COMMENT ON COLUMN wgh_chat_window.share_enabled IS '是否开启分享（0 否 / 1 是）';
COMMENT ON COLUMN wgh_chat_window.status IS '状态（1 启用 / 0 禁用）';
COMMENT ON COLUMN wgh_chat_window.tool_ids IS '关联的工具 ID 列表（逗号分隔）';
COMMENT ON COLUMN wgh_chat_window.max_history_messages IS '历史消息阈值（超过触发分层压缩）';
COMMENT ON COLUMN wgh_chat_window.temperature IS '生成温度 0-2';
COMMENT ON COLUMN wgh_chat_window.evaluation_scheme IS 'RAG 评估方案：NONE / METRICS / LLM_JUDGE / BOTH';
COMMENT ON COLUMN wgh_chat_window.judge_model_ids IS 'LLM-as-judge 模型 ID 列表（JSON 数组），为空则自动选 2 个不同 provider';
COMMENT ON COLUMN wgh_chat_window.agent_enabled IS '是否启用 Agent 自动规划执行（0 否 / 1 是）';
COMMENT ON COLUMN wgh_chat_window.agent_max_steps IS 'Agent 计划最大步骤数（默认 15）';
COMMENT ON COLUMN wgh_chat_window.agent_planner_model_id IS 'Agent Planner 模型 ID（为空则继承 llm_model_id）';
COMMENT ON COLUMN wgh_chat_window.agent_executor_model_id IS 'Agent Step Executor 模型 ID（为空则继承 llm_model_id）';
COMMENT ON COLUMN wgh_chat_window.agent_critic_model_ids IS 'Agent Critic 模型 ID 列表（JSON 数组，反思评估用）';
COMMENT ON COLUMN wgh_chat_window.agent_reflection_threshold IS '反思评分阈值（0-10，低于此分触发重试）';
COMMENT ON COLUMN wgh_chat_window.agent_hitl_enabled IS '是否允许计划中 HITL（Human-In-The-Loop）节点（0 否 / 1 是）';
COMMENT ON COLUMN wgh_chat_window.create_time IS '创建时间';
COMMENT ON COLUMN wgh_chat_window.update_time IS '更新时间';
COMMENT ON COLUMN wgh_chat_window.create_by IS '创建人';
COMMENT ON COLUMN wgh_chat_window.update_by IS '更新人';
COMMENT ON COLUMN wgh_chat_window.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

CREATE TABLE IF NOT EXISTS wgh_chat_session (
  id BIGSERIAL PRIMARY KEY,
  chat_window_id BIGINT NOT NULL,
  title VARCHAR(200),
  user_identifier VARCHAR(100) NOT NULL,
  persistent SMALLINT DEFAULT 1,
  message_count INT DEFAULT 0,
  total_tokens INT DEFAULT 0,
  status VARCHAR(20) DEFAULT 'ACTIVE',
  mid_summary TEXT,
  mid_summary_round_start INTEGER,
  mid_summary_round_end INTEGER,
  mid_summary_model VARCHAR(128),
  mid_summary_prompt_version VARCHAR(16) DEFAULT 'v1',
  mid_summary_time TIMESTAMP,
  long_term_facts TEXT,
  long_term_facts_round_end INTEGER,
  long_term_facts_model VARCHAR(128),
  long_term_facts_prompt_version VARCHAR(16) DEFAULT 'v1',
  long_term_facts_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_cs_window_user_update ON wgh_chat_session(chat_window_id, user_identifier, update_time);
CREATE INDEX IF NOT EXISTS idx_cs_status ON wgh_chat_session(status);
COMMENT ON TABLE wgh_chat_session IS '聊天会话';
COMMENT ON COLUMN wgh_chat_session.id IS '主键';
COMMENT ON COLUMN wgh_chat_session.chat_window_id IS '所属聊天窗口 ID';
COMMENT ON COLUMN wgh_chat_session.title IS '会话标题（首轮 AI 回答自动生成或用户编辑）';
COMMENT ON COLUMN wgh_chat_session.user_identifier IS '用户标识（用户名或匿名 ID）';
COMMENT ON COLUMN wgh_chat_session.persistent IS '是否持久化（临时会话可能设为 0）';
COMMENT ON COLUMN wgh_chat_session.message_count IS '消息总数（含 system/user/assistant）';
COMMENT ON COLUMN wgh_chat_session.total_tokens IS '累计 token 消耗';
COMMENT ON COLUMN wgh_chat_session.status IS '状态：ACTIVE / ARCHIVED / DELETED';
COMMENT ON COLUMN wgh_chat_session.mid_summary IS '分层压缩：中期摘要正文（LLM 生成的 3-5 句对话进展）';
COMMENT ON COLUMN wgh_chat_session.mid_summary_round_start IS '分层压缩：中期摘要覆盖的起始轮次（0-based, inclusive）';
COMMENT ON COLUMN wgh_chat_session.mid_summary_round_end IS '分层压缩：中期摘要覆盖的结束轮次（exclusive）';
COMMENT ON COLUMN wgh_chat_session.mid_summary_model IS '分层压缩：生成中期摘要的模型名';
COMMENT ON COLUMN wgh_chat_session.mid_summary_prompt_version IS '分层压缩：中期摘要 prompt 版本号，变更 prompt 时 bump 使旧压缩失效';
COMMENT ON COLUMN wgh_chat_session.mid_summary_time IS '分层压缩：中期摘要生成时间';
COMMENT ON COLUMN wgh_chat_session.long_term_facts IS '分层压缩：远期关键事实（LLM 提取的 bullet list）';
COMMENT ON COLUMN wgh_chat_session.long_term_facts_round_end IS '分层压缩：远期事实覆盖到的轮次（exclusive）';
COMMENT ON COLUMN wgh_chat_session.long_term_facts_model IS '分层压缩：生成远期事实的模型名';
COMMENT ON COLUMN wgh_chat_session.long_term_facts_prompt_version IS '分层压缩：远期事实 prompt 版本号';
COMMENT ON COLUMN wgh_chat_session.long_term_facts_time IS '分层压缩：远期事实生成时间';
COMMENT ON COLUMN wgh_chat_session.create_time IS '创建时间';
COMMENT ON COLUMN wgh_chat_session.update_time IS '更新时间';
COMMENT ON COLUMN wgh_chat_session.create_by IS '创建人';
COMMENT ON COLUMN wgh_chat_session.update_by IS '更新人';
COMMENT ON COLUMN wgh_chat_session.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

CREATE TABLE IF NOT EXISTS wgh_chat_message (
  id BIGSERIAL PRIMARY KEY,
  session_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  content TEXT,
  message_type VARCHAR(20) DEFAULT 'TEXT',
  token_count INT DEFAULT 0,
  recall_rate DOUBLE PRECISION,
  hit_rate DOUBLE PRECISION,
  accuracy_rate DOUBLE PRECISION,
  sources TEXT,
  mrr DOUBLE PRECISION,
  ndcg DOUBLE PRECISION,
  faithfulness DOUBLE PRECISION,
  answer_relevancy DOUBLE PRECISION,
  judge_agreement DOUBLE PRECISION,
  judge_details TEXT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_cm_session_create ON wgh_chat_message(session_id, create_time);
COMMENT ON TABLE wgh_chat_message IS '聊天消息';
COMMENT ON COLUMN wgh_chat_message.id IS '主键';
COMMENT ON COLUMN wgh_chat_message.session_id IS '所属会话 ID';
COMMENT ON COLUMN wgh_chat_message.role IS '角色：SYSTEM / USER / ASSISTANT';
COMMENT ON COLUMN wgh_chat_message.content IS '消息文本';
COMMENT ON COLUMN wgh_chat_message.message_type IS '消息类型：TEXT / IMAGE / FILE / TOOL_CALL';
COMMENT ON COLUMN wgh_chat_message.token_count IS 'token 数';
COMMENT ON COLUMN wgh_chat_message.recall_rate IS '检索召回率（有 ground truth 时）';
COMMENT ON COLUMN wgh_chat_message.hit_rate IS '命中率（检索结果中相关 chunk 占比）';
COMMENT ON COLUMN wgh_chat_message.accuracy_rate IS '准确率';
COMMENT ON COLUMN wgh_chat_message.sources IS '被引用的检索来源 JSON';
COMMENT ON COLUMN wgh_chat_message.mrr IS 'MRR（Mean Reciprocal Rank）：第一个被引用 chunk 的检索排名倒数';
COMMENT ON COLUMN wgh_chat_message.ndcg IS 'NDCG：被引用 chunks 的归一化折损增益';
COMMENT ON COLUMN wgh_chat_message.faithfulness IS '忠实度（多 judge 平均）：回答被检索内容支持的程度';
COMMENT ON COLUMN wgh_chat_message.answer_relevancy IS '答案相关性（多 judge 平均）：回答与问题的相关程度';
COMMENT ON COLUMN wgh_chat_message.judge_agreement IS 'judge 间一致性：1 - 平均绝对差';
COMMENT ON COLUMN wgh_chat_message.judge_details IS 'judge 详细结果 JSON 数组（每个 judge 的模型、分数、理由）';
COMMENT ON COLUMN wgh_chat_message.create_time IS '创建时间';
COMMENT ON COLUMN wgh_chat_message.update_time IS '更新时间';
COMMENT ON COLUMN wgh_chat_message.create_by IS '创建人';
COMMENT ON COLUMN wgh_chat_message.update_by IS '更新人';
COMMENT ON COLUMN wgh_chat_message.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- ============ 工具配置 ============

CREATE TABLE IF NOT EXISTS wgh_tool (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  tool_type VARCHAR(30) NOT NULL,
  description VARCHAR(500),
  definition TEXT,
  executor_config TEXT,
  status SMALLINT DEFAULT 1,
  is_example SMALLINT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_tool_name ON wgh_tool(name);
CREATE INDEX IF NOT EXISTS idx_tool_status ON wgh_tool(status);
CREATE INDEX IF NOT EXISTS idx_tool_type_status ON wgh_tool(tool_type, status);
COMMENT ON TABLE wgh_tool IS '工具配置';
COMMENT ON COLUMN wgh_tool.id IS '主键';
COMMENT ON COLUMN wgh_tool.name IS '工具名称';
COMMENT ON COLUMN wgh_tool.tool_type IS '工具类型：SKILL / MCP / HTTP / FUNCTION_CALLING';
COMMENT ON COLUMN wgh_tool.description IS '工具描述（LLM function calling description）';
COMMENT ON COLUMN wgh_tool.definition IS '工具定义 JSON（function schema / MCP input schema 等）';
COMMENT ON COLUMN wgh_tool.executor_config IS '执行器配置 JSON（endpoint / handler class / timeout 等）';
COMMENT ON COLUMN wgh_tool.status IS '状态（1 启用 / 0 禁用）';
COMMENT ON COLUMN wgh_tool.is_example IS '是否示例数据（0 否 / 1 是）';
COMMENT ON COLUMN wgh_tool.create_time IS '创建时间';
COMMENT ON COLUMN wgh_tool.update_time IS '更新时间';
COMMENT ON COLUMN wgh_tool.create_by IS '创建人';
COMMENT ON COLUMN wgh_tool.update_by IS '更新人';
COMMENT ON COLUMN wgh_tool.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- ============ Agent 计划 ============

CREATE TABLE IF NOT EXISTS wgh_agent_plan (
  id BIGSERIAL PRIMARY KEY,
  session_id BIGINT NOT NULL,
  chat_window_id BIGINT NOT NULL,
  llm_model_id BIGINT NOT NULL,
  status VARCHAR(20) DEFAULT 'PLANNING',
  user_goal TEXT NOT NULL,
  plan_json JSONB NOT NULL,
  current_step_index INT DEFAULT 0,
  max_steps INT DEFAULT 15,
  total_tokens INT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  finish_time TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_agent_plan_session ON wgh_agent_plan(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_plan_status ON wgh_agent_plan(status);
COMMENT ON TABLE wgh_agent_plan IS 'Agent 执行计划';
COMMENT ON COLUMN wgh_agent_plan.session_id IS '关联的 chat_session';
COMMENT ON COLUMN wgh_agent_plan.chat_window_id IS '关联的 chat_window';
COMMENT ON COLUMN wgh_agent_plan.llm_model_id IS 'Planner 使用的 LLM 模型 ID';
COMMENT ON COLUMN wgh_agent_plan.status IS '状态：PLANNING / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED';
COMMENT ON COLUMN wgh_agent_plan.user_goal IS '用户原始输入（Agent 目标）';
COMMENT ON COLUMN wgh_agent_plan.plan_json IS 'Planner 生成的计划 JSON（含 steps 列表、依赖关系、工具声明）';
COMMENT ON COLUMN wgh_agent_plan.current_step_index IS '当前执行到的步骤序号（0-based）';
COMMENT ON COLUMN wgh_agent_plan.max_steps IS '计划最大步骤数（兜底）';
COMMENT ON COLUMN wgh_agent_plan.total_tokens IS '本次计划累计消耗 token';
COMMENT ON COLUMN wgh_agent_plan.finish_time IS '计划完成/失败时间';
COMMENT ON COLUMN wgh_agent_plan.create_time IS '创建时间';
COMMENT ON COLUMN wgh_agent_plan.update_time IS '更新时间';
COMMENT ON COLUMN wgh_agent_plan.deleted IS '逻辑删除';

-- ============ Agent 步骤 ============

CREATE TABLE IF NOT EXISTS wgh_agent_step (
  id BIGSERIAL PRIMARY KEY,
  plan_id BIGINT NOT NULL,
  step_index INT NOT NULL,
  step_id VARCHAR(32) NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  depends_on TEXT,
  status VARCHAR(20) DEFAULT 'PENDING',
  thought TEXT,
  action_tool VARCHAR(100),
  action_input TEXT,
  observation TEXT,
  reflection_score DOUBLE PRECISION,
  reflection_feedback TEXT,
  retry_count INT DEFAULT 0,
  max_retries INT DEFAULT 3,
  result_summary TEXT,
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  token_count INT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_agent_step_plan ON wgh_agent_step(plan_id);
CREATE INDEX IF NOT EXISTS idx_agent_step_status ON wgh_agent_step(status);
COMMENT ON TABLE wgh_agent_step IS 'Agent 计划中的单个步骤';
COMMENT ON COLUMN wgh_agent_step.plan_id IS '所属计划 ID';
COMMENT ON COLUMN wgh_agent_step.step_index IS '计划内序号（0-based）';
COMMENT ON COLUMN wgh_agent_step.step_id IS '计划 JSON 中的唯一 stepId（如 step_1）';
COMMENT ON COLUMN wgh_agent_step.title IS '步骤标题（Planner 生成）';
COMMENT ON COLUMN wgh_agent_step.description IS '详细说明';
COMMENT ON COLUMN wgh_agent_step.depends_on IS '依赖的 step_id 列表（JSON 数组）';
COMMENT ON COLUMN wgh_agent_step.status IS '状态：PENDING / READY / RUNNING / SUCCESS / FAILED / SKIPPED / HITL_PENDING';
COMMENT ON COLUMN wgh_agent_step.thought IS 'LLM 的推理过程（最近一次）';
COMMENT ON COLUMN wgh_agent_step.action_tool IS '调用的工具名（如有）';
COMMENT ON COLUMN wgh_agent_step.action_input IS '工具调用输入';
COMMENT ON COLUMN wgh_agent_step.observation IS '工具返回 / RAG 检索 / LLM 输出';
COMMENT ON COLUMN wgh_agent_step.reflection_score IS '反思评估分数（0-10）';
COMMENT ON COLUMN wgh_agent_step.reflection_feedback IS '反思反馈';
COMMENT ON COLUMN wgh_agent_step.retry_count IS '已重试次数';
COMMENT ON COLUMN wgh_agent_step.max_retries IS '最大重试次数';
COMMENT ON COLUMN wgh_agent_step.result_summary IS '步骤产出摘要（传给后续步骤的已知事实）';
COMMENT ON COLUMN wgh_agent_step.start_time IS '开始执行时间';
COMMENT ON COLUMN wgh_agent_step.end_time IS '完成时间';
COMMENT ON COLUMN wgh_agent_step.token_count IS '本步骤消耗 token';
COMMENT ON COLUMN wgh_agent_step.create_time IS '创建时间';
COMMENT ON COLUMN wgh_agent_step.update_time IS '更新时间';
COMMENT ON COLUMN wgh_agent_step.deleted IS '逻辑删除';

-- ============ 工作流定义 ============

CREATE TABLE IF NOT EXISTS wgh_workflow (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500),
  status SMALLINT DEFAULT 1,
  version INT DEFAULT 1,
  graph_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_workflow_status_update ON wgh_workflow(status, update_time);
COMMENT ON TABLE wgh_workflow IS '可视化工作流定义';
COMMENT ON COLUMN wgh_workflow.id IS '主键';
COMMENT ON COLUMN wgh_workflow.name IS '工作流名称';
COMMENT ON COLUMN wgh_workflow.description IS '描述';
COMMENT ON COLUMN wgh_workflow.status IS '状态（1 启用 / 0 停用）';
COMMENT ON COLUMN wgh_workflow.version IS '版本号，每次保存 +1';
COMMENT ON COLUMN wgh_workflow.graph_json IS '图定义 JSON：{ nodes: [...], edges: [...] }';
COMMENT ON COLUMN wgh_workflow.create_time IS '创建时间';
COMMENT ON COLUMN wgh_workflow.update_time IS '更新时间';
COMMENT ON COLUMN wgh_workflow.create_by IS '创建人';
COMMENT ON COLUMN wgh_workflow.update_by IS '更新人';
COMMENT ON COLUMN wgh_workflow.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- ============ 工作流版本快照 ============

CREATE TABLE IF NOT EXISTS wgh_workflow_version (
  id BIGSERIAL PRIMARY KEY,
  workflow_id BIGINT NOT NULL,
  version INT NOT NULL,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500),
  graph_json JSONB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wf_version_wf_ver ON wgh_workflow_version(workflow_id, version);
COMMENT ON TABLE wgh_workflow_version IS '工作流版本快照（每次保存生成一条，回滚亦生成新版本）';
COMMENT ON COLUMN wgh_workflow_version.id IS '主键';
COMMENT ON COLUMN wgh_workflow_version.workflow_id IS '所属工作流 ID';
COMMENT ON COLUMN wgh_workflow_version.version IS '版本号（与 wgh_workflow.version 对齐）';
COMMENT ON COLUMN wgh_workflow_version.name IS '保存时的名称快照';
COMMENT ON COLUMN wgh_workflow_version.description IS '保存时的描述快照';
COMMENT ON COLUMN wgh_workflow_version.graph_json IS '保存时的图定义快照';
COMMENT ON COLUMN wgh_workflow_version.create_time IS '创建时间';
COMMENT ON COLUMN wgh_workflow_version.update_time IS '更新时间';
COMMENT ON COLUMN wgh_workflow_version.create_by IS '创建人';
COMMENT ON COLUMN wgh_workflow_version.update_by IS '更新人';
COMMENT ON COLUMN wgh_workflow_version.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- ============ 工作流运行实例 ============

CREATE TABLE IF NOT EXISTS wgh_workflow_run (
  id BIGSERIAL PRIMARY KEY,
  workflow_id BIGINT NOT NULL,
  workflow_version INT,
  input_text TEXT,
  status VARCHAR(20) DEFAULT 'RUNNING',
  output_text TEXT,
  context_json JSONB,
  graph_snapshot JSONB,
  error_message TEXT,
  executed_nodes INT DEFAULT 0,
  total_nodes INT DEFAULT 0,
  start_time TIMESTAMP,
  finish_time TIMESTAMP,
  duration_ms BIGINT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wf_run_workflow_start ON wgh_workflow_run(workflow_id, start_time);
CREATE INDEX IF NOT EXISTS idx_wf_run_status ON wgh_workflow_run(status);
COMMENT ON TABLE wgh_workflow_run IS '工作流运行实例';
COMMENT ON COLUMN wgh_workflow_run.id IS '主键';
COMMENT ON COLUMN wgh_workflow_run.workflow_id IS '所属工作流 ID';
COMMENT ON COLUMN wgh_workflow_run.workflow_version IS '运行时的版本号快照';
COMMENT ON COLUMN wgh_workflow_run.input_text IS '运行输入';
COMMENT ON COLUMN wgh_workflow_run.status IS '状态：RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED';
COMMENT ON COLUMN wgh_workflow_run.output_text IS '最终输出';
COMMENT ON COLUMN wgh_workflow_run.context_json IS '运行上下文（变量池 / 节点输出 / 待恢复节点）';
COMMENT ON COLUMN wgh_workflow_run.graph_snapshot IS '运行时图快照，保证编辑后旧运行仍可恢复';
COMMENT ON COLUMN wgh_workflow_run.error_message IS '失败原因';
COMMENT ON COLUMN wgh_workflow_run.executed_nodes IS '已执行节点数';
COMMENT ON COLUMN wgh_workflow_run.total_nodes IS '图节点总数';
COMMENT ON COLUMN wgh_workflow_run.start_time IS '开始时间';
COMMENT ON COLUMN wgh_workflow_run.finish_time IS '结束时间';
COMMENT ON COLUMN wgh_workflow_run.duration_ms IS '总耗时（毫秒）';
COMMENT ON COLUMN wgh_workflow_run.create_time IS '创建时间';
COMMENT ON COLUMN wgh_workflow_run.update_time IS '更新时间';
COMMENT ON COLUMN wgh_workflow_run.create_by IS '创建人';
COMMENT ON COLUMN wgh_workflow_run.update_by IS '更新人';
COMMENT ON COLUMN wgh_workflow_run.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- ============ 工作流节点执行记录 ============

CREATE TABLE IF NOT EXISTS wgh_workflow_node_run (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL,
  node_id VARCHAR(64) NOT NULL,
  node_type VARCHAR(30) NOT NULL,
  node_name VARCHAR(100),
  status VARCHAR(20) DEFAULT 'PENDING',
  iteration INT DEFAULT 0,
  input_json JSONB,
  output_json JSONB,
  error_message TEXT,
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  duration_ms BIGINT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wf_node_run_run ON wgh_workflow_node_run(run_id, id);
CREATE INDEX IF NOT EXISTS idx_wf_node_run_status ON wgh_workflow_node_run(status);
COMMENT ON TABLE wgh_workflow_node_run IS '工作流节点级执行记录';
COMMENT ON COLUMN wgh_workflow_node_run.id IS '主键';
COMMENT ON COLUMN wgh_workflow_node_run.run_id IS '所属运行 ID';
COMMENT ON COLUMN wgh_workflow_node_run.node_id IS '图中节点 ID';
COMMENT ON COLUMN wgh_workflow_node_run.node_type IS '节点类型';
COMMENT ON COLUMN wgh_workflow_node_run.node_name IS '节点显示名';
COMMENT ON COLUMN wgh_workflow_node_run.status IS '状态：PENDING / RUNNING / SUCCESS / FAILED / SKIPPED / HITL_PENDING';
COMMENT ON COLUMN wgh_workflow_node_run.iteration IS '循环体第几次执行（非循环节点为 0）';
COMMENT ON COLUMN wgh_workflow_node_run.input_json IS '渲染后的节点入参';
COMMENT ON COLUMN wgh_workflow_node_run.output_json IS '节点出参';
COMMENT ON COLUMN wgh_workflow_node_run.error_message IS '失败原因';
COMMENT ON COLUMN wgh_workflow_node_run.start_time IS '开始时间';
COMMENT ON COLUMN wgh_workflow_node_run.end_time IS '结束时间';
COMMENT ON COLUMN wgh_workflow_node_run.duration_ms IS '耗时（毫秒）';
COMMENT ON COLUMN wgh_workflow_node_run.create_time IS '创建时间';
COMMENT ON COLUMN wgh_workflow_node_run.update_time IS '更新时间';
COMMENT ON COLUMN wgh_workflow_node_run.create_by IS '创建人';
COMMENT ON COLUMN wgh_workflow_node_run.update_by IS '更新人';
COMMENT ON COLUMN wgh_workflow_node_run.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

