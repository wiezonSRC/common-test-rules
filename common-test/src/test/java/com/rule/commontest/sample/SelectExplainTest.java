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
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;



public class SelectExplainTest extends SqlIntegrationTestBase{
    @Autowired
    DataSource dataSource;

    @Autowired
    SqlSessionFactory factory;

    @Test
    @DisplayName("Select 조회 쿼리 - Explain 테스트")
    void selectSql_shouldExplain() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            Configuration cfg = factory.getConfiguration();

            for (MappedStatement ms : cfg.getMappedStatements()) {
                if (ms.getSqlCommandType() != SqlCommandType.SELECT) continue;

                BoundSql bs = ms.getBoundSql(SqlParamFactory.createDefault());
                String sql = SqlParamFactory.explainableSql(bs);

                try (PreparedStatement ps = conn.prepareStatement("EXPLAIN " + sql)) {
                    ps.executeQuery();
                } catch (SQLException e) {
                    Assertions.fail("EXPLAIN 실패: " + ms.getId() + "\n" + e.getMessage());
                }
            }
        }
    }

}
