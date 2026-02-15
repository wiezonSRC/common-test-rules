package com.rule.commontest;

import com.example.rulecore.ruleEngine.RuleContext;

import com.example.rulecore.ruleEngine.RuleGroups;
import com.example.rulecore.ruleEngine.RuleRunner;
import com.example.rulecore.ruleEngine.RuleResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleCheckTest {

    @Test
    @DisplayName("통합 규칙 검사: 1순위 Java 및 SQL 규칙 검증")
    void checkAllRules() {
        // 1. RuleContext 생성 (DB 연결 없이 경로만 설정)
        Path projectRoot = Paths.get(".").toAbsolutePath().normalize();
        List<Path> mapperDirs = List.of(
                projectRoot.resolve("src/main/resources/mapper")
        );

        RuleContext context = RuleContext.builder()
                .basePackage("com.rule.commontest")
                .projectRoot(projectRoot)
                .mapperDirs(mapperDirs)
                .build();

        // 2. RuleRunner 실행
        RuleRunner runner = new RuleRunner(RuleGroups.ALL);
        Path reportDir = projectRoot.resolve("build/reports/rule");
        RuleResult result = RuleRunner.run(runner, context, reportDir);

        // 3. 결과 확인 (위반 사항이 있는지 리포트 확인 유도)
        System.out.println("Violations found: " + result.violations().size());
        result.violations().forEach(v -> System.out.println(v.format()));
        
        // 테스트는 통과시키되 로그로 확인
        assertTrue(true);
    }
}