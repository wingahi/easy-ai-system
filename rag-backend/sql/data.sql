-- ========================================================
-- WGH RAG System 种子数据
-- 依赖：先执行 schema.sql 建表
-- ========================================================

-- ============ 初始化示例数据 ============

INSERT INTO wgh_embedding_model (name, provider, model_name, base_url, api_key, dimension, status, is_default, remark) VALUES
('通义 text-embedding-v2', 'DASHSCOPE', 'text-embedding-v2', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'YOUR_DASHSCOPE_API_KEY', 1536, 1, 1, '阿里云通义嵌入模型示例');

INSERT INTO wgh_llm_model (name, model_type, provider, model_name, base_url, api_key, max_context_tokens, temperature, compression_model, status, remark) VALUES
('通义 qwen-plus', 'CHAT', 'DASHSCOPE', 'qwen-plus', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'YOUR_DASHSCOPE_API_KEY', 4096, 0.7, FALSE, 1, '阿里云通义模型示例'),
('DeepSeek V3', 'CHAT', 'DEEPSEEK', 'deepseek-chat', 'https://api.deepseek.com/v1', 'YOUR_DEEPSEEK_API_KEY', 65536, 0.7, FALSE, 1, 'DeepSeek 模型示例');

INSERT INTO wgh_tool (name, tool_type, description, definition, executor_config, status, is_example) VALUES
('天气查询 Skill', 'SKILL', '查询指定城市当前天气状况',
 '{"type":"function","function":{"name":"get_weather","description":"获取指定城市的当前天气","parameters":{"type":"object","properties":{"city":{"type":"string","description":"城市名称如：北京、上海"},"unit":{"type":"string","enum":["celsius","fahrenheit"],"description":"温度单位"}},"required":["city"]}}}',
 E'{"mode":"inline","promptTemplate":"---SYSTEM---\\n你是天气查询助手，请根据用户提供的城市给出当前天气概况。\\n参数不足时请说明缺少什么。\\n---USER---\\n查询城市：{{city}}","subModelDefault":true}', 1, 1),

('MCP Echo 示例', 'MCP', '通过 MCP 协议调用 echo 工具回显消息',
 '{"type":"function","function":{"name":"echo","description":"通过 MCP 服务器回显一段消息","parameters":{"type":"object","properties":{"message":{"type":"string","description":"要回显的消息内容"}},"required":["message"]}}}',
 '{"transport":"stdio","command":"npx","args":["-y","@modelcontextprotocol/server-everything"],"timeoutMs":60000}', 1, 1),

('后端健康检查 HTTP', 'HTTP', '调用后端健康检查接口获取服务状态',
 '{"type":"function","function":{"name":"get_system_health","description":"查询 RAG 后端服务的健康状态","parameters":{"type":"object","properties":{"verbose":{"type":"string","description":"是否返回详细信息，可留空"}}}}}',
 '{"url":"http://localhost:8080/actuator/health","method":"GET","timeoutMs":5000}', 1, 1),

('用户订单查询 Function', 'FUNCTION_CALLING', '反射调用本地 handler 查询用户订单',
 '{"type":"function","function":{"name":"query_user_order","description":"查询指定用户的订单列表","parameters":{"type":"object","properties":{"userId":{"type":"string","description":"用户ID，如 U1001"},"status":{"type":"string","enum":["all","pending","paid","completed"],"description":"订单状态筛选，默认 all"}},"required":["userId"]}}}',
 '{"handlerClass":"com.wgh.rag.tool.demo.OrderQueryHandler","method":"query"}', 1, 1);

-- ============ 老库兼容修复：SKILL executor_config 格式升级 ============
-- 种子 INSERT 已是新格式（mode=inline + promptTemplate），新库跑时 WHERE 不命中，空跑
-- 老库如果存在 built-in 旧格式的 SKILL（无 promptTemplate 也无 skillPath），统一转为 inline 模式
-- 对应原 migration_20261027_tool_skill_executor_config.sql
UPDATE wgh_tool
SET executor_config = jsonb_build_object(
        'mode', 'inline',
        'promptTemplate',
          '---SYSTEM---' || chr(10) ||
          '你是「' || name || '」技能助手。' || COALESCE(NULLIF(description, ''), '') || chr(10) ||
          '请根据用户提供的参数完成任务；参数不足时请说明缺少什么，不要编造参数之外的信息。',
        'subModelDefault', TRUE
    )::text,
    update_time = CURRENT_TIMESTAMP
WHERE tool_type = 'SKILL'
  AND COALESCE(executor_config, '') NOT LIKE '%promptTemplate%'
  AND COALESCE(executor_config, '') NOT LIKE '%skillPath%';

-- ============ 老库兼容修复：wgh_m_agent_step.run_id 类型修正 ============
-- 历史版本该列被错误建为 BIGINT，而 wgh_m_agent_run.run_id 为 VARCHAR(64)（'mag-...' 形式），
-- 导致步骤写入与 runs/{runId}/state、steps 查询全部失败。
-- 新库 schema.sql 已直接建为 VARCHAR(64)；本语句仅在老库列类型仍为 bigint 时执行，幂等可重复。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'wgh_m_agent_step' AND column_name = 'run_id' AND data_type = 'bigint'
    ) THEN
        ALTER TABLE wgh_m_agent_step ALTER COLUMN run_id TYPE VARCHAR(64) USING run_id::text;
    END IF;
END $$;
