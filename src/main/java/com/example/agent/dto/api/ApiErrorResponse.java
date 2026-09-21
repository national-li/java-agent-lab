package com.example.agent.dto.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一的错误响应结构。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code code} —— 给【机器】读。前端可以 {@code if (code === 'UPSTREAM_RATE_LIMITED')} 做分支</li>
 *   <li>{@code message} —— 给【人】读。可以直接展示给用户，所以必须是【人话】</li>
 *   <li>{@code retryable} —— 告诉客户端【能不能重试】。客户端不用自己猜</li>
 * </ul>
 *
 * <p>⚠️ 不要把上游（DeepSeek）的原始错误信息直接给用户：
 * 会暴露供应商、接口结构、内部 ID，而且用户看不懂 "Insufficient Balance"。
 * 完整错误记日志，人话给用户。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        Boolean retryable
) {

    // ---------------------------------------------------------------
    // 错误码常量 —— 集中定义，避免各处硬编码字符串
    // ---------------------------------------------------------------

    /** 请求参数不合法（客户端的错） */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    /** 服务端配置问题（比如 API Key 没配好）—— 上游 401/402 都归这类 */
    public static final String SERVICE_MISCONFIGURED = "SERVICE_MISCONFIGURED";

    /** 上游限流，客户端可以稍后重试 */
    public static final String UPSTREAM_RATE_LIMITED = "UPSTREAM_RATE_LIMITED";

    /** 上游服务异常 */
    public static final String UPSTREAM_ERROR = "UPSTREAM_ERROR";

    /** 上游超时 */
    public static final String UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT";

    // ---------------------------------------------------------------
    // 工厂方法
    // ---------------------------------------------------------------

    public static ApiErrorResponse invalidRequest(String message) {
        return new ApiErrorResponse(INVALID_REQUEST, message, false);
    }

    public static ApiErrorResponse serviceMisconfigured() {
        // 不告诉用户"key 没配"——那是内部信息
        return new ApiErrorResponse(SERVICE_MISCONFIGURED, "服务暂时不可用，请稍后重试", true);
    }

    public static ApiErrorResponse rateLimited() {
        return new ApiErrorResponse(UPSTREAM_RATE_LIMITED, "请求过于频繁，请稍后重试", true);
    }

    public static ApiErrorResponse upstreamError() {
        return new ApiErrorResponse(UPSTREAM_ERROR, "上游服务异常，请稍后重试", true);
    }

    public static ApiErrorResponse upstreamTimeout() {
        return new ApiErrorResponse(UPSTREAM_TIMEOUT, "请求超时，请稍后重试", true);
    }
}
