package com.example.agent.client;

import com.example.agent.config.LlmProperties;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 基于 WebClient 的 LLM 客户端实现（当前对接 DeepSeek）。
 *
 * <p>为什么用 WebClient 而不是 RestClient：课时 8 的 SSE 流式只能用 WebClient，
 * 一套客户端同时支持非流式和流式，避免写两遍。
 *
 * <p>⚠️ 本课时【不含】超时和重试 —— 那是课时 9、10 的内容。先把链路跑通。
 */
@Component
public class DeepSeekClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final WebClient webClient;
    private final LlmProperties props;

    public DeepSeekClient(LlmProperties props, WebClient.Builder builder) {
        this.props = props;

        if (!props.hasApiKey()) {
            log.error("llm.api-key 未配置！请设置环境变量 DEEPSEEK_API_KEY，否则调用会返回 401");
        } else {
            log.info("LLM 客户端初始化: baseUrl={}, model={}, apiKey={}",
                    props.getBaseUrl(), props.getModel(), props.maskedApiKey());
        }

        this.webClient = builder
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        long start = System.currentTimeMillis();

        log.debug("发起 LLM 调用: model={}, messages={}, stream={}",
                request.model(), request.messages().size(), request.stream());

        try {
            ChatResponse response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(request)
                    .retrieve()
                    // 把 HTTP 错误状态转成我们的异常
                    .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new LlmException(
                                    "LLM 调用失败: HTTP " + resp.statusCode().value() + " - " + body,
                                    resp.statusCode().value(),
                                    resp.statusCode().is5xxServerError(),   // 5xx 视为可重试
                                    null))))
                    .bodyToMono(ChatResponse.class)
                    // 非流式调用才可以用 block() —— 流式要用 Flux 返回
                    .block(Duration.ofSeconds(90));

            long cost = System.currentTimeMillis() - start;

            if (response == null) {
                throw new LlmException("LLM 返回空响应");
            }

            // ⚠️ 记录【返回的】model，不是请求的 model（别名会漂移，见 agent-notes §2）
            log.info("LLM 调用完成: 耗时={}ms, {}", cost, response.toLogSummary());

            return response;

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM 调用异常: " + e.getMessage(), e);
        }
    }
}
