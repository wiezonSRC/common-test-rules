package com.example.rulecore.rules;

import com.example.rulecore.ruleEngine.Rule;
import com.example.rulecore.ruleEngine.RuleContext;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.Status;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import java.util.ArrayList;
import java.util.List;

/**
 * ArchUnit 라이브러리를 기반으로 Java 코드의 구조와 컨벤션을 검사하는 기본 클래스입니다.
 * 정적 분석(바이트코드 분석)을 통해 클래스 간 의존성, 패키지 구조, 네이밍 등을 검증합니다.
 */
public abstract class ArchUnitBasedRule implements Rule {

    /**
     * 구체적인 ArchUnit 규칙(ArchRule)을 정의합니다.
     * 상속받는 클래스에서 이 메서드를 구현하여 실제 검증 로직을 작성합니다.
     * 
     * @return 정의된 ArchRule 객체
     */
    protected abstract ArchRule getDefinition();
    
    /**
     * 규칙의 이름을 반환합니다. 기본값은 클래스 명입니다.
     */
    protected String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Context에 지정된 패키지 경로를 스캔하여 ArchUnit 규칙을 평가합니다.
     */
    @Override
    public List<RuleViolation> check(RuleContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        // Context의 basePackage를 기준으로 클래스 임포트
        // 성능 최적화를 위해 캐싱 고려 가능하지만, 지금은 단순하게 매번 임포트
        JavaClasses classes = new ClassFileImporter()
                .importPackages(context.basePackage());

        getDefinition().evaluate(classes).getFailureReport().getDetails().forEach(failure -> {
            violations.add(new RuleViolation(
                    getName(),
                    Status.WARN,
                    failure, // ArchUnit의 상세 실패 메시지
                    "" // 위치 정보는 메시지에 포함되어 있음
            ));
        });

        return violations;
    }
}
