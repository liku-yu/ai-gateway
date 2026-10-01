package com.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** OpenAI 风格错误体，业务侧 SDK 可直接解析。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private ErrorDetail error;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {
        private String message;
        private String type;
        private String code;
        private String param;
        private String traceId;
        /** 可选：给业务的重试建议（秒）。 */
        private Integer retryAfter;
    }

    public static ErrorResponse of(String message, String type, String code, String traceId) {
        return new ErrorResponse(new ErrorDetail(message, type, code, null, traceId, null));
    }
}
