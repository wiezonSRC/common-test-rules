package com.example.sqlanalyzer.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class JdbcAnalyzerTest {

    private Connection connection;

    @BeforeEach
    void set() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", "sa", "");

        try(Statement stmt = connection.createStatement()) {

            // payments 만들기
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TABLE payments (");
            sb.append("payment_id VARCHAR(50) PRIMARY KEY,");
            sb.append("order_id VARCHAR(50) NOT NULL,");
            sb.append("amount DECIMAL(10, 2) NOT NULL,");
            sb.append("status VARCHAR(20) NOT NULL,");
            sb.append("created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            sb.append(")");

            stmt.execute(sb.toString());

            // order 만들기
            sb = new StringBuilder();
            sb.append("CREATE TABLE orders (");
            sb.append("order_id VARCHAR(50) PRIMARY KEY,");
            sb.append("user_id VARCHAR(50) NOT NULL,");
            sb.append("mid VARCHAR(10) NOT NULL");
            sb.append(")");

            stmt.execute(sb.toString());

            // merchants (가맹점) 테이블 만들기
            sb = new StringBuilder();
            sb.append("CREATE TABLE merchants (");
            sb.append("mid VARCHAR(10) PRIMARY KEY,");
            sb.append("merchant_name VARCHAR(100) NOT NULL,");
            sb.append("status VARCHAR(20) DEFAULT 'ACTIVE',"); // 가맹점 상태 (예: ACTIVE, SUSPENDED)
            sb.append("created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            sb.append(")");
            stmt.execute(sb.toString());

            // refunds (결제 취소/환불) 테이블 만들기
            sb = new StringBuilder();
            sb.append("CREATE TABLE refunds (");
            sb.append("refund_id VARCHAR(50) PRIMARY KEY,");
            sb.append("payment_id VARCHAR(50) NOT NULL,");
            sb.append("refund_amount DECIMAL(10, 2) NOT NULL,");
            sb.append("reason VARCHAR(255),");
            sb.append("refunded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            sb.append(")");
            stmt.execute(sb.toString());


            sb = new StringBuilder();
            sb.append("CREATE INDEX payments_amount_idx ON payments(amount)");
            stmt.execute(sb.toString());

            sb = new StringBuilder();
            sb.append("CREATE INDEX orders_user_id_idx ON orders(user_id)");
            stmt.execute(sb.toString());

            stmt.execute("CREATE INDEX idx_merchants_status ON merchants(status)");
            stmt.execute("CREATE INDEX idx_refunds_payment_id ON refunds(payment_id)");

            System.out.println("테이블 스키마 및 인덱스 생성 완료");
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

    // sql 문장 필요

    @Test
    @DisplayName("EXPLAIN 실행")
    void doExplain(){

    }


    @Test
    @DisplayName("Table 추출")
    void extractTables(){

    }

    @Test
    @DisplayName("추출된 Table에 대한 DDL 및 Index 정보 찾기")
    void extractDDLAndIndex(){

    }

}