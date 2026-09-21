package com.example.agent.controller;

import com.example.agent.client.LlmClient;
import com.example.agent.config.LlmProperties;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.dto.api.ChatApiRequest;
import com.example.agent.dto.api.ChatApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
