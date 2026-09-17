package com.example.agent.client;

/**
 * LLM 调用异常。
 *
 * <p>课时 10 会在这里加 {@code retryable} 字段，用来区分
 * "可重试"（网络错误 / 429 / 5xx）和"不可重试"（400 / 401 / 402）。
 */
public class LlmException extends RuntimeException {

    private final Integer statusCode;
    private final boolean retryable;

    public LlmException(String message) {
        this(message, null, false, null);
    }

    public LlmException(String message, Throwable cause) {
        this(message, null, false, cause);
    }

    public LlmException(String message, Integer statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
