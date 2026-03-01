package com.example.rulecore.ruleEngine;

import com.example.rulecore.ruleEngine.enums.Status;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 규칙 위반 사항에 대한 상세 정보를 담는 레코드 객체입니다.
 */
public record RuleViolation(
        String ruleName,
        Status status,
        String message,
        
        @JsonIgnore 
        String filePath,       // 절대 경로 (콘솔 출력 및 IDE 연동용, JSON 제외)
        
        @JsonProperty("filePath")
        String relativePath,   // 상대 경로 (JSON 리포트에서는 filePath로 출력)
        
        Integer lineNumber
) {

    /**
     * JSON 리포트용 링크 (상대 경로 기반)
     */
    @JsonProperty("ideLink")
    public String getIdeLink() {
        return (relativePath != null ? relativePath : "unknown") + ":" + (lineNumber != null ? lineNumber : 1);
    }

    /**
     * JSON 리포트용 URL (상대 경로 기반)
     */
    @JsonProperty("fileUrl")
    public String getFileUrl() {
        return "file://" + (relativePath != null ? relativePath : "") + (lineNumber != null ? ":" + lineNumber : "");
    }

    /**
     * 콘솔 출력을 위한 포맷팅된 문자열을 반환합니다. (절대 경로 사용으로 IDE 클릭 이동 지원)
     */
    public String format() {
        String absoluteLink = (filePath != null ? filePath : "unknown") + ":" + (lineNumber != null ? lineNumber : 1);
        return """
                ----------------------------------------
                [%s] %s
                ----------------------------------------
                %s
                Message: %s
                """.formatted(status, ruleName, absoluteLink, message);
    }
}
