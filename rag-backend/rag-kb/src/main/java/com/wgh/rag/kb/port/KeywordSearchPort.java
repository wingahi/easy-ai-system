package com.wgh.rag.kb.port;

import java.util.List;

/**
 * 关键词检索端口（后端无关契约）。
 *
 * <p>当前默认实现基于 PostgreSQL pg_trgm；独立搜索引擎通过实现本接口接入。
 * 过滤条件由实现翻译为各自方言，命中可携带高亮片段（不支持时为空的列表）。</p>
 */
public interface KeywordSearchPort {

    /** 实现的后端名（用于指标标签与灰度选择）；未声明时记为 unknown。 */
    default String backendName() {
        return "unknown";
    }

    /**
     * 关键词召回。
     *
     * @param collection 集合/索引名
     * @param query      用户查询原文
     * @param topN       最大返回条数
     * @param filter     元数据/租户过滤；为 null 表示不过滤
     */
    List<KeywordHit> search(String collection, String query, int topN, KeywordFilter filter);

    /**
     * 带知识库上下文的关键词召回，供路由层按 KB 白名单灰度。
     * <p>默认忽略 kbId 直接委托四参版本，保证既有实现无需改动。</p>
     *
     * @param kbId 知识库 ID；为 null 表示无 KB 上下文（不走灰度）
     */
    default List<KeywordHit> search(String collection, String query, int topN,
                                    KeywordFilter filter, Long kbId) {
        return search(collection, query, topN, filter);
    }
}
