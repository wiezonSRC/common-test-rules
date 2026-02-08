package com.example.rulecore.ruleEngine;

import java.util.List;

/**
 * 규칙 검증을 위한 최상위 인터페이스입니다.
 * 모든 개별 검증 로직은 이 인터페이스를 구현해야 합니다.
 */
public interface Rule {
    /**
     * 규칙 위반 여부를 검사합니다.
     * 
     * @param context 검사에 필요한 컨텍스트 정보 (패키지 경로, DB 연결 등)
     * @return 위반 사항 목록 (없을 경우 빈 리스트)
     * @throws Exception 검사 과정에서 발생한 예외
     */
    List<RuleViolation> check(RuleContext context) throws Exception;
}
