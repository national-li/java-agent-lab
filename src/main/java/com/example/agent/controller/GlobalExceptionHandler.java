package com.example.agent.controller;

import com.example.agent.client.LlmException;
import com.example.agent.dto.api.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.concurrent.TimeoutException;

/**
 * 全局异常处理 —— 把各类异常转成【合适的 HTTP 状态码 + 统一错误结构】。
 *
 * <p>{@code @RestControllerAdvice} 会拦截所有 Controller 抛出的异常，
 * 这样业务代码里不用写 try-catch，错误处理逻辑集中在一处。
 *
 * <p>⭐ 核心判断原则：<b>HTTP 状态码不是"描述发生了什么"，而是"告诉客户端接下来该干什么"。</b>
 *
 * <pre>
 * 上游 401（我们的 key 配错了）
 *   → 谁的问题？【我们】的
 *   → 客户端能做什么？什么也做不了
 *   → 所以【不能返回 401】—— 那会误导客户端以为"我的认证有问题"
 *   → 正确返回：500 / 503（服务器侧问题）
 * </pre>
 *
 * <p>唯一可以透传的是 <b>429</b>：客户端确实可以"等一下再重试"，这个信息对它有用。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 参数不合法 —— 客户端的问题，返回 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException e) {
        log.warn("请求参数不合法: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.invalidRequest(e.getMessage()));
    }

    /**
     * LLM 调用失败 —— 根据上游状态码决定怎么返回。
     */
    @ExceptionHandler(LlmException.class)
    public ResponseEntity<ApiErrorResponse> handleLlmException(LlmException e) {
        Integer status = e.getStatusCode();

        // 完整错误记日志（排查用），人话给用户
        log.error("LLM 调用失败: status={}, retryable={}, message={}",
                status, e.isRetryable(), e.getMessage());

        if (status == null) {
            // 网络异常等，没有 HTTP 状态码
            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(ApiErrorResponse.upstreamError());
        }

        return switch (status) {
            // 429：上游限流 → 【可以透传】，客户端能"稍后重试"
            case 429 -> ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiErrorResponse.rateLimited());

            // 401 / 402：我们的 key 错 / 余额不足 → 是【我们的问题】，不能返回 4xx
            case 401, 402, 403 -> ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiErrorResponse.serviceMisconfigured());

            // 408 / 504：超时
            case 408, 504 -> ResponseEntity
                    .status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ApiErrorResponse.upstreamTimeout());

            // 5xx：上游故障 → 502 Bad Gateway
            default -> {
                if (status >= 500) {
                    yield ResponseEntity
                            .status(HttpStatus.BAD_GATEWAY)
                            .body(ApiErrorResponse.upstreamError());
                }
                // 其他 4xx：视为我们调用方式有问题，也是服务端侧的问题
                yield ResponseEntity
                        .status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiErrorResponse.serviceMisconfigured());
            }
        };
    }

    /**
     * 超时。
     */
    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handleTimeout(TimeoutException e) {
        log.error("请求超时: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.GATEWAY_TIMEOUT)
                .body(ApiErrorResponse.upstreamTimeout());
    }

    /**
     * 静态资源 / 路径找不到 —— 返回 <b>404</b>，不是 500。
     *
     * <p>⚠️ 没有这个 handler 的话，{@code NoResourceFoundException} 会被下面的
     * {@code Exception.class} 兜底接住，返回 500 —— 语义完全错了。
     *
     * <p>典型触发场景：浏览器自动请求 {@code /favicon.ico}。
     * 这暴露了一个真实教训：<b>兜底 handler 越宽泛，越容易把该 404 的转成 500。</b>
     *
     * <p>日志用 {@code debug} 级别：这类请求通常是噪音，不该在 error 级别刷屏。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException e) {
        log.debug("资源不存在: {}", e.getResourcePath());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("NOT_FOUND", "请求的资源不存在", false));
    }

    /**
     * 兜底 —— 其他所有未处理的异常。
     *
     * <p>⚠️ 这里【绝不能】把异常堆栈或原始 message 返回给用户：
     * 可能泄露内部类名、SQL、文件路径等信息。完整堆栈打日志就好。
     *
     * <p>⚠️ 也要注意 {@code Exception.class} 的副作用：它会吞掉框架自己的异常
     * （比如上面的 NoResourceFoundException）。<b>每发现一种"不该是 500"的情况，
     * 就该补一个专门的 handler。</b>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnknown(Exception e) {
        log.error("未预期的异常", e);   // ← 带堆栈进日志
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("INTERNAL_ERROR", "服务器内部错误", false));
    }

    /**
     * 缺少必填的请求参数 —— 例如流式接口没带 {@code message}。
     *
     * <p>⭐ 为什么必须是 <b>400</b>：这是<b>客户端</b>的错，它自己能改（把参数补上）。
     * 返回 500 等于告诉客户端"你没错，是服务器坏了" —— 客户端会去重试，而重试永远不会成功。
     *
     * <p>⚠️ 不要让它落到 {@code Exception.class} 兜底：
     * 兜底 handler 越宽泛，越容易把"该 4xx"的转成 500。每发现一种这种情况就补一个专门 handler。
     *
     * <p>参数名可以安全地告诉客户端（它不是内部信息），这样用户一眼就知道少了什么。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        // warn 而非 error：是【客户端】的调用姿势不对，不是服务端故障，不该惊动运维
        log.warn("请求缺少必填参数: {}", e.getParameterName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                // 参数名是客户端自己的东西，告诉它才能改；不像内部类名那样需要藏
                .body(ApiErrorResponse.invalidRequest("缺少必填参数: " + e.getParameterName()));
    }

    /**
     * 请求体解析失败 —— 不是合法 JSON，或者压根没有 body。
     *
     * <p>同样返回 <b>400</b>（客户端的错）。
     *
     * <p>⚠️⚠️ 但这里的 {@code message} 处理和上面<b>不一样</b>：
     * {@code e.getMessage()} 里包含<b>原始 JSON 片段 + Jackson 解析器的内部信息</b>
     * （实测长这样：{@code JSON parse error: Unexpected character ('n' (code 110))...
     * at [Source: REDACTED; byte offset: #1]}）。
     * 直接回显给客户端 = <b>信息泄露 + 用户看不懂的噪音</b>。
     * → JSON 里回一句固定人话，完整原因只进日志。
     *
     * <p>日志也建议 {@code log.warn} 且<b>不要打整个堆栈</b> —— 否则又是刷屏。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        // 只记 e.getMessage()，不记堆栈：参数错误是噪音，打堆栈会刷屏（对比下面兜底那个 log.error(...,e)）
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                // ⚠️ 固定人话，【不回显】e.getMessage() —— 那里面带着 Jackson 解析器的内部细节
                .body(ApiErrorResponse.invalidRequest("请求体不是合法的 JSON"));
    }
}
