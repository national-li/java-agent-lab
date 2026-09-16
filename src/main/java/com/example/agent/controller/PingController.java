package com.example.agent.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基础探测接口。
 *
 * <p>{@code /api/config-check} 是 W1 开发 LLM 客户端前的自检工具：
 * 用来确认「配置文件有没有被读到」以及「API key 有没有注入成功」。
 *
 * <p>排查顺序建议：配置没读到 → 后面的代码全是白写。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Value("${llm.model:NOT_SET}")
    private String llmModel;

    @Value("${llm.base-url:NOT_SET}")
    private String llmBaseUrl;

    /** key 的默认值在 application.yml 里是空的，所以只有真正注入成功时这里才非空 */
    @Value("${llm.api-key:}")
    private String apiKey;

    /** 最基础的存活检查 */
    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    /**
     * 配置自检。
     *
     * <p>⚠️ 只暴露「有没有」和「长度」，绝不返回 key 本身 —— 这个原则在 W1 写日志时同样适用。
     */
    @GetMapping("/config-check")
    public Map<String, String> configCheck() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("activeProfile", activeProfile);
        result.put("llmModel", llmModel);
        result.put("llmBaseUrl", llmBaseUrl);

        boolean hasKey = apiKey != null && !apiKey.isBlank();
        result.put("apiKey", hasKey ? "LOADED (length=" + apiKey.length() + ")" : "MISSING");

        // 环境变量单独看一眼：区分「环境变量没设」和「Spring 没读到」
        result.put("envVarPresent",
                System.getenv("DEEPSEEK_API_KEY") != null ? "yes" : "no");

        return result;
    }
}
