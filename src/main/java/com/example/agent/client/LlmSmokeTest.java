package com.example.agent.client;

import com.example.agent.config.LlmProperties;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 课时 6 的冒烟测试：启动时自动调一次 LLM，把结果打印到控制台。
 *
 * <p>目的是在写 Controller 之前先确认「配置读到了 + 网络通 + 字段解析对」。
 *
 * <p>⚠️ 用完请删掉，或者用 {@code @Profile("local")} 限制只在本地生效 ——
 * 否则每次启动都白烧一次 API 调用。
 */
//@Component
// @Profile("local")   // ← 调试完建议打开这行注释
public class LlmSmokeTest implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LlmSmokeTest.class);

    private final LlmClient llmClient;
    private final LlmProperties props;

    public LlmSmokeTest(LlmClient llmClient, LlmProperties props) {
        this.llmClient = llmClient;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        log.info("");
        log.info("========== LlmSmokeTest 开始 ==========");

        // 1. 先打印配置，确认读到了
        log.info("[配置] baseUrl = {}", props.getBaseUrl());
        log.info("[配置] model   = {}", props.getModel());
        log.info("[配置] apiKey  = {}", props.maskedApiKey());
        log.info("[配置] timeout = connect {}s / read {}s",
                props.getTimeout().getConnect().toSeconds(),
                props.getTimeout().getRead().toSeconds());

        if (!props.hasApiKey()) {
            log.error("[跳过] apiKey 未配置，无法测试调用");
            return;
        }

        // 2. 真实调用
        try {
            ChatResponse resp = llmClient.chat(
                    ChatRequest.of(props.getModel(), "hi", false));

            log.info("[结果] 请求 model = {}", props.getModel());
            log.info("[结果] 实际 model = {}   ← 别名漂移，注意两者可能不同", resp.model());
            log.info("[结果] 回复内容   = {}", resp.firstContent());
            log.info("[结果] finishReason = {}", resp.firstFinishReason());
            log.info("[结果] fingerprint  = {}", resp.systemFingerprint());
            log.info("[结果] tokens       = {}", resp.usage());
            log.info("========== LlmSmokeTest 成功 ==========");

        } catch (LlmException e) {
            log.error("");
            log.error("========== LlmSmokeTest 失败 ==========");
            log.error("原因: {}", e.getMessage());
            if (e.getStatusCode() != null) {
                log.error("HTTP 状态码: {}", e.getStatusCode());
                switch (e.getStatusCode()) {
                    case 401 -> log.error("提示: key 无效或未读到 —— 检查 /api/config-check 的 apiKey 字段");
                    case 402 -> log.error("提示: 余额不足，去 platform.deepseek.com 充值");
                    case 429 -> log.error("提示: 触发限流，课时 10 会做退避重试");
                    default  -> log.error("提示: 检查 baseUrl 和 model 是否正确");
                }
            }
        }

        log.info("");
    }
}
