package com.wgh.rag.workflow.engine;

import com.wgh.rag.common.template.TemplateRenderer;
import com.wgh.rag.workflow.dto.RunContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 变量引用解析器 —— 支持 {{input}} / {{vars.x}} / {{nodeId.text}} / {{nodeId.sources.0.fileName}} / {{loop.iteration}}。
 * 未解析到的路径渲染为空串，避免调试期因缺省数据中断。
 * <p>
 * 渲染核心（占位符语法 / 替换循环 / Map-List 遍历 / 字符串化）统一委托 rag-common 的
 * {@link TemplateRenderer}；本类只保留 workflow 侧的路径解析语义（vars / nodeOutputs / loop 命名空间）。
 */
@Component
public class VariableResolver {

    /** 渲染模板中的所有 {{path}} */
    public String render(String template, RunContext ctx) {
        return TemplateRenderer.render(template, path -> resolvePath(path, ctx));
    }

    /** 解析单个路径 */
    public Object resolvePath(String path, RunContext ctx) {
        if (path == null || ctx == null) return null;
        String[] parts = path.split("\\.");
        if (parts.length == 0) return null;

        switch (parts[0]) {
            case "input" -> {
                return ctx.getVars().get("input");
            }
            case "last" -> {
                return ctx.getVars().get("lastOutput");
            }
            case "loop" -> {
                if (parts.length > 1 && "iteration".equals(parts[1])) {
                    return ctx.getCurrentLoopIteration();
                }
                return null;
            }
            case "vars" -> {
                if (parts.length < 2) return null;
                Object current = ctx.getVars().get(parts[1]);
                return TemplateRenderer.traverse(current, parts, 2);
            }
            default -> {
                Map<String, Object> output = ctx.getNodeOutputs().get(parts[0]);
                if (output == null) return null;
                Object current = output.get(parts.length > 1 ? parts[1] : "text");
                return TemplateRenderer.traverse(current, parts, 2);
            }
        }
    }

    public String stringify(Object value) {
        return TemplateRenderer.stringify(value);
    }
}
