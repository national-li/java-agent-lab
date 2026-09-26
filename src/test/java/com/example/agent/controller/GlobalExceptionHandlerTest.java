package com.example.agent.controller;

import com.example.agent.client.LlmException;
import com.example.agent.dto.api.ApiErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler} 的单元测试。
 *
 * <p>为什么这个测试比手工调接口有价值：
 * <pre>
 *   手工测试：配错 key → 重启 → 调接口 → 改回来   （每种情况一轮，且依赖环境变量和网络）
 *   单元测试：一次写完，永久回归                  （完全隔离，CI 自动跑）
 * </pre>
 *
 * <p>⭐ 测试本身就是"状态码映射规则"的可执行文档 —— 看这个文件就知道映射关系。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ===============================================================
    //  上游认证/计费类错误 —— ⭐ 最重要的一组
    //  核心断言：不能返回 4xx！因为他们不是客户端的错
    // ===============================================================

    @Nested
    @DisplayName("上游 401（我们的 key 无效）")
    class Upstream401 {

        @Test
        @DisplayName("必须返回 503 而不是 401 —— 否则客户端会以为自己的认证坏了，把用户踢下线")
        void shouldReturn503Not401() {
            LlmException e = new LlmException(
                    "Unauthorized", 401, false, null);

            ResponseEntity<ApiErrorResponse> resp = handler.handleLlmException(e);

            // ⭐ 第一断言：绝不能是 4xx
            assertThat(resp.getStatusCode().is4xxClientError())
                    .as("上游 401 是我们的配置问题，不能返回 4xx 让客户端背锅")
                    .isFalse();

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().code()).isEqualTo(ApiErrorResponse.SERVICE_MISCONFIGURED);
            assertThat(resp.getBody().retryable()).isTrue();
        }

        @Test
        @DisplayName("错误信息必须是[人话]，不能暴露 key 没配这类内部信息")
        void shouldNotLeakInternalDetail() {
            LlmException e = new LlmException(
                    "Insufficient Balance", 402, false, null);

            ResponseEntity<ApiErrorResponse> resp = handler.handleLlmException(e);

            assertThat(resp.getBody().message())
                    .doesNotContain("Balance")
                    .doesNotContain("key")
                    .doesNotContain("401")
                    .doesNotContain("402");
        }
    }

    // ===============================================================
    //  429 限流 —— ⭐ 唯一应该透传给他客户端的状态码
    // ===============================================================

    @Nested
    @DisplayName("上游 429（限流）")
    class Upstream429 {

        @Test
        @DisplayName("透传 429 —— 因为客户端等一下重试确实能成功")
        void shouldPassThrough429() {
            LlmException e = new LlmException("Rate limit exceeded", 429, true, null);

            ResponseEntity<ApiErrorResponse> resp = handler.handleLlmException(e);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(resp.getBody().code()).isEqualTo(ApiErrorResponse.UPSTREAM_RATE_LIMITED);
            assertThat(resp.getBody().retryable()).isTrue();
        }
    }

    // ===============================================================
    //  上游 5xx / 无状态码 —— 服务端侧问题
    // ===============================================================

    @Nested
    @DisplayName("上游 5xx（服务故障）")
    class Upstream5xx {

        @Test
        @DisplayName("返回 502 Bad Gateway")
        void shouldReturn502() {
            LlmException e = new LlmException("Internal Server Error", 500, true, null);

            ResponseEntity<ApiErrorResponse> resp = handler.handleLlmException(e);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(resp.getBody().code()).isEqualTo(ApiErrorResponse.UPSTREAM_ERROR);
            assertThat(resp.getBody().retryable()).isTrue();
        }
    }

    @Nested
    @DisplayName("无 HTTP 状态码（网络异常等）")
    class NoStatusCode {

        @Test
        @DisplayName("返回 502，并标记可重试")
        void shouldReturn502() {
            LlmException e = new LlmException("Connection refused", new RuntimeException("boom"));

            ResponseEntity<ApiErrorResponse> resp = handler.handleLlmException(e);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(resp.getBody().retryable()).isTrue();
        }
    }

    // ===============================================================
    //  超时
    // ===============================================================

    @Nested
    @DisplayName("上游超时")
    class Timeout {

        @Test
        @DisplayName("408 / 504 都映射成 504 Gateway Timeout")
        void shouldReturn504() {
            for (int upstream : new int[]{408, 504}) {
                LlmException e = new LlmException("timeout", upstream, true, null);

                ResponseEntity<ApiErrorResponse> resp = handler.handleLlmException(e);

                assertThat(resp.getStatusCode())
                        .as("上游 %d 应映射为 504", upstream)
                        .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                assertThat(resp.getBody().code()).isEqualTo(ApiErrorResponse.UPSTREAM_TIMEOUT);
            }
        }
    }

    // ===============================================================
    //  客户端参数问题 —— 这才是真正的 400
    // ===============================================================

    @Nested
    @DisplayName("请求参数不合法（客户端的错）")
    class BadRequest {

        @Test
        @DisplayName("返回 400，且标记不可重试 —— 客户端改参数才有用，重试无意义")
        void shouldReturn400NotRetryable() {
            IllegalArgumentException e = new IllegalArgumentException("message 不能为空");

            ResponseEntity<ApiErrorResponse> resp = handler.handleBadRequest(e);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().code()).isEqualTo(ApiErrorResponse.INVALID_REQUEST);
            assertThat(resp.getBody().retryable())
                    .as("参数错误重试无意义")
                    .isFalse();
        }
    }

    // ===============================================================
    //  ⭐ 框架抛的参数异常 —— 也必须自己接住，否则会掉进兜底变 500
    //  实测背景见 agent-notes.md §13.3：两种都曾返回 500
    // ===============================================================

    @Nested
    @DisplayName("缺少必填请求参数（MissingServletRequestParameterException）")
    class MissingParam {

        // ⚠️ Spring 7 的构造器是 (String 参数名, String 类型名) ——
        //    老版本是 (String, Class<?>)，网上 Spring Boot 3.x 的示例在这里编不过
        private final MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("message", "String");

        @Test
        @DisplayName("返回 400 而不是 500 —— 兜底 Exception.class 不该接住它")
        void shouldReturn400Not500() {
            ResponseEntity<ApiErrorResponse> resp = handler.handleMissingParam(ex);

            assertThat(resp.getStatusCode())
                    .as("缺参数是客户端的错，返回 500 会让客户端以为重试有用")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().code()).isEqualTo(ApiErrorResponse.INVALID_REQUEST);
            assertThat(resp.getBody().retryable()).isFalse();
        }

        @Test
        @DisplayName("message 里要带参数名 —— 客户端才知道该补什么")
        void shouldTellWhichParameter() {
            ResponseEntity<ApiErrorResponse> resp = handler.handleMissingParam(ex);

            assertThat(resp.getBody().message()).contains("message");
        }
    }

    @Nested
    @DisplayName("请求体不是合法 JSON（HttpMessageNotReadableException）")
    class NotReadable {

        /**
         * 模拟真实异常：里面带着 Jackson 解析器的内部信息。
         *
         * <p>⚠️ Spring 7 没有单参数的构造器了，至少要给一个 {@code HttpInputMessage}
         * （实测场景里它本来就为 null —— 请求体没读成，自然没有输入流）。
         */
        private final HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character ('n' (code 110)): was expecting double-quote "
                        + "to start property name at [Source: REDACTED; byte offset: #1]",
                (HttpInputMessage) null);

        @Test
        @DisplayName("返回 400")
        void shouldReturn400() {
            ResponseEntity<ApiErrorResponse> resp = handler.handleNotReadable(ex);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().code()).isEqualTo(ApiErrorResponse.INVALID_REQUEST);
            assertThat(resp.getBody().retryable()).isFalse();
        }

        @Test
        @DisplayName("⭐ 不能把解析器内部细节回显给客户端 —— 它只该进日志")
        void shouldNotLeakParserDetail() {
            ResponseEntity<ApiErrorResponse> resp = handler.handleNotReadable(ex);

            assertThat(resp.getBody().message())
                    .as("解析器错误含字节偏移、内部类名等实现细节，属于信息泄露")
                    .doesNotContain("code 110")
                    .doesNotContain("REDACTED")
                    .doesNotContain("byte offset")
                    .doesNotContain("JSON parse error");
        }
    }

    // ===============================================================
    //  兜底 —— 不能泄露内部信息
    // ===============================================================

    @Nested
    @DisplayName("未预期异常（兜底）")
    class Unexpected {

        @Test
        @DisplayName("返回 500，且【绝不能】把异常信息或堆栈暴露给调用方")
        void shouldNotLeakStackTrace() {
            Exception e = new RuntimeException(
                    "com.example.agent.internal.SecretClass 在 /opt/app/config 读取失败");

            ResponseEntity<ApiErrorResponse> resp = handler.handleUnknown(e);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(resp.getBody().message())
                    .as("兜底消息必须是人话，不能带类名/路径等内部信息")
                    .doesNotContain("com.example")
                    .doesNotContain("/opt/app")
                    .doesNotContain("SecretClass");
            assertThat(resp.getBody().retryable()).isFalse();
        }
    }
}
