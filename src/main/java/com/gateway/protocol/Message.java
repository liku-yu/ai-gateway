package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条对话消息。
 * content 兼容两种形态：纯文本 String，或多模态分片数组（[{"type":"text","text":"..."}]）。
 * 网关内部统一用 JsonNode 承载，脱敏时只改写其中的文本片段，不破坏结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {

    private String role;

    private JsonNode content;

    private String name;

    @JsonProperty("tool_call_id")
    private String toolCallId;

    @JsonProperty("tool_calls")
    private JsonNode toolCalls;

    public static Message text(String role, String text) {
        return new Message(role, com.fasterxml.jackson.databind.node.TextNode.valueOf(text), null, null, null);
    }

    /** 提取纯文本内容（多模态时仅拼接 text 分片）。 */
    public String contentAsText() {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.hasNonNull("text")) {
                    sb.append(part.get("text").asText());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    /** 用脱敏后的文本回写：纯文本直接替换；多模态则逐片替换 text 字段。 */
    public void setContentText(String text) {
        if (content != null && content.isArray()) {
            List<JsonNode> parts = new ArrayList<>();
            for (JsonNode part : content) {
                if (part.isObject() && part.hasNonNull("text")) {
                    com.fasterxml.jackson.databind.node.ObjectNode copy =
                            ((com.fasterxml.jackson.databind.node.ObjectNode) part).deepCopy();
                    copy.put("text", text);
                    parts.add(copy);
                } else {
                    parts.add(part);
                }
            }
            com.fasterxml.jackson.databind.node.ArrayNode arr =
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            parts.forEach(arr::add);
            this.content = arr;
            return;
        }
        this.content = com.fasterxml.jackson.databind.node.TextNode.valueOf(text);
    }
}
