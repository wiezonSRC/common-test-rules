package com.example.rulecore.ruleEngine;

import com.example.rulecore.util.Status;


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
     * IDE(IntelliJ, Eclipse) 터미널에서 클릭 가능한 링크 포맷을 생성합니다.
     * 포맷: "(파일명.java:라인번호)"
     */
    public String getIdeLink() {
        if (filePath == null) return "";
        // 파일 경로에서 파일명만 추출하거나 전체 경로를 사용할 수 있습니다.
        // 대부분의 IDE는 (파일명:라인) 또는 (전체경로:라인) 형태를 링크로 인식합니다.
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