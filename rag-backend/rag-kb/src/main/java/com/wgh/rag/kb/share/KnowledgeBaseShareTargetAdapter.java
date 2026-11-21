package com.wgh.rag.kb.share;

import com.wgh.rag.common.share.KnowledgeBaseShareTargetPort;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 分享链接知识库域端口实现（由 rag-kb 提供，rag-public-access 只依赖端口接口）。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseShareTargetAdapter implements KnowledgeBaseShareTargetPort {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public boolean exists(Long kbId) {
        return kbId != null && knowledgeBaseMapper.selectById(kbId) != null;
    }
}
