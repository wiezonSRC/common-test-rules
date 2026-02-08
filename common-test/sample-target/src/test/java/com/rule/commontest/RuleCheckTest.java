package com.rule.commontest;

import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleGroups;
import com.example.rulecore.ruleEngine.RuleRunner;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

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

        String message = error.getMessage();
        
        // 3. 검증: 각 카테고리별 위반 사항이 잘 리포트되었는지 확인

        // [JAVA] 트랜잭션 규칙
        assertTrue(message.contains("TransactionalSwallowExceptionRule"), "트랜잭션 규칙 위반이 감지되어야 합니다.");
        
        // [SQL] MyBatis ${} 사용
        assertTrue(message.contains("NoDollarExpressionRule"), "${} 사용 금지 규칙 위반이 감지되어야 합니다.");

        // [Style] SQL 스타일 (SELECT *)
        assertTrue(message.contains("SqlStyleRule"), "SQL 스타일 규칙 위반이 감지되어야 합니다.");

        // [Style] Java 네이밍 (JavaNamingRule)
        assertTrue(message.contains("JavaNamingRule"), "Java 네이밍 규칙 위반이 감지되어야 합니다.");

        // [Java] System.out 사용 (NoSystemOutRule)
        // 현재 샘플 코드에 System.out이 있으므로 이것도 감지되어야 함 (주석 해제 필요 시)
        // 라이브러리의 NoSystemOutRule은 기본 활성화 상태이므로 감지될 것임
        assertTrue(message.contains("NoSystemOutRule"), "System.out 금지 규칙 위반이 감지되어야 합니다.");
    }
}
