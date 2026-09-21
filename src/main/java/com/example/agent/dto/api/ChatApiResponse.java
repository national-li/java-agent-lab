package com.example.agent.dto.api;

import com.example.agent.dto.ChatResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code POST /api/chat} 的响应体。
 *
 * <p>⭐ 为什么不直接返回内部的 {@link ChatResponse}？（这是课时 7 的核心设计点）
 *
 * <pre>
 *   内部 ChatResponse  ← 结构照着【DeepSeek 的响应格式】设计
 *        ↓ 直接返回的话
 *   问题1：W2 给 ChatResponse 加 tool_calls 字段 → 你的 API 响应自动多一坨
 *   问题2：换供应商（Claude/文心）→ 内部 DTO 要改 → API 跟着变 → 前端全改
 *   问题3：会泄露不该给的内部信息（systemFingerprint / 上游 id / 模型名）
 * </pre>
 *
 * <p>解决办法：加一层【对外契约】。转换逻辑由 {@link #from} 控制，
 * 内部结构怎么变，这个类都不动。
 *
 * <p>这个原则叫「防腐层」（Anti-Corruption Layer）：
 * <pre>
 *   供应商结构  →  内部模型  →  对外契约  →  前端
 *        ↑            ↑           ↑
 *    可以随便变    业务用      保持稳定
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatApiResponse(

        /** 模型回复的内容 */
        String reply,

        /** 用量统计 */
        UsageDto usage
) {

    /**
     * 对外暴露的用量。
     *
     * <p>注意这里【只挑了 3 个字段】—— 内部的 cacheHit/cacheMiss 不暴露。
     * 暴露什么是你的选择，不是被内部 DTO 决定的。
     */
    public record UsageDto(
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) {}

    /**
     * 内部 DTO → 对外契约的转换。
     *
     * <p>⭐ 这个方法就是"防腐层"的具体位置：
     * 以后换供应商，只改这里的取值逻辑，前端完全无感知。
     */
    public static ChatApiResponse from(ChatResponse internal) {
        if (internal == null) {
            return new ChatApiResponse(null, null);
        }

        UsageDto usageDto = null;
        if (internal.usage() != null) {
            var u = internal.usage();
            usageDto = new UsageDto(
                    u.promptTokens() == null ? 0 : u.promptTokens(),
                    u.completionTokens() == null ? 0 : u.completionTokens(),
                    u.totalTokens() == null ? 0 : u.totalTokens());
        }

        return new ChatApiResponse(internal.firstContent(), usageDto);
    }
}
