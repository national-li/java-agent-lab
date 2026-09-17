package com.example.agent.dto;

import java.util.List;

/**
 * 发往 LLM 的请求体。
 *
 * <p>字段名必须和 API 一致 —— Jackson 默认按字段名序列化，所以这里用 {@code messages}/{@code stream} 是安全的。
 *
 * <p>用 Java record：不可变、自动生成 getter/equals/hashCode，比 Lombok 干净。
 *
 * <p>⚠️ W2 会在这里加 {@code tools} 字段；W3 之后消息角色会多出 {@code tool}。
 */
public record ChatRequest(
        String model,
        List<Message> messages,
        boolean stream
) {

    /** 一条对话消息。W2 会扩展出 tool_calls 相关字段 */
    public record Message(String role, String content) {

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    /** 最常用的构造：单轮 user 提问 */
    public static ChatRequest of(String model, String userMessage, boolean stream) {
        return new ChatRequest(model, List.of(Message.user(userMessage)), stream);
    }

    /** 带 system prompt 的构造 */
    public static ChatRequest of(String model, String systemPrompt, String userMessage, boolean stream) {
        return new ChatRequest(model,
                List.of(Message.system(systemPrompt), Message.user(userMessage)),
                stream);
    }
}
