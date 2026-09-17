package com.example.agent.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * LLM 相关配置，绑定 application.yml 里的 {@code llm.*}。
 *
 * <p>绑定规则（relaxed binding）：YAML 的中划线 → Java 的驼峰
 * <pre>
 *   llm.api-key            → apiKey
 *   llm.base-url           → baseUrl
 *   llm.timeout.connect    → timeout.connect
 *   llm.retry.max-attempts → retry.maxAttempts
 * </pre>
 *
 * <p>需要在启动类上加 {@code @EnableConfigurationProperties(LlmProperties.class)}。
 *
 * <p>为什么用这个而不是散落的 {@code @Value}：
 * 配置项集中、有类型、能自动补全、改配置不用改代码。
 */
@ConfigurationProperties(prefix = "llm")
@Validated
public class LlmProperties {

    /** API Key。默认空 —— 必须由环境变量 DEEPSEEK_API_KEY 注入 */
    @NotBlank(message = "llm.api-key 不能为空，请设置环境变量 DEEPSEEK_API_KEY")
    private String apiKey;

    /** API 基地址 */
    private String baseUrl = "https://api.deepseek.com";

    /**
     * 模型名。用【别名】deepseek-chat 而不是具体的 deepseek-flash ——
     * 别名稳定，服务端换后端模型时不用改代码。
     */
    private String model = "deepseek-chat";

    private Timeout timeout = new Timeout();

    private Retry retry = new Retry();

    // ---------------------------------------------------------------
    // 嵌套配置类
    // ---------------------------------------------------------------

    public static class Timeout {
        /** 建立连接超时 */
        private Duration connect = Duration.ofSeconds(10);
        /** 读取响应超时。流式输出要留足，W1 会用 httpRequestTimeout 单独配 */
        private Duration read = Duration.ofSeconds(60);

        public Duration getConnect() {
            return connect;
        }

        public void setConnect(Duration connect) {
            this.connect = connect;
        }

        public Duration getRead() {
            return read;
        }

        public void setRead(Duration read) {
            this.read = read;
        }
    }

    public static class Retry {
        /** 最大尝试次数（含首次） */
        private int maxAttempts = 3;
        /** 指数退避初始间隔 */
        private Duration backoffInitial = Duration.ofSeconds(1);
        /** 退避倍数：1s → 2s → 4s */
        private int backoffMultiplier = 2;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getBackoffInitial() {
            return backoffInitial;
        }

        public void setBackoffInitial(Duration backoffInitial) {
            this.backoffInitial = backoffInitial;
        }

        public int getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(int backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }
    }

    // ---------------------------------------------------------------
    // 顶层 getter / setter
    // ---------------------------------------------------------------

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Timeout getTimeout() {
        return timeout;
    }

    public void setTimeout(Timeout timeout) {
        this.timeout = timeout;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    /** 便捷方法：key 是否已配置 —— 用于启动时自检 */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** ⚠️ 永远不要输出完整 key。用于日志 */
    public String maskedApiKey() {
        if (!hasApiKey()) {
            return "MISSING";
        }
        return "****" + apiKey.substring(Math.max(0, apiKey.length() - 4));
    }
}
