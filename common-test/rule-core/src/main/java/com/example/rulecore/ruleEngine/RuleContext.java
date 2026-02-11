package com.example.rulecore.ruleEngine;

import org.apache.ibatis.session.SqlSessionFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 규칙 검사에 필요한 환경 정보를 담고 있는 컨텍스트 클래스입니다.
 * 
 * @param basePackage       검사 대상 Java 패키지 경로
 * @param projectRoot       프로젝트 루트 디렉토리
 * @param mapperDirs        MyBatis Mapper XML 위치 목록
 * @param sqlSessionFactory MyBatis SQL 세션 팩토리
 * @param dataSource        DB 데이터 소스
 * @param affectedFiles     (Optional) Git diff 등을 통해 추출된 변경된 파일 목록
 */
public record RuleContext(
        String basePackage,
        Path projectRoot,
        List<Path> mapperDirs,
        SqlSessionFactory sqlSessionFactory,
        DataSource dataSource,
        List<Path> affectedFiles // 추가된 필드
) {
    /**
     * 기본 생성자 (affectedFiles를 빈 리스트로 초기화)
     */
    public RuleContext(String basePackage, Path projectRoot, List<Path> mapperDirs,
                       SqlSessionFactory sqlSessionFactory, DataSource dataSource) {
        this(basePackage, projectRoot, mapperDirs, sqlSessionFactory, dataSource, Collections.emptyList());
    }

    /**
     * 변경된 파일이 있는지 확인합니다.
     */
    public boolean hasAffectedFiles() {
        return affectedFiles != null && !affectedFiles.isEmpty();
    }
}