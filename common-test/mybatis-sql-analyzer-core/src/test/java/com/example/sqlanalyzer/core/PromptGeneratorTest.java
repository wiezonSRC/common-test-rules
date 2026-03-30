package com.example.sqlanalyzer.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
class PromptGeneratorTest {

    private Connection connection;

    @BeforeEach
    void set() throws Exception {
        // H2 메모리 DB 설정 (테이블 및 인덱스 생성)
        connection = DriverManager.getConnection("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", "sa", "");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE payments (payment_id VARCHAR(50) PRIMARY KEY, order_id VARCHAR(50) NOT NULL, amount DECIMAL(10, 2) NOT NULL, status VARCHAR(20) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE orders (order_id VARCHAR(50) PRIMARY KEY, user_id VARCHAR(50) NOT NULL, mid VARCHAR(10) NOT NULL)");
            stmt.execute("CREATE TABLE merchants (mid VARCHAR(10) PRIMARY KEY, merchant_name VARCHAR(100) NOT NULL, status VARCHAR(20) DEFAULT 'ACTIVE', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE refunds (refund_id VARCHAR(50) PRIMARY KEY, payment_id VARCHAR(50) NOT NULL, refund_amount DECIMAL(10, 2) NOT NULL, reason VARCHAR(255), refunded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            stmt.execute("CREATE INDEX payments_amount_idx ON payments(amount)");
            stmt.execute("CREATE INDEX orders_user_id_idx ON orders(user_id)");
            stmt.execute("CREATE INDEX idx_merchants_status ON merchants(status)");
            stmt.execute("CREATE INDEX idx_refunds_payment_id ON refunds(payment_id)");
        }

    }

    @AfterEach
    void remove() throws Exception{
        if(connection != null && !connection.isClosed()){
            try(Statement stmt = connection.createStatement()){
                stmt.execute("DROP ALL OBJECTS");
            }
            connection.close();
        }
    }

    //set : 제대로 만들어진 rawSql, dynamicSql, Explain, tableList, DDL, Index

    /**
     * [설정]
     *
     * - database 정보
     * - mapperBasePath 정보
     *
     * [로직]
     *
     * 1. queryId 입력 (파라미터로 받기)
     *
     * 2. 해당 queryId가 있는 xml 파일 리스트 선택 문장 제공 **
     *
     * 3. 선택된 xml파일의 queryId 동적쿼리를 fakeSql로 가공
     *
     * 4. fakeSql를 이용하여 explain 실행
     *
     * 5. fakeSql의 테이블들 정보 추출
     *
     * 6. 테이블들의 ddl 및 인덱스정보 추출
     *
     * 7. database 정보 + 동적쿼리+ fakeSql + explain 결과 + ddl + 인덱스정보 쿼리 튜닝 이라는 문구 출력
     *
     * 해당 출력물을 복사 붙여넣기 쉽게 파일로 저장 **
     */
    @Test
    @DisplayName("Prompt 생성")
    void printPrompt(){

    }
  
}