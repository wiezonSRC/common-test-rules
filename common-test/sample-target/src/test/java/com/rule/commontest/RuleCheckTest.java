package com.rule.commontest;

import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleGroups;
import com.example.rulecore.ruleEngine.RuleRunner;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RuleCheckTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("통합 규칙 검사: Java 컨벤션 + SQL 안전성 + 스타일 (그룹 실행)")
    void checkAllRules() {
        // 1. RuleContext 생성
        Path projectRoot = Paths.get(".").toAbsolutePath().normalize();
        List<Path> mapperDirs = List.of(
                projectRoot.resolve("src/main/resources/mapper")
        );

        RuleContext context = new RuleContext(
                "com.rule.commontest", // basePackage
                projectRoot,
                mapperDirs,
                sqlSessionFactory,
                dataSource
        );

        // 2. RuleRunner 실행 (RuleGroups.ALL 사용하여 모든 규칙 검사)
        // 위반 사항이 많으므로 AssertionError가 발생할 것을 기대
        AssertionError error = assertThrows(AssertionError.class, () -> {
            new RuleRunner(RuleGroups.ALL).runOrFail(context);
        });

        // Rule Check 결과
        if(!error.getMessage().isEmpty()){
            Assertions.fail(error.getMessage());
        }else{
            Assertions.assertTrue(true, "Rule Check Success!");
        }
    }
}
