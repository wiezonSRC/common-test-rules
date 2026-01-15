package com.rule.commontest.sample;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/***
 *
 * [1단계] BoundSqlGenerationTest
 *         → SQL이 "문장으로 만들어질 수 있는가?"
 *
 * [2단계] WriteSqlStaticRuleTest
 *         → 위험한 SQL 패턴은 없는가? (WHERE, ${})
 *
 * [3단계] (선택) SELECT EXPLAIN
 *         → 실행 계획이 가능한가?
 *
 * [4단계] (선택) 통합 테스트
 *         → 실제 DB에서 동작하는가?
 *
 */


/**
 * [BoundSqlGenerationTest]

 * 목적
 * - 모든 MyBatis Mapper SQL이 실행 이전 단계(BoundSql 생성 단계)까지
 *   정상적으로 생성되는지를 검증한다.

 * 검증 범위
 * - <if>, <choose>, <foreach> 등의 동적 SQL 분기 조건 평가
 * - OGNL 표현식에서의 null 참조 여부
 * - #{}, ${} 바인딩 시 파라미터 누락 여부
 * - Mapper XML 문법 오류로 인한 SQL 생성 실패

 * 이 테스트가 잡아낼 수 있는 대표적인 문제
 * - <if test="deleteSet.size() > 0"> 와 같이
 *   null-safe 하지 않은 조건으로 인한 BuilderException
 * - <foreach collection="xxx"> 에서 xxx 파라미터 미존재
 * - 잘못된 OGNL 표현식, 존재하지 않는 Map key 접근
 * - ${} 사용 시 파라미터 누락으로 인한 바인딩 실패

 * 의도적으로 검증하지 않는 항목
 * - 실제 SQL 실행 가능 여부
 * - 테이블 / 컬럼 존재 여부
 * - UPDATE / INSERT / DELETE의 영향 범위
 * - 트랜잭션 동작 여부

 * 비고
 * - 본 테스트는 DB 실행 이전 단계의 '정적 안정성 검사' 성격을 가진다.
 * - 서비스 / 화면 단위 테스트 이전에
 *   SQL 자체가 실행 가능한 형태인지 1차적으로 걸러내는 목적이다.
 * - SI 구조에서 화면 테스트에 의존하던 문제를 사전에 방지하기 위한 최소 안전장치이다.
 */



public class BoundSqlGenerationTest extends SqlIntegrationTestBase{
    @Autowired
    SqlSessionFactory sqlSessionFactory;

    @Test
    void mappedStatementsLoaded() {
        sqlSessionFactory.getConfiguration()
                .getMappedStatements()
                .forEach(ms -> System.out.println(ms.getId()));
    }

    @Test
    @DisplayName("BoundSql 바인딩 Exception 예외 발견 테스트")
    void allMappedSql_shouldGenerateBoundSql() {
        Configuration cfg = sqlSessionFactory.getConfiguration();

        for (Object obj : cfg.getMappedStatements()) {

            if(!(obj instanceof MappedStatement ms)){
                continue;
            }

            if (!(ms.getSqlCommandType() == SqlCommandType.SELECT
                    || ms.getSqlCommandType() == SqlCommandType.UPDATE
                    || ms.getSqlCommandType() == SqlCommandType.INSERT
                    || ms.getSqlCommandType() == SqlCommandType.DELETE)) {
                continue;
            }

            Map<String, Object> param = SqlParamFactory.createDefault();

            try {
                BoundSql boundSql = ms.getBoundSql(param);
                Assertions.assertNotNull(ms.getId(), boundSql.getSql());
            } catch (Exception e) {
                Assertions.fail("BoundSql 생성 실패: " + ms.getId() + "\n" + e.getMessage());
            }
        }
    }
}
