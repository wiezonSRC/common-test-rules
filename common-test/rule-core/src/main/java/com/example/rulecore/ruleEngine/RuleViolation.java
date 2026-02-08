package com.example.rulecore.ruleEngine;

import com.example.rulecore.util.Status;


/**
 * 규칙 위반 사항에 대한 상세 정보를 담는 레코드 객체입니다.
 * 
 * @param ruleName 위반된 규칙의 이름
 * @param status   위반 심각도 (FAIL, WARN)
 * @param message  위반 내용에 대한 상세 설명
 * @param location 위반이 발생한 위치 (파일명, 라인 번호 등)
 */
public record RuleViolation(String ruleName, Status status, String message, String location) {

    /**
     * 콘솔 출력을 위한 포맷팅된 문자열을 반환합니다.
     */
    public String format() {
        return """
                [RULE] %s \n
                [STATUS] %s \n
                %s \n
                %s \n
                """.formatted(ruleName, status, message, location);
    }

}
