package com.example.agent.client;

import com.example.agent.config.LlmProperties;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.dto.StreamChunk;
import com.example.agent.dto.StreamResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

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
    private final ObjectMapper objectMapper;

    public DeepSeekClient(LlmProperties props, WebClient.Builder builder, ObjectMapper objectMapper) {
        this.props = props;
        if (!props.hasApiKey()) {
            log.error("llm.api-key 未配置！请设置环境变量 DEEPSEEK_API_KEY，否则调用会返回 401");
        } else {
            log.info("LLM 客户端初始化: baseUrl={}, model={}, apiKey={}",
                    props.getBaseUrl(), props.getModel(), props.maskedApiKey());
        }
        this.objectMapper = objectMapper;
        this.webClient = builder
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        // ⚠️ 前置检查：key 没配就直接抛清楚，不要带着空 token 去请求上游。
        // 否则上游返回 401 → 我们转成 503，排查时【分不清】是"key 没配"还是"key 配错了"。
        // 这个检查让错误信息更精确（也顺便让"删除 key"这个测试场景可复现）。
        if (!props.hasApiKey()) {
            throw new LlmException(
                    "API Key 未配置：请设置环境变量 DEEPSEEK_API_KEY，"
                            + "或检查 application-local.yml 是否覆盖了 llm.api-key",
                    401, false, null);
        }

        long start = System.currentTimeMillis();

        log.debug("发起 LLM 调用: model={}, messages={}, stream={}",
                request.model(), request.messages().size(), request.stream());

        try {
            ChatResponse response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(request)
                    .retrieve()
                    // 把 HTTP 错误状态转成我们的异常
                    .onStatus(HttpStatusCode::isError, this::toLlmException)
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

    @Override
    public Flux<StreamChunk> chatStream(ChatRequest request) {
        // ① 前置检查（key 没配 → LlmException）—— 和 chat() 一样
        if (!props.hasApiKey()) {
            throw new LlmException(
                    "API Key 未配置：请设置环境变量 DEEPSEEK_API_KEY，"
                            + "或检查 application-local.yml 是否覆盖了 llm.api-key",
                    401, false, null);
        }

        // ② 构造带 stream=true 的请求
        //    提示：ChatRequest 是 record，怎么造一个 stream=true 的？
        //          ChatRequest.of(model, message, true) 或用构造器
        ChatRequest streamRequest = new ChatRequest(
                request.model(), request.messages(), true);

        long start = System.currentTimeMillis();
        AtomicReference<ChatResponse.Usage> lastUsage = new AtomicReference<>();

        log.debug("发起流式调用: model={}, messages={}", streamRequest.model(), streamRequest.messages().size());

        // ③ 发起请求
        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(streamRequest)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toLlmException)
                // ③ ⭐ 关键：用 ServerSentEvent 类型消费
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                // ④ 取出 data 载荷："[DONE]" 或 "{...}"
                .map(ServerSentEvent::data)
                // ⑤ ⭐ 遇到 [DONE] 结束流（不再往下传）
                .takeWhile(data -> !"[DONE]".equals(data))
                // ⑥ 解析成 StreamResponse（从没剥过前缀的纯 JSON 串）
                .mapNotNull(this::parseStreamResponse)
                // ⑦ 转成内部模型
                .map(StreamResponse::toChunk)
                // ⑧ 捞 usage（只在最后一条 chunk 里）
                .doOnNext(chunk -> {
                    if (chunk.usage() != null) {
                        lastUsage.set(chunk.usage());
                    }
                })
                // ⑨ 流正常结束 → 记日志（带 usage）
                .doOnComplete(() -> {
                    long cost = System.currentTimeMillis() - start;
                    ChatResponse.Usage u = lastUsage.get();
                    log.info("流式调用完成: 耗时={}ms, totalTokens={}, promptTokens={}, completionTokens={}",
                            cost,
                            u == null ? "-" : u.totalTokens(),
                            u == null ? "-" : u.promptTokens(),
                            u == null ? "-" : u.completionTokens());
                })
                // ⑩ 异常转换
                .onErrorMap(this::mapError);
    }

    private StreamResponse parseStreamResponse(String json) {
        // 提示：
        //  - json 可能为空/空白 → 返回 null（配合 mapNotNull 过滤）
        //  - 用 objectMapper.readValue(json, StreamResponse.class)
        //  - ⚠️ 解析失败怎么办？
        //      抛异常 → 整个流中断
        //      返回 null → 跳过这一条，流继续
        //    【你决定用哪种，并说出理由】
        if (json == null || json.isBlank()) {
            return null;
        }
        if ("[DONE]".equals(json)) {
            return null;
        }

        try {
            return objectMapper.readValue(json, StreamResponse.class);
        } catch (JacksonException e) {
            // ⚠️ 你决定：这里该抛异常中断整个流，还是只记录并跳过？
            log.warn("解析流式数据失败，跳过该条: {}, 原始内容: {}", e.getMessage(), json);
            return null;
        }
    }

    private Mono<Throwable> toLlmException(ClientResponse resp) {
        // 从 chat() 里那段复制过来（同一个逻辑）
        // ⚠️ 复制之后，记得把 chat() 里的也改成调用这个方法 —— 【消除重复】
        return resp.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(new LlmException(
                        "LLM 调用失败: HTTP " + resp.statusCode().value() + " - " + body,
                        resp.statusCode().value(),
                        resp.statusCode().is5xxServerError(),   // 5xx 视为可重试
                        null)));
    }

    private Throwable mapError(Throwable e) {
        // 提示：
        //  - 已经是 LlmException → 原样返回
        //  - 其他异常（网络、解码）→ 包成 LlmException
        //  - ⚠️ 注意判断顺序，别把 LlmException 又包一层
        if (e instanceof LlmException llm) {
            return llm;
        }
        if (e instanceof TimeoutException) {
            return new LlmException("LLM 调用超时: " + e.getMessage(), null, true, e);
        }
        // ③ 连接类异常 → 可重试（网络抖动）
        //    WebClientRequestException 是 WebClient 包装的连接/请求异常
        if (e instanceof WebClientRequestException) {
            return new LlmException("LLM 连接失败: " + e.getMessage(), null, true, e);
        }
        if (e instanceof JsonProcessingException) {
            return new LlmException("LLM 响应解析失败: " + e.getMessage(), null, true, e);
        }
        // ⑤ 兜底：其他异常一律包装，标记为【不可重试】
        //    为什么不可重试：未知异常重试往往只是重复失败 + 白花钱
        return new LlmException("LLM 调用异常: " + e.getMessage(), null, false, e);

    }
}
