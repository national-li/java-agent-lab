package com.example.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM 非流式响应。
 *
 * <p>字段对照真实返回（见 agent-notes.md §2）：
 * <pre>
 * {
 *   "id": "...", "object": "chat.completion", "created": 1789293475,
 *   "model": "deepseek-flash",              ← ⚠️ 和请求的 deepseek-chat 不一样（别名漂移）
 *   "choices": [ { "index":0, "message":{...}, "finish_reason":"stop" } ],
 *   "usage": { "prompt_tokens":5, ..., "prompt_cache_hit_tokens":0 },
 *   "system_fingerprint": "aeb56401..."
 * }
 * </pre>
 *
 * <p>⚠️ {@code @JsonProperty} 是必须的：Java 字段是驼峰、JSON 是下划线，
 * 不对齐会静默解析成 null。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：API 会返回我们没用到的字段，
 * 不忽略的话反序列化直接失败。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(
        String id,
        String object,
        Long created,
        String model,
        List<Choice> choices,
        Usage usage,
        @JsonProperty("system_fingerprint") String systemFingerprint
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            Integer index,
            Message message,
            @JsonProperty("finish_reason") String finishReason
    ) {
        /**
         * W2 会在这里加 {@code tool_calls} 字段。
         * 调工具时 content 是 null —— 用之前必须判空。
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Message(String role, String content) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens,
            @JsonProperty("prompt_cache_hit_tokens") Integer promptCacheHitTokens,
            @JsonProperty("prompt_cache_miss_tokens") Integer promptCacheMissTokens
    ) {}

    // ---------------------------------------------------------------
    // 便捷方法（W1 日志和 W2 循环会大量使用）
    // ---------------------------------------------------------------

    /** 第一条回复内容。可能为 null（调工具时） */
    public String firstContent() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).message().content();
    }

    /**
     * finish_reason。W2 的循环分支判断依据。
     * 取值：stop / tool_calls / length / content_filter
     */
    public String firstFinishReason() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).finishReason();
    }

    /** 是否有内容（判空用，别直接 .length()） */
    public boolean hasContent() {
        String c = firstContent();
        return c != null && !c.isBlank();
    }

    /** 日志用的摘要 —— 一行看清这次调用的关键信息 */
    public String toLogSummary() {
        Usage u = usage;
        return String.format(
                "model=%s, prompt=%s, completion=%s, total=%s, cacheHit=%s, cacheMiss=%s, finishReason=%s",
                model,
                u == null ? "-" : u.promptTokens(),
                u == null ? "-" : u.completionTokens(),
                u == null ? "-" : u.totalTokens(),
                u == null ? "-" : u.promptCacheHitTokens(),
                u == null ? "-" : u.promptCacheMissTokens(),
                firstFinishReason());
    }
}
