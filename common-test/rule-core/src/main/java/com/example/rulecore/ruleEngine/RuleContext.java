package com.example.rulecore.ruleEngine;

import org.apache.ibatis.session.SqlSessionFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

/**
 * 규칙 검사에 필요한 환경 정보를 담고 있는 컨텍스트 클래스입니다.
 * 패키지 경로, 프로젝트 루트, DB 연결 정보 등을 포함합니다.
 *
 * @param basePackage       검사 대상 Java 패키지 경로
 * @param projectRoot       프로젝트 루트 디렉토리 (파일 스캔용)
 * @param mapperDirs        MyBatis Mapper XML 위치 목록
 * @param sqlSessionFactory MyBatis SQL 세션 팩토리
 * @param dataSource        DB 데이터 소스 (EXPLAIN 실행용)
 */
public record RuleContext(String basePackage, Path projectRoot, List<Path> mapperDirs,
                          SqlSessionFactory sqlSessionFactory, DataSource dataSource) {
}
