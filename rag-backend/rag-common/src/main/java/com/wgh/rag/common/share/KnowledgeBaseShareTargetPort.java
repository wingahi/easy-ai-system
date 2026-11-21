package com.wgh.rag.common.share;

/**
 * 分享链接对知识库域的只读端口。
 *
 * <p>端口定义放在中立模块 {@code rag-common}，由 rag-kb 提供实现、rag-public-access 只依赖本接口，
 * 避免知识库域反向依赖适配层。</p>
 */
public interface KnowledgeBaseShareTargetPort {

    /**
     * 知识库是否存在。
     *
     * @param kbId 知识库标识
     * @return 是否存在
     */
    boolean exists(Long kbId);
}
