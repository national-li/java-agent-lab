package com.example.agent.dto.api;

/**
 * {@code POST /api/chat} 的请求体。
 *
 * <p>这是【对外契约】的一部分 —— 一旦发布，改动成本很高。
 * 所以即使某些字段现在用不上，也先留着（见 sessionId）。
 */
public record ChatApiRequest(

        /**
         * 会话 ID。
         *
         * <p>⚠️ 现在【不处理】—— Redis 会话历史是课时 11.5 的内容。
         * 但接口要先留着：否则以后加这个字段，前端/文档/测试全要改。
         * 这是"预留扩展位"的做法。
         */
        String sessionId,

        /** 用户输入 */
        String message
) {
}
