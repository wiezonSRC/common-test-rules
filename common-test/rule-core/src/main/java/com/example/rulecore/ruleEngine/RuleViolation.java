package com.example.rulecore.ruleEngine;

import com.example.rulecore.ruleEngine.enums.Status;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 규칙 위반 사항에 대한 상세 정보를 담는 레코드 객체입니다.
 */
public record RuleViolation(
        String ruleName,
        Status status,
        String message,
        String filePath,
        Integer lineNumber
) {

    @JsonProperty("ideLink")
    public String getIdeLink() {
        return "(" + (filePath != null ? filePath : "unknown") + ":" + (lineNumber != null ? lineNumber : 1) + ")";
    }

    @JsonProperty("fileUrl")
    public String getFileUrl() {
        return "file://" + (filePath != null ? filePath : "") + (lineNumber != null ? ":" + lineNumber : "");
    }

    /**
     * 콘솔 출력을 위한 포맷팅된 문자열을 반환합니다.
     */
    public String format() {
        return """
                [%s] %s
                Message: %s
                Location: %s
                """.formatted(status, ruleName, message, getIdeLink());
    }
}