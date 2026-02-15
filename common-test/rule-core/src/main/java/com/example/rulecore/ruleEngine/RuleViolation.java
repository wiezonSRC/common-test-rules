package com.example.rulecore.ruleEngine;


import com.example.rulecore.ruleEngine.enums.Status;

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

    /**
     * 터미널에서 클릭 가능한 링크 포맷 제공
     * 포맷: "(파일명.java:라인번호)"
     */
    public String getIdeLink() {
        if (filePath == null) return "";
        return "(" + filePath + ":" + (lineNumber != null ? lineNumber : 1) + ")";
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