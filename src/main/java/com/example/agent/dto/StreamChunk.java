package com.example.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StreamChunk (
    String content,
    boolean finished,
    String finishReason,
    ChatResponse.Usage usage
) {

    /** 有实际内容才发给客户端 —— 第一条和最后一条都是空的 */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    /** 调试用 */
    @Override
    public String toString() {
        return "StreamChunk{content='%s', finished=%s, finishReason=%s}"
                .formatted(content, finished, finishReason);
    }
}
