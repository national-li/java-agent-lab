package com.example.agent.client;

import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;

/**
 * LLM 客户端抽象。
 *
 * <p>为什么先抽接口：
 * <ul>
 *   <li>W3 会引入 Spring AI，实现类可以平行存在、方便对比（面试要用）</li>
 *   <li>测试时可以用假实现，不必真的调 API（不烧钱、不依赖网络）</li>
 * </ul>
 *
 * <p>课时 8 会在这里加流式方法。
 */
public interface LlmClient {

    /**
     * 非流式调用。会阻塞直到拿到完整响应。
     *
     * @throws LlmException LLM 调用失败（网络、认证、限流等）
     */
    ChatResponse chat(ChatRequest request);
}
