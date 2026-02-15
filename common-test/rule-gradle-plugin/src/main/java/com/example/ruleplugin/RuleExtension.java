package com.example.ruleplugin;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * 프로젝트별 설정을 담당하는 Extension 클래스입니다.
 * rule {
 *         // [필수]
 *         basePackage = "com.[Group].[Project]"
 *         // [선택 - 기본값과 다르게 설정하고 싶을 때만]
 *         incremental = true              // 로컬 개발 시 true, CI 환경에선 false 권장
 *         failOnViolation = true          // 엄격한 품질 관리가 필요할 때 true
 *         ruleGroupName = "SQL_CRITICAL"  // SQL 규칙만 집중 점검하고 싶을 때
 *         mapperPaths = [                 // 매퍼 경로가 여러 개일 때
 *             "src/main/resources/mapper/common",
 *             "src/main/resources/mapper/biz"
 *         ]
 *         enableFormatter = true          // 코드 정렬 자동화 사용
 *     }
 *
 */
public abstract class RuleExtension {
    /** 검사 대상 기본 Java 패키지 (필수) */
    public abstract Property<String> getBasePackage();

    /** 실행할 규칙 그룹 (기본값: ALL) */
    public abstract Property<String> getRuleGroupName();

    /** 증분 검사 여부 (기본값: true) */
    public abstract Property<Boolean> getIncremental();

    /** 위반 시 빌드 실패 여부 (기본값: false) */
    public abstract Property<Boolean> getFailOnViolation();

    /** MyBatis 매퍼 XML 경로 목록 (선택) */
    public abstract ListProperty<String> getMapperPaths();

    /** 자동 포맷터(Spotless) 활성화 여부 (기본값: true) */
    public abstract Property<Boolean> getEnableFormatter();
}