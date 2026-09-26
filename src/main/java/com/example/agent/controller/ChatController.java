package com.example.agent.controller;

import com.example.agent.client.LlmClient;
import com.example.agent.config.LlmProperties;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.dto.StreamChunk;
import com.example.agent.dto.api.ChatApiRequest;
import com.example.agent.dto.api.ChatApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 聊天接口 —— 项目的核心业务入口。
 *
 * <p>为什么单独一个类，不放在 {@code PingController}：
 * 那个是【运维/调试】接口（存活探测、配置自检），W6 会被砍掉或加鉴权；
 * 这个会随 W2（工具调用）、W1 后段（流式）持续长大。
 * <b>按"变更原因"划分，不按"技术类型"划分。</b>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final LlmClient llmClient;
    private final LlmProperties props;

    public ChatController(LlmClient llmClient, LlmProperties props) {
        this.llmClient = llmClient;
        this.props = props;
    }

    /**
     * 非流式对话。
     *
     * <p>请求：{@code POST /api/chat}，body {@code {"sessionId":"...", "message":"..."}}
     * <p>响应：{@code {"reply":"...", "usage":{...}}}
     *
     * <p>⚠️ 异常【不在这里 catch】—— 交给 {@code GlobalExceptionHandler} 统一处理。
     * 这样 Controller 只关心正常流程，错误处理逻辑集中在一处。
     */
    @PostMapping("/chat")
    public ChatApiResponse chat(@RequestBody ChatApiRequest request) {

        // ---- 1. 参数校验（W6 会换成 @Valid + @NotBlank 声明式校验）----
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        // ---- 2. 记录请求 ----
        // ⚠️ 只记长度，不记完整内容：
        //    ① 用户消息可能很长 → 日志爆炸
        //    ② 可能包含隐私信息
        String sessionId = request.sessionId() == null ? "anonymous" : request.sessionId();
        log.info("收到对话请求: sessionId={}, messageLength={}",
                sessionId, request.message().length());

        // ---- 3. 调 LLM ----
        // LlmException 会往上抛，由全局异常处理器转成合适的 HTTP 响应
        ChatResponse internal = llmClient.chat(
                ChatRequest.of(props.getModel(), request.message(), false));

        // ---- 4. 转换 + 返回 ----
        // ⭐ 关键：不直接返回 internal，而是走 from() 转换成对外契约
        ChatApiResponse response = ChatApiResponse.from(internal);

        log.info("对话完成: sessionId={}, replyLength={}",
                sessionId, response.reply() == null ? 0 : response.reply().length());

        return response;
    }

    /**
     * 流式对话（SSE）。
     *
     * <p>请求：{@code GET /api/chat/stream/{sessionId}?message=你好}
     * <p>响应：{@code text/event-stream}，逐条推送内容片段
     *
     * <p>为什么用 GET + {@code @PathVariable}/{@code @RequestParam} 而不是 POST：
     * <b>浏览器的 {@code EventSource} 只支持 GET</b>，用 POST 的话前端就没法用原生 SSE API。
     * （W2/W3 如果需要更长的上下文，可以改回 POST，但那时前端得用 fetch + ReadableStream。）
     *
     * <p>⭐ 三个关键点：
     * <ol>
     *   <li>{@code produces = TEXT_EVENT_STREAM_VALUE} —— 让 Spring 用 SSE 编码器</li>
     *   <li>返回 {@code Flux<ServerSentEvent<String>>} —— <b>不用手动拼 "data: xxx\n\n"</b></li>
     *   <li><b>绝不能调 block()</b> —— 那样就退化成非流式了</li>
     * </ol>
     */
    @GetMapping(value = "/chat/stream/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @PathVariable String sessionId,
            @RequestParam String message) {

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        log.info("收到流式请求: sessionId={}, messageLength={}", sessionId, message.length());

        return llmClient.chatStream(ChatRequest.of(props.getModel(), message, true))

                // 过滤空内容：第一条（只有 role）和最后一条（只有 finish_reason）的 content 都是空
                .filter(StreamChunk::hasContent)

                // ⭐ 转成 SSE 事件 —— 格式交给 Spring，不用手拼 "data: " 前缀和空行
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk.content())
                        .build())

                // 流正常结束
                .doOnComplete(() -> log.info("流式请求结束: sessionId={}", sessionId))

                // ⚠️ 异常【不吞掉】—— 让它传到 GlobalExceptionHandler
                //    但流已经开始了的话，HTTP 状态码已经发不出去，只能中断连接。
                //    W1 下半会处理"流中途出错"这个场景（以 event: error 推送）。
                .doOnError(e -> log.error("流式请求异常: sessionId={}", sessionId, e));
    }

}
