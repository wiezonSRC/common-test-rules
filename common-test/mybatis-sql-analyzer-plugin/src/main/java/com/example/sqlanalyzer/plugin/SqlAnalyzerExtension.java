package com.example.sqlanalyzer.plugin;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import java.util.List;

public abstract class SqlAnalyzerExtension {
    /** 소스 루트 경로 (기본값: src/main) */
    public abstract Property<String> getSourceRoot();

    /** MyBatis 매퍼 XML 경로 목록 */
    public abstract ListProperty<String> getMapperPaths();

    /** JDBC 연결 URL */
    public abstract Property<String> getJdbcUrl();

    /** DB 사용자명 */
    public abstract Property<String> getJdbcUser();

    /** DB 비밀번호 */
    public abstract Property<String> getJdbcPassword();

    /** [선택] 분석 대상 SQL ID (태스크 인자로도 가능) */
    public abstract Property<String> getQueryId();
}
