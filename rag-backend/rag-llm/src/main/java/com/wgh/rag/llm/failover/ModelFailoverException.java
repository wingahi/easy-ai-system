package com.wgh.rag.llm.failover;

import com.wgh.rag.common.exception.BusinessException;

/**
 * 故障转移链路的终态业务异常：消息已脱敏且对用户可读。
 */
public class ModelFailoverException extends BusinessException {

    public ModelFailoverException(String message, Throwable cause) {
        super(message, cause);
    }
}
