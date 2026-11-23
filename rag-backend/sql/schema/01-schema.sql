-- ========================================================
-- WGH RAG System 数据库结构定义（新库基线）
-- PostgreSQL 15+ with pgvector
-- 包含完整 DDL + 索引 + 全字段 COMMENT，不依赖任何历史库状态
-- 用法：psql -d wgh_rag -f rag-backend/sql/schema/01-schema.sql  （已切换到目标库）
-- ========================================================

CREATE EXTENSION IF NOT EXISTS vector;
-- 混合检索关键词路（pg_trgm 相似度），postgres-with-vector 镜像自带
CREATE EXTENSION IF NOT EXISTS pg_trgm;

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
  compression_model SMALLINT DEFAULT 0,
  supports_vision SMALLINT DEFAULT 0,
  capability_tier SMALLINT NOT NULL DEFAULT 3,
  supports_tools SMALLINT NOT NULL DEFAULT 1,
  route_group VARCHAR(50),
  route_priority INT DEFAULT 0,
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
COMMENT ON COLUMN wgh_llm_model.model_type IS '模型类型：CHAT / EMBEDDING / RERANK';
COMMENT ON COLUMN wgh_llm_model.provider IS '提供厂商：DEEPSEEK / DASHSCOPE / CUSTOM_OPENAI / CUSTOM_RERANK 等';
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
  deleted SMALLINT DEFAULT 0
);
-- 仅允许一个未删除的默认嵌入模型；非默认模型可任意多个
CREATE UNIQUE INDEX IF NOT EXISTS uk_embedding_default ON wgh_embedding_model(is_default) WHERE is_default = 1 AND deleted = 0;
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
  visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
  rebuild_status VARCHAR(20) DEFAULT '',
  rebuild_total INT DEFAULT 0,
  rebuild_processed INT DEFAULT 0,
  pending_collection VARCHAR(100),
  pending_embedding_model_id BIGINT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_kb_status_create ON wgh_knowledge_base(status, create_time);
CREATE INDEX IF NOT EXISTS idx_kb_owner_visibility ON wgh_knowledge_base(create_by, visibility);
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
COMMENT ON COLUMN wgh_knowledge_base.visibility IS '可见性：PRIVATE 仅自己 / TEAM 团队可读 / PUBLIC 公开可读';
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
  content_hash VARCHAR(64),
  version INT DEFAULT 1,
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
COMMENT ON COLUMN wgh_knowledge_file.content_hash IS '文件内容 SHA-256（同知识库内重复内容去重）';
COMMENT ON COLUMN wgh_knowledge_file.create_time IS '创建时间';
COMMENT ON COLUMN wgh_knowledge_file.update_time IS '更新时间';
COMMENT ON COLUMN wgh_knowledge_file.create_by IS '创建人';
COMMENT ON COLUMN wgh_knowledge_file.update_by IS '更新人';
COMMENT ON COLUMN wgh_knowledge_file.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- ============ 检索评测标注 ============

