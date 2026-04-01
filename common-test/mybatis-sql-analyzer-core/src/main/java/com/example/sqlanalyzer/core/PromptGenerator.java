package com.example.sqlanalyzer.core;


import org.w3c.dom.Node;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Set;

public class PromptGenerator {

    private PromptGenerator() {}

    public static StringBuilder generatePrompt(Connection connection, String queryId, String mapperPath, Path mapperPathDir) throws Exception {
        // 전제) queryId와 xml 파일이 선택된 이후 로직

        // 1. queryId의 동적 쿼리 Node 추출
        Node queryNode = SqlExtractor.getQueryIdDetail(queryId, mapperPath);

        // 2. queryId의 동적쿼리 추출
        String originalQuery = SqlExtractor.nodeToString(queryNode);

        // 3. 가공된 sql
        String namespace;
        String fakeSql = null;
        if (queryNode != null) {
            namespace = queryNode.getOwnerDocument().getDocumentElement().getAttribute("namespace");
            fakeSql = SqlExtractor.buildFakeSql(queryNode, true, namespace, SqlExtractor.getSqlSnippetRegistry(mapperPathDir));
        }

        String explainInfo = null;
        String metaDataInfo = null;
        if(fakeSql != null){
            // 4. 가공된 sql >> explain 실행
            explainInfo = JdbcAnalyzer.getExplainInfo(connection, fakeSql);

            // 5. 메타데이터 추출 (테이블 정보 + 인덱스 정보)
            Set<String> tables = JdbcAnalyzer.extractTableMethod(fakeSql);
            metaDataInfo = JdbcAnalyzer.getMetaDataInfo(tables, connection.getMetaData()).toString();
        }


        StringBuilder prompt = new StringBuilder();

        // 1. 구체적인 페르소나와 환경 설정
        prompt.append("너는 10년 차 수석 DBA이자 쿼리 튜닝 전문가야.\n");
        prompt.append("우리 시스템은 대용량 트랜잭션이 발생하는 환경이며, MyBatis를 이용해 데이터베이스와 통신하고 있어.\n");
        prompt.append("아래 제공된 쿼리와 실행 계획(Explain), 데이터베이스 메타데이터를 종합적으로 분석해서 정밀한 쿼리 튜닝 리포트를 작성해 줘.\n\n");

        // 2. 실제 데이터 컨텍스트
        prompt.append("[Original Query] : ").append(originalQuery).append("\n");
        prompt.append("[Remove Tag Query] : ").append(fakeSql).append("\n");
        prompt.append("[Explain] : " ).append(explainInfo).append("\n");
        prompt.append("[MetaData] : " ).append(metaDataInfo).append("\n");

        // 3. 구체적인 답변 지침 (Output Format) 요구
        prompt.append("[요청 사항]\n");
        prompt.append("다음 목차에 따라 분석 결과와 개선안을 제시해 줘:\n");
        prompt.append("1. 실행 계획 분석: 병목 구간(예: Full Table Scan, Filesort, 비효율적인 Join 등)과 원인 파악\n");
        prompt.append("2. 인덱스 최적화 제안: 메타데이터를 참고하여, 성능 향상을 위해 추가하거나 수정해야 할 DDL(CREATE INDEX 등)이 있다면 구체적인 이유와 함께 작성\n");
        prompt.append("3. MyBatis 동적 쿼리 리뷰: [Original Query]의 <if>, <foreach> 등의 구조상, 파라미터 조건에 따라 성능 널뛰기나 인덱스 무력화가 발생할 위험이 있는지 점검\n");
        prompt.append("4. 개선된 쿼리 및 매퍼 제안: 최적화가 적용된 최종 SQL과 이를 반영한 MyBatis XML 코드를 작성\n");
        return prompt;
    }
}
