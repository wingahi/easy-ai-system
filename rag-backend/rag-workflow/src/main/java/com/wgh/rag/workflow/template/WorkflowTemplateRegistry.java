package com.wgh.rag.workflow.template;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置工作流模板注册表（节点市场第一期）。
 *
 * 模板以图 JSON 内置，实例化走标准创建链路（图校验 + v1 版本快照）。
 * 模板中 modelId / kbIds 为占位空值，实例化后由用户在编辑器中补全。
 */
@Component
public class WorkflowTemplateRegistry {

    @Data
    @Builder
    public static class WorkflowTemplate {
        /** 模板标识 */
        private String templateId;
        /** 模板名称 */
        private String name;
        /** 使用说明（含需要补全的配置项） */
        private String description;
        /** 分类标签 */
        private String category;
        /** 图定义 JSON */
        private String graphJson;
    }

    private final List<WorkflowTemplate> templates = List.of(
            WorkflowTemplate.builder()
                    .templateId("kb-qa")
                    .name("知识库问答")
                    .category("问答")
                    .description("检索知识库 → LLM 基于片段作答。使用前请在检索节点选择知识库、在 LLM 节点选择模型。")
                    .graphJson("""
                            {
                              "nodes": [
                                {"id": "start", "type": "START", "name": "开始", "position": {"x": 80, "y": 160}, "config": {}, "onError": "STOP"},
                                {"id": "kb", "type": "RETRIEVAL", "name": "知识库检索", "position": {"x": 300, "y": 160},
                                 "config": {"kbIds": [], "query": "{{input}}", "topK": 5}, "onError": "STOP"},
                                {"id": "answer", "type": "LLM", "name": "生成回答", "position": {"x": 520, "y": 160},
                                 "config": {"modelId": null, "systemPrompt": "你是严谨的知识库问答助手",
                                   "prompt": "请仅根据以下检索片段回答用户问题；若片段中没有答案，请明确回答「根据现有知识库无法回答」。\\n\\n用户问题：{{input}}\\n\\n检索片段：\\n{{kb.text}}"},
                                 "onError": "STOP"},
                                {"id": "end", "type": "END", "name": "结束", "position": {"x": 740, "y": 160},
                                 "config": {"output": "{{answer.text}}"}, "onError": "STOP"}
                              ],
                              "edges": [
                                {"id": "e1", "source": "start", "target": "kb", "sourceHandle": "default"},
                                {"id": "e2", "source": "kb", "target": "answer", "sourceHandle": "default"},
                                {"id": "e3", "source": "answer", "target": "end", "sourceHandle": "default"}
                              ]
                            }
                            """)
                    .build(),
            WorkflowTemplate.builder()
                    .templateId("approval")
                    .name("人工审批流")
                    .category("审批")
                    .description("整理申请 → 人工审批（HITL 暂停等待确认）→ 按审批意见自动分流批准/驳回。开箱即用。")
                    .graphJson("""
                            {
                              "nodes": [
                                {"id": "start", "type": "START", "name": "开始", "position": {"x": 60, "y": 160}, "config": {}, "onError": "STOP"},
                                {"id": "prepare", "type": "VARIABLE", "name": "整理申请", "position": {"x": 260, "y": 160},
                                 "config": {"assignments": [{"name": "applyInfo", "value": "{{input}}"}]}, "onError": "STOP"},
                                {"id": "approve", "type": "HITL", "name": "人工审批", "position": {"x": 460, "y": 160},
                                 "config": {"prompt": "请审批以下申请：{{vars.applyInfo}}（回复「同意」或「驳回」及理由）"}, "onError": "STOP"},
                                {"id": "judge", "type": "CONDITION", "name": "审批结果", "position": {"x": 660, "y": 160},
                                 "config": {"expression": "{{approve.text}} contains 同意"}, "onError": "STOP"},
                                {"id": "approved", "type": "END", "name": "批准", "position": {"x": 880, "y": 80},
                                 "config": {"output": "已批准：{{vars.applyInfo}}"}, "onError": "STOP"},
                                {"id": "rejected", "type": "END", "name": "驳回", "position": {"x": 880, "y": 240},
                                 "config": {"output": "已驳回：{{vars.applyInfo}}；审批意见：{{approve.text}}"}, "onError": "STOP"}
                              ],
                              "edges": [
                                {"id": "e1", "source": "start", "target": "prepare", "sourceHandle": "default"},
                                {"id": "e2", "source": "prepare", "target": "approve", "sourceHandle": "default"},
                                {"id": "e3", "source": "approve", "target": "judge", "sourceHandle": "default"},
                                {"id": "e4", "source": "judge", "target": "approved", "sourceHandle": "true"},
                                {"id": "e5", "source": "judge", "target": "rejected", "sourceHandle": "false"}
                              ]
                            }
                            """)
                    .build(),
            WorkflowTemplate.builder()
                    .templateId("inspection")
                    .name("定期巡检流")
                    .category("自动化")
                    .description("循环逐项巡检并汇总报告，开箱即用；建议在循环体内替换/追加工具或 LLM 节点执行真实巡检，配合 CRON 触发器定时运行。")
                    .graphJson("""
                            {
                              "nodes": [
                                {"id": "start", "type": "START", "name": "开始", "position": {"x": 60, "y": 160}, "config": {}, "onError": "STOP"},
                                {"id": "patrol", "type": "LOOP", "name": "逐项巡检", "position": {"x": 280, "y": 160},
                                 "config": {"maxIterations": 3, "breakCondition": ""}, "onError": "STOP"},
                                {"id": "record", "type": "VARIABLE", "name": "记录巡检项", "position": {"x": 500, "y": 160},
                                 "config": {"assignments": [{"name": "lastResult", "value": "第 {{loop.iteration}} 项巡检正常"}]}, "onError": "STOP"},
                                {"id": "summary", "type": "END", "name": "巡检报告", "position": {"x": 280, "y": 340},
                                 "config": {"output": "巡检完成，共 {{loop.iteration}} 项：{{vars.lastResult}}"}, "onError": "STOP"}
                              ],
                              "edges": [
                                {"id": "e1", "source": "start", "target": "patrol", "sourceHandle": "default"},
                                {"id": "e2", "source": "patrol", "target": "record", "sourceHandle": "loop"},
                                {"id": "e3", "source": "record", "target": "patrol", "sourceHandle": "default"},
                                {"id": "e4", "source": "patrol", "target": "summary", "sourceHandle": "done"}
                              ]
                            }
                            """)
                    .build()
    );

    public List<WorkflowTemplate> list() {
        return templates;
    }

    public WorkflowTemplate get(String templateId) {
        return templates.stream()
                .filter(t -> t.getTemplateId().equals(templateId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "模板不存在"));
    }
}