CREATE TABLE IF NOT EXISTS wgh_kb_ground_truth (
  id BIGSERIAL PRIMARY KEY,
  knowledge_base_id BIGINT NOT NULL,
  query_text VARCHAR(500) NOT NULL,
  relevant_chunks TEXT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_gt_kb ON wgh_kb_ground_truth(knowledge_base_id, deleted);
COMMENT ON TABLE wgh_kb_ground_truth IS '检索评测 ground truth（标注每个查询的相关 chunk，用于计算真实 Recall@K）';
COMMENT ON COLUMN wgh_kb_ground_truth.id IS '主键';
COMMENT ON COLUMN wgh_kb_ground_truth.knowledge_base_id IS '所属知识库 ID';
COMMENT ON COLUMN wgh_kb_ground_truth.query_text IS '评测查询';
COMMENT ON COLUMN wgh_kb_ground_truth.relevant_chunks IS '相关 chunk 键集合 JSON 数组，元素形如 "fileId:chunkIndex"';
COMMENT ON COLUMN wgh_kb_ground_truth.create_time IS '创建时间';
COMMENT ON COLUMN wgh_kb_ground_truth.update_time IS '更新时间';
COMMENT ON COLUMN wgh_kb_ground_truth.create_by IS '创建人';
COMMENT ON COLUMN wgh_kb_ground_truth.update_by IS '更新人';
COMMENT ON COLUMN wgh_kb_ground_truth.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- ============ 评测数据集（版本化） ============

-- 数据集 = 某个知识库下的一组评测用例；current_version 为当前生效版本号（0 表示空数据集）
CREATE TABLE IF NOT EXISTS wgh_eval_dataset (
  id BIGSERIAL PRIMARY KEY,
  knowledge_base_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500),
  current_version INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_eval_dataset_kb ON wgh_eval_dataset(knowledge_base_id, deleted);
COMMENT ON TABLE wgh_eval_dataset IS '评测数据集（版本化：每次用例变更生成新版本快照）';
COMMENT ON COLUMN wgh_eval_dataset.id IS '主键';
COMMENT ON COLUMN wgh_eval_dataset.knowledge_base_id IS '所属知识库 ID';
COMMENT ON COLUMN wgh_eval_dataset.name IS '数据集名称';
COMMENT ON COLUMN wgh_eval_dataset.description IS '数据集说明';
COMMENT ON COLUMN wgh_eval_dataset.current_version IS '当前版本号（0=空数据集；每次用例变更 +1）';
COMMENT ON COLUMN wgh_eval_dataset.create_time IS '创建时间';
COMMENT ON COLUMN wgh_eval_dataset.update_time IS '更新时间';
COMMENT ON COLUMN wgh_eval_dataset.create_by IS '创建人';
COMMENT ON COLUMN wgh_eval_dataset.update_by IS '更新人';
COMMENT ON COLUMN wgh_eval_dataset.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- 用例快照：同一 (dataset_id, dataset_version) 下为一份不可变用例集合，旧版本只读
CREATE TABLE IF NOT EXISTS wgh_eval_case (
  id BIGSERIAL PRIMARY KEY,
  dataset_id BIGINT NOT NULL,
  dataset_version INT NOT NULL,
  query_text VARCHAR(500) NOT NULL,
  relevant_chunks TEXT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_eval_case_ds_version ON wgh_eval_case(dataset_id, dataset_version, deleted);
COMMENT ON TABLE wgh_eval_case IS '评测用例版本快照（同一版本内不可变，用于历史结果对比）';
COMMENT ON COLUMN wgh_eval_case.id IS '主键';
COMMENT ON COLUMN wgh_eval_case.dataset_id IS '所属数据集 ID';
COMMENT ON COLUMN wgh_eval_case.dataset_version IS '所属数据集版本号';
COMMENT ON COLUMN wgh_eval_case.query_text IS '评测查询';
COMMENT ON COLUMN wgh_eval_case.relevant_chunks IS '相关 chunk 键集合 JSON 数组，元素形如 "fileId:chunkIndex"';
COMMENT ON COLUMN wgh_eval_case.create_time IS '创建时间';
COMMENT ON COLUMN wgh_eval_case.update_time IS '更新时间';
COMMENT ON COLUMN wgh_eval_case.create_by IS '创建人';
COMMENT ON COLUMN wgh_eval_case.update_by IS '更新人';
COMMENT ON COLUMN wgh_eval_case.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- 评测结果归档：一条记录对应某数据集某版本的一次评测
CREATE TABLE IF NOT EXISTS wgh_eval_result (
  id BIGSERIAL PRIMARY KEY,
  dataset_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  dataset_version INT NOT NULL,
  top_k INT,
  hybrid SMALLINT DEFAULT 0,
  query_count INT DEFAULT 0,
  recall_at_k DOUBLE PRECISION,
  precision_at_k DOUBLE PRECISION,
  mrr DOUBLE PRECISION,
  ndcg DOUBLE PRECISION,
  query_hit_rate DOUBLE PRECISION,
  coverage_rate DOUBLE PRECISION,
  details_json TEXT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_eval_result_ds_version ON wgh_eval_result(dataset_id, dataset_version, deleted);
COMMENT ON TABLE wgh_eval_result IS '评测结果归档（按数据集版本留存指标与逐查询明细，支持跨版本对比）';
COMMENT ON COLUMN wgh_eval_result.id IS '主键';
COMMENT ON COLUMN wgh_eval_result.dataset_id IS '所属数据集 ID';
COMMENT ON COLUMN wgh_eval_result.knowledge_base_id IS '评测知识库 ID';
COMMENT ON COLUMN wgh_eval_result.dataset_version IS '评测时使用的数据集版本号';
COMMENT ON COLUMN wgh_eval_result.top_k IS '评测使用的 topK';
COMMENT ON COLUMN wgh_eval_result.hybrid IS '是否启用混合检索（0 否 / 1 是）';
COMMENT ON COLUMN wgh_eval_result.query_count IS '实际参与计算的查询数';
COMMENT ON COLUMN wgh_eval_result.recall_at_k IS 'Recall@K（分母为标注相关集合）';
COMMENT ON COLUMN wgh_eval_result.precision_at_k IS 'Precision@K';
COMMENT ON COLUMN wgh_eval_result.mrr IS 'MRR@K';
COMMENT ON COLUMN wgh_eval_result.ndcg IS 'NDCG@K';
COMMENT ON COLUMN wgh_eval_result.query_hit_rate IS '查询命中率（topK 至少 1 条相关）';
COMMENT ON COLUMN wgh_eval_result.coverage_rate IS '召回覆盖率（实际返回条数 / topK）';
COMMENT ON COLUMN wgh_eval_result.details_json IS '逐查询明细 JSON';
COMMENT ON COLUMN wgh_eval_result.create_time IS '创建时间';
COMMENT ON COLUMN wgh_eval_result.update_time IS '更新时间';
COMMENT ON COLUMN wgh_eval_result.create_by IS '创建人';
COMMENT ON COLUMN wgh_eval_result.update_by IS '更新人';
COMMENT ON COLUMN wgh_eval_result.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

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
  visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
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
  workflow_id BIGINT,
  rerank_enabled SMALLINT DEFAULT 0,
  rerank_model_id BIGINT,
  hybrid_enabled SMALLINT DEFAULT 0,
  query_transform_mode VARCHAR(20) DEFAULT 'NONE',
  rewrite_model_id BIGINT,
  semantic_cache_enabled SMALLINT DEFAULT 0,
  model_route_group VARCHAR(50),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_cw_workflow_id ON wgh_chat_window(workflow_id);
CREATE INDEX IF NOT EXISTS idx_cw_share_token ON wgh_chat_window(share_token);
CREATE INDEX IF NOT EXISTS idx_cw_share_enabled ON wgh_chat_window(share_enabled);
CREATE INDEX IF NOT EXISTS idx_cw_status_create ON wgh_chat_window(status, create_time);
CREATE INDEX IF NOT EXISTS idx_cw_owner_visibility ON wgh_chat_window(create_by, visibility);
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
COMMENT ON COLUMN wgh_chat_window.visibility IS '可见性：PRIVATE 仅自己 / TEAM 团队可读 / PUBLIC 公开可读';
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
COMMENT ON COLUMN wgh_chat_window.workflow_id IS '绑定的工作流 ID（非空时每条用户消息触发一次完整工作流运行）';
COMMENT ON COLUMN wgh_chat_window.rerank_enabled IS '是否启用 Rerank 重排序（0 否 / 1 是）';
COMMENT ON COLUMN wgh_chat_window.rerank_model_id IS 'Rerank 模型 ID（指向 wgh_llm_model 中 model_type=RERANK 的记录）';
COMMENT ON COLUMN wgh_chat_window.hybrid_enabled IS '是否启用混合检索（向量 + pg_trgm 关键词，RRF 融合）（0 否 / 1 是）';
COMMENT ON COLUMN wgh_chat_window.query_transform_mode IS '查询变换模式：NONE / REWRITE / MULTI_QUERY / HYDE';
COMMENT ON COLUMN wgh_chat_window.rewrite_model_id IS '查询变换执行模型 ID（CHAT 类型；为空则复用窗口聊天模型）';
COMMENT ON COLUMN wgh_chat_window.semantic_cache_enabled IS '是否启用语义缓存（相似问题直接返回缓存答案，跳过检索+LLM）（0 否 / 1 是）';
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
  status VARCHAR(20),
  token_count INT DEFAULT 0,
  recall_rate DOUBLE PRECISION,
  coverage_rate DOUBLE PRECISION,
  hit_rate DOUBLE PRECISION,
  accuracy_rate DOUBLE PRECISION,
  sources TEXT,
  mrr DOUBLE PRECISION,
  ndcg DOUBLE PRECISION,
  faithfulness DOUBLE PRECISION,
  answer_relevancy DOUBLE PRECISION,
  judge_agreement DOUBLE PRECISION,
  judge_details TEXT,
  attachments TEXT,
  feedback SMALLINT DEFAULT 0,
  feedback_comment VARCHAR(500),
  cost DOUBLE PRECISION,
  model_name VARCHAR(100),
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
COMMENT ON COLUMN wgh_chat_message.status IS '助手消息生成状态：COMPLETED / STOPPED / FAILED';
COMMENT ON COLUMN wgh_chat_message.token_count IS 'token 数';
COMMENT ON COLUMN wgh_chat_message.recall_rate IS '检索召回率 Recall@K（分母为标注的相关 chunk 数，需 ground truth；在线无标注时为 NULL）';
COMMENT ON COLUMN wgh_chat_message.coverage_rate IS '召回覆盖率（实际返回条数/topK，衡量 topK 填充程度，非召回率）';
COMMENT ON COLUMN wgh_chat_message.hit_rate IS '命中率 Precision@K（检索结果中分数达标的 chunk 占比）';
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
  visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_tool_name ON wgh_tool(name);
CREATE INDEX IF NOT EXISTS idx_tool_status ON wgh_tool(status);
CREATE INDEX IF NOT EXISTS idx_tool_type_status ON wgh_tool(tool_type, status);
CREATE INDEX IF NOT EXISTS idx_tool_owner_visibility ON wgh_tool(create_by, visibility);
COMMENT ON TABLE wgh_tool IS '工具配置';
COMMENT ON COLUMN wgh_tool.id IS '主键';
COMMENT ON COLUMN wgh_tool.name IS '工具名称';
COMMENT ON COLUMN wgh_tool.tool_type IS '工具类型：SKILL / MCP / HTTP / FUNCTION_CALLING';
COMMENT ON COLUMN wgh_tool.description IS '工具描述（LLM function calling description）';
COMMENT ON COLUMN wgh_tool.definition IS '工具定义 JSON（function schema / MCP input schema 等）';
COMMENT ON COLUMN wgh_tool.executor_config IS '执行器配置 JSON（endpoint / handler class / timeout 等）';
COMMENT ON COLUMN wgh_tool.status IS '状态（1 启用 / 0 禁用）';
COMMENT ON COLUMN wgh_tool.is_example IS '是否示例数据（0 否 / 1 是）';
COMMENT ON COLUMN wgh_tool.visibility IS '可见性：PRIVATE 仅自己 / TEAM 团队可读 / PUBLIC 公开可读';
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
  visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
  version INT DEFAULT 1,
  graph_json JSONB NOT NULL,
  share_token VARCHAR(64),
  share_enabled SMALLINT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_workflow_share_token ON wgh_workflow(share_token);
CREATE INDEX IF NOT EXISTS idx_workflow_status_update ON wgh_workflow(status, update_time);
CREATE INDEX IF NOT EXISTS idx_workflow_owner_visibility ON wgh_workflow(create_by, visibility);
COMMENT ON TABLE wgh_workflow IS '可视化工作流定义';
COMMENT ON COLUMN wgh_workflow.id IS '主键';
COMMENT ON COLUMN wgh_workflow.name IS '工作流名称';
COMMENT ON COLUMN wgh_workflow.description IS '描述';
COMMENT ON COLUMN wgh_workflow.status IS '状态（1 启用 / 0 停用）';
COMMENT ON COLUMN wgh_workflow.visibility IS '可见性：PRIVATE 仅自己 / TEAM 团队可读 / PUBLIC 公开可读';
COMMENT ON COLUMN wgh_workflow.version IS '版本号，每次保存 +1';
COMMENT ON COLUMN wgh_workflow.graph_json IS '图定义 JSON：{ nodes: [...], edges: [...] }';
COMMENT ON COLUMN wgh_workflow.share_token IS '分享访问令牌（UUID 无横线），为空表示未生成';
COMMENT ON COLUMN wgh_workflow.share_enabled IS '是否开启分享运行页（0 否 / 1 是），分享态运行启用 safeMode 只读工具白名单';
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

-- ============ 工作流触发器 ============

CREATE TABLE IF NOT EXISTS wgh_workflow_trigger (
  id BIGSERIAL PRIMARY KEY,
  workflow_id BIGINT NOT NULL,
  trigger_type VARCHAR(20) NOT NULL,
  cron_expression VARCHAR(50),
  webhook_token VARCHAR(64),
  input_text VARCHAR(1000),
  status SMALLINT DEFAULT 1,
  last_fire_time TIMESTAMP,
  fire_count INT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wf_trigger_token ON wgh_workflow_trigger(webhook_token);
CREATE INDEX IF NOT EXISTS idx_wf_trigger_wf ON wgh_workflow_trigger(workflow_id, status);
COMMENT ON TABLE wgh_workflow_trigger IS '工作流触发器（CRON 定时 / WEBHOOK）';
COMMENT ON COLUMN wgh_workflow_trigger.id IS '主键';
COMMENT ON COLUMN wgh_workflow_trigger.workflow_id IS '所属工作流 ID';
COMMENT ON COLUMN wgh_workflow_trigger.trigger_type IS '触发类型：CRON / WEBHOOK';
COMMENT ON COLUMN wgh_workflow_trigger.cron_expression IS 'CRON 表达式（6 段：秒 分 时 日 月 周）';
COMMENT ON COLUMN wgh_workflow_trigger.webhook_token IS 'Webhook 令牌（URL 路径即密钥，CRON 类型为空）';
COMMENT ON COLUMN wgh_workflow_trigger.input_text IS '触发时的默认输入';
COMMENT ON COLUMN wgh_workflow_trigger.status IS '状态（1 启用 / 0 停用）';
COMMENT ON COLUMN wgh_workflow_trigger.last_fire_time IS '最近触发时间';
COMMENT ON COLUMN wgh_workflow_trigger.fire_count IS '累计触发次数';
COMMENT ON COLUMN wgh_workflow_trigger.create_time IS '创建时间';
COMMENT ON COLUMN wgh_workflow_trigger.update_time IS '更新时间';
COMMENT ON COLUMN wgh_workflow_trigger.create_by IS '创建人';
COMMENT ON COLUMN wgh_workflow_trigger.update_by IS '更新人';
COMMENT ON COLUMN wgh_workflow_trigger.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- ============ 工作流运行实例 ============

CREATE TABLE IF NOT EXISTS wgh_workflow_run (
  id BIGSERIAL PRIMARY KEY,
  workflow_id BIGINT NOT NULL,
  session_id BIGINT,
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
CREATE INDEX IF NOT EXISTS idx_wf_run_session ON wgh_workflow_run(session_id, id);
CREATE INDEX IF NOT EXISTS idx_wf_run_status ON wgh_workflow_run(status);
COMMENT ON TABLE wgh_workflow_run IS '工作流运行实例';
COMMENT ON COLUMN wgh_workflow_run.id IS '主键';
COMMENT ON COLUMN wgh_workflow_run.workflow_id IS '所属工作流 ID';
COMMENT ON COLUMN wgh_workflow_run.session_id IS '关联的聊天会话 ID（聊天窗口触发的工作流运行，用于刷新恢复）';
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

-- ========== 多 Agent 运行实例 ==========
CREATE TABLE IF NOT EXISTS wgh_m_agent_run (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              VARCHAR(64) NOT NULL UNIQUE,
    session_id          BIGINT NOT NULL,
    chat_window_id      BIGINT NOT NULL,
    execution_mode      VARCHAR(30) NOT NULL,
    crew_config_json    JSONB NOT NULL,
    agents_json         JSONB NOT NULL,
    user_goal           TEXT NOT NULL,
    input_text          TEXT,
    output_text         TEXT,
    status              VARCHAR(20) DEFAULT 'PLANNING',
    current_round       INT DEFAULT 0,
    max_rounds          INT DEFAULT 20,
    dynamic_router_model_id BIGINT,
    dynamic_router_prompt  TEXT,
    termination_criteria TEXT,
    summarizer_agent_id VARCHAR(32),
    error_message       TEXT,
    start_time          TIMESTAMP,
    finish_time         TIMESTAMP,
    duration_ms         BIGINT,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by           VARCHAR(50) DEFAULT 'system',
    update_by           VARCHAR(50) DEFAULT 'system',
    deleted             SMALLINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_m_ar_session  ON wgh_m_agent_run(session_id);
CREATE INDEX IF NOT EXISTS idx_m_ar_mode     ON wgh_m_agent_run(execution_mode);
CREATE INDEX IF NOT EXISTS idx_m_ar_status   ON wgh_m_agent_run(status);
CREATE INDEX IF NOT EXISTS idx_m_ar_window   ON wgh_m_agent_run(chat_window_id, start_time);

COMMENT ON TABLE wgh_m_agent_run IS '多 Agent 运行实例';
COMMENT ON COLUMN wgh_m_agent_run.run_id IS '对外 UUID';
COMMENT ON COLUMN wgh_m_agent_run.execution_mode IS 'CREW_SEQUENTIAL / CREW_HIERARCHICAL / CREW_PARALLEL / GRAPH_ROUTED / GROUP_CHAT_ROUND_ROBIN / GROUP_CHAT_DYNAMIC_ROUTER';
COMMENT ON COLUMN wgh_m_agent_run.status IS 'PLANNING / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED';

-- ========== Agent 执行记录 ==========
CREATE TABLE IF NOT EXISTS wgh_m_agent_step (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              VARCHAR(64) NOT NULL,
    agent_id            VARCHAR(32) NOT NULL,
    step_index          INT NOT NULL,
    status              VARCHAR(20) DEFAULT 'PENDING',
    role                VARCHAR(50),
    input_context       JSONB,
    output_text         TEXT,
    tool_calls_json     JSONB,
    rag_sources_json    JSONB,
    reflection_score    DOUBLE PRECISION,
    reflection_feedback TEXT,
    thought             TEXT,
    hitl_expected_input TEXT,
    result_summary      TEXT,
    round               INT DEFAULT 0,
    start_time          TIMESTAMP,
    end_time            TIMESTAMP,
    duration_ms         BIGINT,
    token_count         INT DEFAULT 0,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by           VARCHAR(50) DEFAULT 'system',
    update_by           VARCHAR(50) DEFAULT 'system',
    deleted             SMALLINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_m_as_run_agent ON wgh_m_agent_step(run_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_m_as_run_step  ON wgh_m_agent_step(run_id, step_index);
CREATE INDEX IF NOT EXISTS idx_m_as_status    ON wgh_m_agent_step(status);
CREATE INDEX IF NOT EXISTS idx_m_as_round     ON wgh_m_agent_step(run_id, round);

COMMENT ON TABLE wgh_m_agent_step IS 'AgentInstance 执行记录';
COMMENT ON COLUMN wgh_m_agent_step.status IS 'PENDING / RUNNING / SUCCESS / FAILED / SKIPPED / HITL_PENDING';
COMMENT ON COLUMN wgh_m_agent_step.round IS 'GroupChat 对话轮次';

-- ========== 可复用 Crew 配置 ==========
CREATE TABLE IF NOT EXISTS wgh_m_agent_crew (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500),
    execution_mode      VARCHAR(30) NOT NULL,
    agents_json         JSONB NOT NULL,
    graph_json          JSONB,
    crew_config_json    JSONB,
    group_chat_config_json JSONB,
    status              SMALLINT DEFAULT 1,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by           VARCHAR(50) DEFAULT 'system',
    update_by           VARCHAR(50) DEFAULT 'system',
    deleted             SMALLINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_m_crew_mode   ON wgh_m_agent_crew(execution_mode);
CREATE INDEX IF NOT EXISTS idx_m_crew_status ON wgh_m_agent_crew(status);

COMMENT ON TABLE wgh_m_agent_crew IS '可复用的多 Agent Crew 配置模板';

-- ============ 审计日志 / 配额限流 / 成本统计 ============

CREATE TABLE IF NOT EXISTS wgh_audit_log (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(50),
  action VARCHAR(50) NOT NULL,
  resource_type VARCHAR(50),
  resource_id VARCHAR(64),
  detail TEXT,
  ip VARCHAR(64),
  status VARCHAR(20) DEFAULT 'SUCCESS',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_audit_username_create ON wgh_audit_log(username, create_time);
CREATE INDEX IF NOT EXISTS idx_audit_action_create ON wgh_audit_log(action, create_time);
COMMENT ON TABLE wgh_audit_log IS '审计日志（写操作留痕，异步写入）';
COMMENT ON COLUMN wgh_audit_log.username IS '操作人用户名（未登录为 anonymous）';
COMMENT ON COLUMN wgh_audit_log.action IS '操作标识（如 KB_CREATE / MODEL_DELETE）';
COMMENT ON COLUMN wgh_audit_log.resource_type IS '资源类型（如 KNOWLEDGE_BASE / LLM_MODEL）';
COMMENT ON COLUMN wgh_audit_log.resource_id IS '资源 ID（尽力提取）';
COMMENT ON COLUMN wgh_audit_log.detail IS '操作详情（方法 + 参数摘要，截断 500 字）';
COMMENT ON COLUMN wgh_audit_log.ip IS '客户端 IP';
COMMENT ON COLUMN wgh_audit_log.status IS '结果：SUCCESS / FAIL';

CREATE TABLE IF NOT EXISTS wgh_model_price (
  id BIGSERIAL PRIMARY KEY,
  provider VARCHAR(50) NOT NULL,
  model_name VARCHAR(100) NOT NULL,
  input_price_per_1k DOUBLE PRECISION NOT NULL DEFAULT 0,
  output_price_per_1k DOUBLE PRECISION NOT NULL DEFAULT 0,
  currency VARCHAR(10) DEFAULT 'CNY',
  remark VARCHAR(500),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0,
  CONSTRAINT uk_model_price UNIQUE (provider, model_name)
);
COMMENT ON TABLE wgh_model_price IS '模型价格表（每 1K token 单价，用于成本估算）';
COMMENT ON COLUMN wgh_model_price.provider IS '提供厂商（与 wgh_llm_model.provider 对齐）';
COMMENT ON COLUMN wgh_model_price.model_name IS '厂商侧模型名（与 wgh_llm_model.model_name 对齐）';
COMMENT ON COLUMN wgh_model_price.input_price_per_1k IS '输入单价（元/1K tokens）';
COMMENT ON COLUMN wgh_model_price.output_price_per_1k IS '输出单价（元/1K tokens）';
COMMENT ON COLUMN wgh_model_price.currency IS '币种（默认 CNY）';

CREATE TABLE IF NOT EXISTS wgh_quota_usage (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(50) NOT NULL,
  date_key VARCHAR(10) NOT NULL,
  chat_count INT DEFAULT 0,
  token_used BIGINT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0,
  CONSTRAINT uk_quota_user_date UNIQUE (username, date_key)
);
COMMENT ON TABLE wgh_quota_usage IS '用户周期配额用量（聊天条数 + token 消耗，按 date_key 聚合）';
COMMENT ON COLUMN wgh_quota_usage.username IS '用户名';
COMMENT ON COLUMN wgh_quota_usage.date_key IS '周期键（日=yyyy-MM-dd，月=yyyy-MM，服务器本地时区）';
COMMENT ON COLUMN wgh_quota_usage.chat_count IS '当日已用对话条数';
COMMENT ON COLUMN wgh_quota_usage.token_used IS '当日已用 token 数（实际或估算）';

-- 按用户配额配置（rag-quota 模块；无记录或禁用时回退全局默认 wgh.rag.quota.*）
CREATE TABLE IF NOT EXISTS wgh_user_quota_config (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(50) NOT NULL,
  period VARCHAR(10) NOT NULL DEFAULT 'DAY',
  chat_limit INT DEFAULT 0,
  token_limit BIGINT DEFAULT 0,
  enabled SMALLINT DEFAULT 1,
  remark VARCHAR(500),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0,
  CONSTRAINT uk_user_quota_username UNIQUE (username)
);
COMMENT ON TABLE wgh_user_quota_config IS '按用户配额配置（优先于全局默认）';
COMMENT ON COLUMN wgh_user_quota_config.username IS '用户名（唯一）';
COMMENT ON COLUMN wgh_user_quota_config.period IS '统计周期：DAY=自然日，MONTH=自然月';
COMMENT ON COLUMN wgh_user_quota_config.chat_limit IS '请求数上限（<=0 不限制）';
COMMENT ON COLUMN wgh_user_quota_config.token_limit IS 'token 上限（<=0 不限制）';
COMMENT ON COLUMN wgh_user_quota_config.enabled IS '是否启用（0/1；禁用时回退全局默认）';
COMMENT ON COLUMN wgh_user_quota_config.remark IS '备注';
-- date_key 为月周期键（yyyy-MM，7 字符）

-- 配额扣费流水（退款幂等与补偿的唯一事实源；一次对话扣费一行）
CREATE TABLE IF NOT EXISTS wgh_quota_charge (
  id BIGSERIAL PRIMARY KEY,
  charge_id VARCHAR(64) NOT NULL,
  username VARCHAR(50) NOT NULL,
  period_key VARCHAR(10) NOT NULL,
  amount INT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'CHARGED',
  window_id BIGINT,
  session_id BIGINT,
  task_id VARCHAR(64),
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP,
  last_error VARCHAR(500),
  finish_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
COMMENT ON TABLE wgh_quota_charge IS '配额扣费流水（退款幂等与补偿的唯一事实源）';
COMMENT ON COLUMN wgh_quota_charge.charge_id IS '扣费标识（UUID）；退款幂等键，全局唯一';
COMMENT ON COLUMN wgh_quota_charge.username IS '用户名';
COMMENT ON COLUMN wgh_quota_charge.period_key IS '扣费时的周期键（日=yyyy-MM-dd，月=yyyy-MM）；退款按此键回退';
COMMENT ON COLUMN wgh_quota_charge.amount IS '本次扣减的对话条数（当前固定为 1）';
COMMENT ON COLUMN wgh_quota_charge.status IS '状态：CHARGED/SETTLED/REFUNDED/REFUND_FAILED/DEAD';
COMMENT ON COLUMN wgh_quota_charge.window_id IS '关联聊天窗口';
COMMENT ON COLUMN wgh_quota_charge.session_id IS '关联会话（会话创建后回填）';
COMMENT ON COLUMN wgh_quota_charge.task_id IS '关联任务标识（预留：后台作业/异步任务接入时使用）';
COMMENT ON COLUMN wgh_quota_charge.retry_count IS '退款补偿已重试次数';
COMMENT ON COLUMN wgh_quota_charge.next_retry_at IS '下次可补偿时间（退避）';
COMMENT ON COLUMN wgh_quota_charge.last_error IS '最近一次退款失败原因（截断）';
COMMENT ON COLUMN wgh_quota_charge.finish_time IS '终态时间（REFUNDED / DEAD）';
COMMENT ON COLUMN wgh_quota_charge.deleted IS '逻辑删除（0 未删除 / 1 已删除）';
CREATE UNIQUE INDEX IF NOT EXISTS uk_quota_charge_id
  ON wgh_quota_charge(charge_id);
CREATE INDEX IF NOT EXISTS idx_quota_charge_scan
  ON wgh_quota_charge(status, create_time)
  WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_quota_charge_retry
  ON wgh_quota_charge(status, next_retry_at)
  WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_quota_charge_user
  ON wgh_quota_charge(username, period_key)
  WHERE deleted = 0;

-- 聊天消息成本列（回复完成后按模型价格表回填，无价格配置时为 NULL）
COMMENT ON COLUMN wgh_chat_message.cost IS '本条消息估算成本（元，按模型价格表回填；无价格配置为 NULL）';
COMMENT ON COLUMN wgh_chat_message.model_name IS '生成本条消息的模型名（厂商侧 modelName，用于成本按模型聚合）';

-- 聊天消息反馈列（仅 ASSISTANT 消息可反馈）
COMMENT ON COLUMN wgh_chat_message.feedback IS '用户反馈（0 无 / 1 赞 / -1 踩）';
COMMENT ON COLUMN wgh_chat_message.feedback_comment IS '差评补充说明（截断 500 字）';
CREATE INDEX IF NOT EXISTS idx_msg_feedback ON wgh_chat_message(feedback) WHERE feedback <> 0;

-- ============ 语义缓存 ============

-- 向量存 pgvector 独立表 wgh_semantic_cache_vec（由 PgVectorEmbeddingStore 自动建表，
-- 维度取窗口知识库的 embedding 模型维度；metadata.windowId 做窗口隔离）
CREATE TABLE IF NOT EXISTS wgh_semantic_cache (
  id BIGSERIAL PRIMARY KEY,
  window_id BIGINT NOT NULL,
  query_text VARCHAR(2000) NOT NULL,
  answer_text TEXT NOT NULL,
  embedding_id VARCHAR(64),
  hit_count INT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_semantic_cache_window ON wgh_semantic_cache(window_id, deleted);
COMMENT ON TABLE wgh_semantic_cache IS '语义缓存（相似问题直接返回缓存答案，跳过检索+LLM；向量存 wgh_semantic_cache_vec）';
COMMENT ON COLUMN wgh_semantic_cache.window_id IS '聊天窗口 ID（缓存按窗口隔离）';
COMMENT ON COLUMN wgh_semantic_cache.query_text IS '用户问题（截断 2000 字）';
COMMENT ON COLUMN wgh_semantic_cache.answer_text IS '缓存的答案全文';
COMMENT ON COLUMN wgh_semantic_cache.embedding_id IS 'wgh_semantic_cache_vec 中的向量 ID（uuid）';
COMMENT ON COLUMN wgh_semantic_cache.hit_count IS '命中次数';

-- ============ 多模态（图片问答） ============

COMMENT ON COLUMN wgh_llm_model.supports_vision IS '是否支持图片输入（视觉模型，0 否 / 1 是）';
COMMENT ON COLUMN wgh_llm_model.capability_tier IS '能力档位（1~5，越大越强；故障转移只允许降级不升级）';
COMMENT ON COLUMN wgh_llm_model.supports_tools IS '是否支持 function calling（0 否 / 1 是）';
CREATE INDEX IF NOT EXISTS idx_llm_model_route_capability
  ON wgh_llm_model (route_group, route_priority, capability_tier)
  WHERE deleted = 0 AND status = 1;
COMMENT ON COLUMN wgh_chat_message.attachments IS '消息附件 JSON 数组（图片 data URL 或公网 URL）';

-- ============ Prompt 模板中心 ============

CREATE TABLE IF NOT EXISTS wgh_prompt_template (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL UNIQUE,
  category VARCHAR(50),
  content TEXT NOT NULL,
  variables VARCHAR(500),
  version INT DEFAULT 1,
  builtin SMALLINT DEFAULT 0,
  remark VARCHAR(500),
  visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_prompt_template_owner_visibility ON wgh_prompt_template(create_by, visibility);
COMMENT ON TABLE wgh_prompt_template IS 'Prompt 模板中心（{{var}} 占位符；聊天窗口 system_prompt 支持 template:模板名 引用）';
COMMENT ON COLUMN wgh_prompt_template.name IS '模板名（唯一，template: 引用键）';
COMMENT ON COLUMN wgh_prompt_template.category IS '分类（RAG / 客服 / 总结 等）';
COMMENT ON COLUMN wgh_prompt_template.content IS '模板内容（{{var}} 占位符）';
COMMENT ON COLUMN wgh_prompt_template.variables IS '变量名 JSON 数组（保存时从 content 自动提取）';
COMMENT ON COLUMN wgh_prompt_template.version IS '版本号（内容变更自动 +1）';
COMMENT ON COLUMN wgh_prompt_template.builtin IS '是否内置模板（1 内置不可删除/改名，0 自定义）';
COMMENT ON COLUMN wgh_prompt_template.remark IS '备注';
COMMENT ON COLUMN wgh_prompt_template.visibility IS '可见性：PRIVATE 仅自己 / TEAM 团队可读 / PUBLIC 公开可读；内置模板固定 PUBLIC';

-- 内置种子模板（幂等）
INSERT INTO wgh_prompt_template (name, category, content, variables, builtin, remark, visibility)
SELECT 'RAG问答助手', 'RAG', '你是知识库「{{kbName}}」的智能问答助手。
要求：
1. 仅依据检索到的知识库内容回答，不编造信息；
2. 回答标注来源（知识库名 + 文件名）；
3. 知识库内容不足时明确告知用户；
4. 回答结构化、条理清晰，使用中文。', '["kbName"]', 1, '内置模板', 'PUBLIC'
WHERE NOT EXISTS (SELECT 1 FROM wgh_prompt_template WHERE name = 'RAG问答助手');

INSERT INTO wgh_prompt_template (name, category, content, variables, builtin, remark, visibility)
SELECT '客服专员', '客服', '你是「{{kbName}}」的客服专员{{userName}}。
要求：
1. 语气友好专业，先共情再解决问题；
2. 严格依据知识库内容作答，不确定的问题引导用户联系人工客服；
3. 不承诺知识库之外的补偿、退款等事项；
4. 每次回答控制在 300 字以内。', '["kbName","userName"]', 1, '内置模板', 'PUBLIC'
WHERE NOT EXISTS (SELECT 1 FROM wgh_prompt_template WHERE name = '客服专员');

INSERT INTO wgh_prompt_template (name, category, content, variables, builtin, remark, visibility)
SELECT '内容总结专家', '总结', '你是内容总结专家。
要求：
1. 将检索到的内容浓缩为要点列表（3-7 条）；
2. 保留关键数据、结论与来源；
3. 去除重复与口水内容；
4. 输出格式：先一句话总述，再分条列出要点。', '[]', 1, '内置模板', 'PUBLIC'
WHERE NOT EXISTS (SELECT 1 FROM wgh_prompt_template WHERE name = '内容总结专家');

-- ============ 知识库标签 / 文档版本 ============

CREATE TABLE IF NOT EXISTS wgh_tag (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(50) NOT NULL UNIQUE,
  color VARCHAR(20),
  remark VARCHAR(200),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
COMMENT ON TABLE wgh_tag IS '知识库 / 文档共用标签';
COMMENT ON COLUMN wgh_tag.name IS '标签名（唯一）';
COMMENT ON COLUMN wgh_tag.color IS '颜色（Hex，如 #409EFF）';

CREATE TABLE IF NOT EXISTS wgh_kb_tag_rel (
  id BIGSERIAL PRIMARY KEY,
  kb_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(kb_id, tag_id)
);
CREATE INDEX IF NOT EXISTS idx_kb_tag_rel_tag ON wgh_kb_tag_rel(tag_id);
COMMENT ON TABLE wgh_kb_tag_rel IS '知识库-标签关系';

CREATE TABLE IF NOT EXISTS wgh_file_tag_rel (
  id BIGSERIAL PRIMARY KEY,
  file_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(file_id, tag_id)
);
CREATE INDEX IF NOT EXISTS idx_file_tag_rel_tag ON wgh_file_tag_rel(tag_id);
COMMENT ON TABLE wgh_file_tag_rel IS '知识库文件-标签关系';

COMMENT ON COLUMN wgh_knowledge_file.version IS '当前版本号（重切/重传时 +1）';

-- content_hash 去重：同一知识库内相同文件内容不重复入库
COMMENT ON COLUMN wgh_knowledge_file.content_hash IS '文件内容 SHA-256（同知识库内重复内容去重）';
CREATE INDEX IF NOT EXISTS idx_kfile_kb_content_hash ON wgh_knowledge_file(knowledge_base_id, content_hash);

-- 重建状态机：换 embedding 模型只置 PENDING，实际重建由显式接口触发
COMMENT ON COLUMN wgh_knowledge_base.rebuild_status IS '重建状态：空/PENDING/RUNNING/SUCCEEDED/FAILED';
COMMENT ON COLUMN wgh_knowledge_base.rebuild_total IS '重建文件总数（进度分母）';
COMMENT ON COLUMN wgh_knowledge_base.rebuild_processed IS '已重建文件数（进度分子）';
COMMENT ON COLUMN wgh_knowledge_base.pending_collection IS '重建中的目标向量表名（成功后切换为 collection_name，失败时清理）';
COMMENT ON COLUMN wgh_knowledge_base.pending_embedding_model_id IS '重建中待生效的嵌入模型 ID（成功后替换 embedding_model_id）';

CREATE TABLE IF NOT EXISTS wgh_knowledge_file_version (
  id BIGSERIAL PRIMARY KEY,
  file_id BIGINT NOT NULL,
  version INT NOT NULL,
  chunk_count INT,
  split_strategy VARCHAR(20),
  split_config TEXT,
  status VARCHAR(20),
  remark VARCHAR(500),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_kfv_file ON wgh_knowledge_file_version(file_id);
COMMENT ON TABLE wgh_knowledge_file_version IS '知识库文件版本快照（记录历史切分元数据，支持追溯；不回滚向量）';
COMMENT ON COLUMN wgh_knowledge_file_version.remark IS '变更说明（如 手动重切、修复OCR）';

-- 知识库列表支持按标签过滤：新增 wgh_knowledge_file 与标签联合查询走 idx_file_tag_rel_tag

-- ============ 模型路由与故障转移 ============

COMMENT ON COLUMN wgh_llm_model.route_group IS '路由组名（窗口 model_route_group 匹配；非空时可参与故障转移链路）';
COMMENT ON COLUMN wgh_llm_model.route_priority IS '同组内优先级（升序：数字越小越优先）';
COMMENT ON COLUMN wgh_chat_window.model_route_group IS '指定模型路由组（优先于 llm_model_id：非空则走 ModelRouter 选链路）';


-- ============ 多用户与权限体系（RBAC） ============

CREATE TABLE IF NOT EXISTS wgh_user (
    id                  BIGSERIAL PRIMARY KEY,
    username            VARCHAR(64) NOT NULL,
    password_hash       VARCHAR(256) NOT NULL,
    name                VARCHAR(64),
    email               VARCHAR(128),
    tenant_id           VARCHAR(64),
    status              SMALLINT NOT NULL DEFAULT 1,
    oidc_subject        VARCHAR(256),
    oidc_issuer         VARCHAR(256),
    source              VARCHAR(16) NOT NULL DEFAULT 'local',
    trial               SMALLINT NOT NULL DEFAULT 0,
    expire_at           TIMESTAMP,
    remark              VARCHAR(500),
    password_must_change SMALLINT NOT NULL DEFAULT 0,
    password_version    INT NOT NULL DEFAULT 1,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by           VARCHAR(64),
    update_by           VARCHAR(64),
    deleted             SMALLINT NOT NULL DEFAULT 0,
    UNIQUE (username)
);
COMMENT ON COLUMN wgh_user.source IS '账号来源：local=本地账号（本地管理员/体验账号，可密码登录），oidc=外部 OIDC（仅 token 进入）';
COMMENT ON COLUMN wgh_user.trial IS '是否体验账号：1=是，0=否';
COMMENT ON COLUMN wgh_user.expire_at IS '账号有效期，NULL 表示长期有效；到期后不可登录';
COMMENT ON COLUMN wgh_user.remark IS '备注';
COMMENT ON COLUMN wgh_user.password_must_change IS '是否必须先改密：1=是（初始化创建/管理员重置口令），0=否';
COMMENT ON COLUMN wgh_user.password_version IS '口令版本号：改密/重置/禁用时自增，用于失效已签发的本地会话凭证';

CREATE TABLE IF NOT EXISTS wgh_role (
    id                  BIGSERIAL PRIMARY KEY,
    role_code           VARCHAR(64) NOT NULL,
    role_name           VARCHAR(128),
    description         TEXT,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT NOT NULL DEFAULT 0,
    UNIQUE (role_code)
);

CREATE TABLE IF NOT EXISTS wgh_permission (
    id                  BIGSERIAL PRIMARY KEY,
    perm_code           VARCHAR(128) NOT NULL,
    perm_name           VARCHAR(128),
    resource_type       VARCHAR(64),
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (perm_code)
);

CREATE TABLE IF NOT EXISTS wgh_user_role (
    user_id             BIGINT NOT NULL REFERENCES wgh_user(id),
    role_id             BIGINT NOT NULL REFERENCES wgh_role(id),
    PRIMARY KEY (user_id, role_id),
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS wgh_role_permission (
    role_id             BIGINT NOT NULL REFERENCES wgh_role(id),
    permission_id       BIGINT NOT NULL REFERENCES wgh_permission(id),
    PRIMARY KEY (role_id, permission_id),
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wgh_user_role_user ON wgh_user_role(user_id);
CREATE INDEX IF NOT EXISTS idx_wgh_role_perm_role   ON wgh_role_permission(role_id);

-- ============ 免登录分享 / Widget / 开放 API ============

CREATE TABLE IF NOT EXISTS wgh_share_link (
  id            BIGSERIAL PRIMARY KEY,
  token         VARCHAR(64) NOT NULL UNIQUE,
  name          VARCHAR(100),
  scope         VARCHAR(20) NOT NULL,
  target_id     BIGINT NOT NULL,
  expires_at    TIMESTAMP,
  password_hash VARCHAR(128),
  max_uses      INT DEFAULT 0,
  use_count     INT DEFAULT 0,
  revoked       SMALLINT NOT NULL DEFAULT 0,
  embed_enabled SMALLINT NOT NULL DEFAULT 0,
  embed_origin_whitelist TEXT,
  embed_ip_whitelist TEXT,
  created_by    VARCHAR(64),
  create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by     VARCHAR(64),
  update_by     VARCHAR(64),
  deleted       SMALLINT NOT NULL DEFAULT 0
);
COMMENT ON TABLE wgh_share_link IS '免登录分享链接（有效期/密码/范围/次数/吊销）';
COMMENT ON COLUMN wgh_share_link.scope IS 'WINDOW=匿名问答 / SESSION=只读会话记录 / KB=只读知识库检索';
COMMENT ON COLUMN wgh_share_link.target_id IS 'WINDOW: chat_window_id；SESSION: chat_session_id；KB: knowledge_base_id';
COMMENT ON COLUMN wgh_share_link.max_uses IS '最大使用次数，0 表示不限';
COMMENT ON COLUMN wgh_share_link.embed_enabled IS '是否允许 Widget 嵌入，0=禁止，1=允许';
COMMENT ON COLUMN wgh_share_link.embed_origin_whitelist IS '嵌入域名白名单，逗号分隔，支持 *.example.com';
COMMENT ON COLUMN wgh_share_link.embed_ip_whitelist IS '嵌入 IP 白名单，逗号分隔 CIDR';
CREATE INDEX IF NOT EXISTS idx_share_link_token ON wgh_share_link(token);
CREATE INDEX IF NOT EXISTS idx_share_link_target ON wgh_share_link(scope, target_id);

CREATE TABLE IF NOT EXISTS wgh_api_key (
  id             BIGSERIAL PRIMARY KEY,
  key_prefix     VARCHAR(16),
  key_hash       VARCHAR(128) NOT NULL UNIQUE,
  name           VARCHAR(100),
  scopes         VARCHAR(200),
  expires_at     TIMESTAMP,
  revoked        SMALLINT NOT NULL DEFAULT 0,
  created_by     VARCHAR(64),
  last_used_time TIMESTAMP,
  ip_whitelist   TEXT,
  create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by      VARCHAR(64),
  update_by      VARCHAR(64),
  deleted        SMALLINT NOT NULL DEFAULT 0
);
COMMENT ON TABLE wgh_api_key IS '开放 API Key（仅存 SHA-256 哈希，明文创建时返回一次）';
COMMENT ON COLUMN wgh_api_key.scopes IS '权限范围，逗号分隔：chat:write,kb:read,usage:read；* 表示全部';
COMMENT ON COLUMN wgh_api_key.ip_whitelist IS '调用来源 IP 白名单，逗号分隔 CIDR；为空表示不限制';
CREATE INDEX IF NOT EXISTS idx_api_key_hash ON wgh_api_key(key_hash);

CREATE TABLE IF NOT EXISTS wgh_api_call_log (
  id            BIGSERIAL PRIMARY KEY,
  api_key_id    BIGINT,
  api_key_name  VARCHAR(100),
  username      VARCHAR(64),
  method        VARCHAR(10),
  path          VARCHAR(300),
  status_code   INT,
  duration_ms   BIGINT,
  ip            VARCHAR(64),
  create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by     VARCHAR(64),
  update_by     VARCHAR(64),
  deleted       SMALLINT NOT NULL DEFAULT 0
);
COMMENT ON TABLE wgh_api_call_log IS '开放 API 调用日志（谁、接口、状态码、耗时）';
CREATE INDEX IF NOT EXISTS idx_api_call_log_key ON wgh_api_call_log(api_key_id, create_time);

-- ============ URL / HTTP 数据源连接器（T08） ============

-- 连接器配置：一个连接器对应知识库内的一个单页 URL，按间隔重抓，内容未变不重复入库
CREATE TABLE IF NOT EXISTS wgh_kb_connector (
  id                    BIGSERIAL PRIMARY KEY,
  knowledge_base_id     BIGINT NOT NULL,
  name                  VARCHAR(200) NOT NULL,
  url                   VARCHAR(1000) NOT NULL,
  split_strategy        VARCHAR(20) DEFAULT 'PARAGRAPH',
  split_config          TEXT,
  sync_interval_minutes INT DEFAULT 60,
  enabled               SMALLINT DEFAULT 0,
  last_file_id          BIGINT,
  last_content_hash     VARCHAR(64),
  last_sync_time        TIMESTAMP,
  last_status           VARCHAR(20),
  last_error            TEXT,
  create_time           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by             VARCHAR(50) DEFAULT 'system',
  update_by             VARCHAR(50) DEFAULT 'system',
  deleted               SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_kb_connector_kb ON wgh_kb_connector(knowledge_base_id, deleted);
CREATE INDEX IF NOT EXISTS idx_kb_connector_due ON wgh_kb_connector(enabled, last_sync_time);
COMMENT ON TABLE wgh_kb_connector IS 'URL/HTTP 数据源连接器配置（单页抓取 + 增量同步）';
COMMENT ON COLUMN wgh_kb_connector.id IS '主键';
COMMENT ON COLUMN wgh_kb_connector.knowledge_base_id IS '所属知识库 ID';
COMMENT ON COLUMN wgh_kb_connector.name IS '连接器名称';
COMMENT ON COLUMN wgh_kb_connector.url IS '抓取地址（仅 http/https，经 SSRF 防护校验）';
COMMENT ON COLUMN wgh_kb_connector.split_strategy IS '入库切分策略';
COMMENT ON COLUMN wgh_kb_connector.split_config IS '切分策略配置 JSON';
COMMENT ON COLUMN wgh_kb_connector.sync_interval_minutes IS '自动同步间隔（分钟），0 表示仅手动同步';
COMMENT ON COLUMN wgh_kb_connector.enabled IS '是否启用自动同步（1 启用 / 0 停用）';
COMMENT ON COLUMN wgh_kb_connector.last_file_id IS '最近一次同步生成/命中的知识库文件 ID';
COMMENT ON COLUMN wgh_kb_connector.last_content_hash IS '最近一次抓取正文 SHA-256';
COMMENT ON COLUMN wgh_kb_connector.last_sync_time IS '最近一次同步时间';
COMMENT ON COLUMN wgh_kb_connector.last_status IS '最近一次同步状态：SUCCESS / UNCHANGED / REMOVED / FAILED';
COMMENT ON COLUMN wgh_kb_connector.last_error IS '最近一次失败原因';
COMMENT ON COLUMN wgh_kb_connector.create_time IS '创建时间';
COMMENT ON COLUMN wgh_kb_connector.update_time IS '更新时间';
COMMENT ON COLUMN wgh_kb_connector.create_by IS '创建人';
COMMENT ON COLUMN wgh_kb_connector.update_by IS '更新人';
COMMENT ON COLUMN wgh_kb_connector.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- 运行记录：每次同步一行，包含 HTTP 状态、内容哈希、命中的文件与耗时
CREATE TABLE IF NOT EXISTS wgh_kb_connector_run (
  id            BIGSERIAL PRIMARY KEY,
  connector_id  BIGINT NOT NULL,
  status        VARCHAR(20) NOT NULL,
  http_status   INT,
  file_id       BIGINT,
  content_hash  VARCHAR(64),
  chunk_count   INT DEFAULT 0,
  message       TEXT,
  duration_ms   BIGINT,
  started_at    TIMESTAMP,
  finished_at   TIMESTAMP,
  create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by     VARCHAR(50) DEFAULT 'system',
  update_by     VARCHAR(50) DEFAULT 'system',
  deleted       SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_kb_connector_run ON wgh_kb_connector_run(connector_id, id);
COMMENT ON TABLE wgh_kb_connector_run IS 'URL 连接器单次同步运行记录';
COMMENT ON COLUMN wgh_kb_connector_run.connector_id IS '所属连接器 ID';
COMMENT ON COLUMN wgh_kb_connector_run.status IS '运行状态：SUCCESS / UNCHANGED / REMOVED / FAILED';
COMMENT ON COLUMN wgh_kb_connector_run.http_status IS '抓取返回的 HTTP 状态码';
COMMENT ON COLUMN wgh_kb_connector_run.file_id IS '本次同步产生/命中的知识库文件 ID';
COMMENT ON COLUMN wgh_kb_connector_run.content_hash IS '本次正文 SHA-256';
COMMENT ON COLUMN wgh_kb_connector_run.chunk_count IS '文件 chunk 数（异步处理完成前可能为 0）';
COMMENT ON COLUMN wgh_kb_connector_run.message IS '结果说明 / 失败原因';
COMMENT ON COLUMN wgh_kb_connector_run.duration_ms IS '耗时（毫秒）';
COMMENT ON COLUMN wgh_kb_connector_run.started_at IS '开始时间';
COMMENT ON COLUMN wgh_kb_connector_run.finished_at IS '结束时间';
COMMENT ON COLUMN wgh_kb_connector_run.create_time IS '创建时间';
COMMENT ON COLUMN wgh_kb_connector_run.update_time IS '更新时间';
COMMENT ON COLUMN wgh_kb_connector_run.create_by IS '创建人';
COMMENT ON COLUMN wgh_kb_connector_run.update_by IS '更新人';
COMMENT ON COLUMN wgh_kb_connector_run.deleted IS '逻辑删除（0 未删除 / 1 已删除）';


-- 分片上传会话（大文件断点续传）：分片先按对象存储，complete 时合并为正式文件并复用入库链路
CREATE TABLE IF NOT EXISTS wgh_upload_session (
  id BIGSERIAL PRIMARY KEY,
  knowledge_base_id BIGINT NOT NULL,
  upload_uuid VARCHAR(64) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_type VARCHAR(20) NOT NULL,
  file_size BIGINT NOT NULL,
  chunk_size INT NOT NULL,
  total_chunks INT NOT NULL,
  uploaded_chunks TEXT,
  status VARCHAR(20) DEFAULT 'UPLOADING',
  file_id BIGINT,
  split_strategy VARCHAR(20) DEFAULT 'PARAGRAPH',
  split_config TEXT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_upload_session_uuid ON wgh_upload_session(upload_uuid);
CREATE INDEX IF NOT EXISTS idx_upload_session_status ON wgh_upload_session(status, update_time);
COMMENT ON TABLE wgh_upload_session IS '大文件分片上传会话（断点续传）';
COMMENT ON COLUMN wgh_upload_session.id IS '主键';
COMMENT ON COLUMN wgh_upload_session.knowledge_base_id IS '所属知识库 ID';
COMMENT ON COLUMN wgh_upload_session.upload_uuid IS '上传会话 UUID（同时作为分片存储目录名）';
COMMENT ON COLUMN wgh_upload_session.file_name IS '原始文件名';
COMMENT ON COLUMN wgh_upload_session.file_type IS '文件类型（扩展名大写）';
COMMENT ON COLUMN wgh_upload_session.file_size IS '文件总大小（字节）';
COMMENT ON COLUMN wgh_upload_session.chunk_size IS '分片大小（字节）';
COMMENT ON COLUMN wgh_upload_session.total_chunks IS '分片总数';
COMMENT ON COLUMN wgh_upload_session.uploaded_chunks IS '已完成分片序号 JSON 数组（断点续传依据）';
COMMENT ON COLUMN wgh_upload_session.status IS '会话状态：UPLOADING / COMPLETED / ABORTED';
COMMENT ON COLUMN wgh_upload_session.file_id IS '合并入库后生成的 wgh_knowledge_file.id';
COMMENT ON COLUMN wgh_upload_session.split_strategy IS '入库切分策略';
COMMENT ON COLUMN wgh_upload_session.split_config IS '切分策略配置 JSON';
COMMENT ON COLUMN wgh_upload_session.create_time IS '创建时间';
COMMENT ON COLUMN wgh_upload_session.update_time IS '更新时间';
COMMENT ON COLUMN wgh_upload_session.create_by IS '创建人';
COMMENT ON COLUMN wgh_upload_session.update_by IS '更新人';
COMMENT ON COLUMN wgh_upload_session.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- ==================== 检索式记忆 ====================
CREATE TABLE IF NOT EXISTS wgh_agent_memory (
  id BIGSERIAL PRIMARY KEY,
  scope_type VARCHAR(16) NOT NULL,
  scope_id VARCHAR(64) NOT NULL,
  agent_id VARCHAR(64),
  memory_type VARCHAR(20) DEFAULT 'SUMMARY',
  content TEXT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  embedding_id VARCHAR(64),
  source_message_id BIGINT,
  token_count INT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
COMMENT ON TABLE wgh_agent_memory IS '检索式记忆（会话/任务级；向量存 wgh_agent_memory_vec，按 scopeKey 隔离）';
COMMENT ON COLUMN wgh_agent_memory.scope_type IS '作用域类型：SESSION 会话级 / TASK 任务级';
COMMENT ON COLUMN wgh_agent_memory.scope_id IS '作用域 ID（sessionId 或 taskId）';
COMMENT ON COLUMN wgh_agent_memory.agent_id IS '产生该记忆的 Agent（单 Agent 链路为空）';
COMMENT ON COLUMN wgh_agent_memory.memory_type IS '记忆类型：SUMMARY 摘要 / FACT 事实';
COMMENT ON COLUMN wgh_agent_memory.content IS '记忆正文';
COMMENT ON COLUMN wgh_agent_memory.content_hash IS '正文 SHA-256，用于同作用域去重';
COMMENT ON COLUMN wgh_agent_memory.embedding_id IS 'wgh_agent_memory_vec 中的向量 ID（uuid）';
COMMENT ON COLUMN wgh_agent_memory.source_message_id IS '来源消息 ID（可选）';
COMMENT ON COLUMN wgh_agent_memory.token_count IS '正文估算 Token 数';
COMMENT ON COLUMN wgh_agent_memory.deleted IS '逻辑删除（0 未删除 / 1 已删除）';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_memory_scope_hash
  ON wgh_agent_memory (scope_type, scope_id, content_hash)
  WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_memory_scope_time
  ON wgh_agent_memory (scope_type, scope_id, create_time DESC)
  WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_memory_agent
  ON wgh_agent_memory (agent_id, create_time DESC)
  WHERE deleted = 0;

-- ==================== 调度：后台作业 ====================
CREATE TABLE IF NOT EXISTS wgh_execution_job (
  id BIGSERIAL PRIMARY KEY,
  execution_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64),
  job_type VARCHAR(64) NOT NULL,
  lane VARCHAR(16) NOT NULL DEFAULT 'BACKGROUND',
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  owner_user_id VARCHAR(64),
  biz_key VARCHAR(200),
  payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  payload_schema_version INT NOT NULL DEFAULT 1,
  priority INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  scheduled_at TIMESTAMP NOT NULL DEFAULT now(),
  not_before TIMESTAMP,
  lease_owner VARCHAR(64),
  start_time TIMESTAMP,
  lease_expire_time TIMESTAMP,
  heartbeat_time TIMESTAMP,
  attempt INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  timeout_seconds INT,
  cancel_requested SMALLINT NOT NULL DEFAULT 0,
  cancel_reason VARCHAR(200),
  error_code VARCHAR(64),
  error_message TEXT,
  result_json JSONB,
  finish_time TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
COMMENT ON TABLE wgh_execution_job IS '后台作业（持久化 + 租约；核对与重试的唯一事实源）';
COMMENT ON COLUMN wgh_execution_job.id IS '主键';
COMMENT ON COLUMN wgh_execution_job.execution_id IS '对外执行标识（UUID），门面查询/取消/订阅使用';
COMMENT ON COLUMN wgh_execution_job.trace_id IS '提交时链路 traceId，用于跨实例按 traceId 关联提交与执行日志';
COMMENT ON COLUMN wgh_execution_job.job_type IS '作业类型，对应 JobHandler#jobType()';
COMMENT ON COLUMN wgh_execution_job.lane IS '执行车道：BACKGROUND 后台 / MAINTENANCE 维护';
COMMENT ON COLUMN wgh_execution_job.tenant_id IS '租户标识（单租户为 default）';
COMMENT ON COLUMN wgh_execution_job.owner_user_id IS '发起人用户标识（系统任务为空）';
COMMENT ON COLUMN wgh_execution_job.biz_key IS '业务幂等键；同 job_type + biz_key 只保留一条';
COMMENT ON COLUMN wgh_execution_job.payload_json IS '作业负载 JSON';
COMMENT ON COLUMN wgh_execution_job.payload_schema_version IS '负载结构版本；不兼容置 DEAD(INCOMPATIBLE_PAYLOAD)';
COMMENT ON COLUMN wgh_execution_job.priority IS '优先级，数值越大越优先';
COMMENT ON COLUMN wgh_execution_job.status IS '状态：PENDING/LEASED/RUNNING/SUCCEEDED/FAILED/DEAD/CANCELLED';
COMMENT ON COLUMN wgh_execution_job.scheduled_at IS '期望开始时间';
COMMENT ON COLUMN wgh_execution_job.not_before IS '最早可领取时间（重试退避 / 单飞锁等待）';
COMMENT ON COLUMN wgh_execution_job.lease_owner IS '租约持有者实例标识';
COMMENT ON COLUMN wgh_execution_job.start_time IS '首次进入 RUNNING 的时间';
COMMENT ON COLUMN wgh_execution_job.lease_expire_time IS '租约过期时间；过期后可被其它实例回收';
COMMENT ON COLUMN wgh_execution_job.heartbeat_time IS '最近一次续租时间';
COMMENT ON COLUMN wgh_execution_job.attempt IS '已尝试次数（领取时 +1）';
COMMENT ON COLUMN wgh_execution_job.max_attempts IS '最大尝试次数（含首次）';
COMMENT ON COLUMN wgh_execution_job.timeout_seconds IS '单次执行超时秒数（空=车道默认值）';
COMMENT ON COLUMN wgh_execution_job.cancel_requested IS '是否已请求取消（0 否 / 1 是）';
COMMENT ON COLUMN wgh_execution_job.cancel_reason IS '取消原因';
COMMENT ON COLUMN wgh_execution_job.error_code IS '失败错误码（ExecutionErrorCodes）';
COMMENT ON COLUMN wgh_execution_job.error_message IS '失败原因';
COMMENT ON COLUMN wgh_execution_job.result_json IS '执行结果 JSON（如工作流 runId）';
COMMENT ON COLUMN wgh_execution_job.finish_time IS '终态时间';
COMMENT ON COLUMN wgh_execution_job.version IS '状态迁移版本号，每次迁移 +1';
COMMENT ON COLUMN wgh_execution_job.create_time IS '创建时间';
COMMENT ON COLUMN wgh_execution_job.update_time IS '更新时间';
COMMENT ON COLUMN wgh_execution_job.create_by IS '创建人';
COMMENT ON COLUMN wgh_execution_job.update_by IS '更新人';
COMMENT ON COLUMN wgh_execution_job.deleted IS '逻辑删除（0 未删除 / 1 已删除）';
CREATE UNIQUE INDEX IF NOT EXISTS uk_exec_job_execution_id
  ON wgh_execution_job(execution_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_exec_job_biz
  ON wgh_execution_job(job_type, biz_key)
  WHERE biz_key IS NOT NULL AND deleted = 0;
CREATE INDEX IF NOT EXISTS idx_exec_job_claim
  ON wgh_execution_job(lane, status, scheduled_at)
  WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_exec_job_tenant
  ON wgh_execution_job(tenant_id, status)
  WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_exec_job_lease
  ON wgh_execution_job(status, lease_expire_time)
  WHERE deleted = 0;
