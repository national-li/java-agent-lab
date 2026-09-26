package com.example.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 【上游】DeepSeek 流式响应的单条 chunk —— 忠实映射对方的 JSON 结构。
 *
 * <p>和 {@link StreamChunk} 的区别（这就是"防腐层"的分工）：
 * <pre>
 *   上游 JSON  →  StreamResponse（本类，结构照着对方）  →  StreamChunk（内部模型，只装我要的）
 *                      ↑ 对方改结构，只改这里                  ↑ 我的业务代码只认这个
 * </pre>
 *
 * <p>上游单条 chunk 的真实结构：
 * <pre>
 * {
 *   "id":"...", "object":"chat.completion.chunk", "model":"deepseek-flash",
 *   "choices":[{"index":0, "delta":{"role":"assistant","content":"1"}, "finish_reason":null}],
 *   "usage":{...}                     ← ⚠️ 只有【最后一条】chunk 才有
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StreamResponse(
        String id,
        String object,
        String model,
        List<Choice> choices,
        ChatResponse.Usage usage        // 复用非流式的 Usage —— 结构一样
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            Integer index,
            /** ⚠️ 字段名是 delta，不是 message —— 流式的特有结构 */
            Delta delta,
            @JsonProperty("finish_reason") String finishReason
    ) {}

    /**
     * 增量内容。
     *
     * <p>⚠️ 注意 {@code content} 会是【空字符串】而不是 null：
     * <pre>
     *   第一条：  {"role":"assistant","content":""}          ← 只有角色信息
     *   中间条：  {"content":"1"}                            ← 有实际内容
     *   最后一条：{"content":""} + finish_reason:"stop"       ← 结束标记
     * </pre>
     * 所以判空要用 {@code isBlank()}，不能只判 null。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(String role, String content) {}

    // ---------------------------------------------------------------
    // 转换为内部模型 —— 防腐层的转换点
    // ---------------------------------------------------------------

    /** 提取内容片段。可能为 null 或空串 */
    public String content() {
        if (choices == null || choices.isEmpty()) return null;
        Delta d = choices.get(0).delta();
        return d == null ? null : d.content();
    }

    /** 提取 finish_reason。中间 chunk 是 null，最后一条是 "stop" */
    public String finishReason() {
        if (choices == null || choices.isEmpty()) return null;
        return choices.get(0).finishReason();
    }

    /** 是否结束 —— finish_reason 有值即视为结束 */
    public boolean isFinished() {
        String fr = finishReason();
        return fr != null && !fr.isBlank();
    }

    /** 转成内部模型 */
    public StreamChunk toChunk() {
        return new StreamChunk(content(), isFinished(), finishReason(), usage);
    }
}
