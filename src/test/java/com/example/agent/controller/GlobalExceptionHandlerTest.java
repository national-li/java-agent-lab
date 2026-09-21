package com.example.agent.controller;

import com.example.agent.client.LlmException;
import com.example.agent.dto.api.ApiErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
