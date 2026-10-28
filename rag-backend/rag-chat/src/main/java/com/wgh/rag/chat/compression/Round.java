package com.wgh.rag.chat.compression;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.Getter;
import lombok.Setter;

/**
 * 轮次：一对 user + ai（ai 可能为 null 表示最后一轮用户还没收到回复）
 */
@Getter
public class Round {

    private final UserMessage user;

    @Setter
    private AiMessage ai;

    public Round(UserMessage user, AiMessage ai) {
        this.user = user;
        this.ai = ai;
    }
}
