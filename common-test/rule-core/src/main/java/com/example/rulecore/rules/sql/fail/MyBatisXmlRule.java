package com.example.rulecore.rules.sql.fail;

import com.example.rulecore.ruleEngine.enums.Status;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.rules.sql.MybatisUnitBasedRule;
import org.xml.sax.SAXException;

/**
 * MyBatis XML 내의 부등호 사용 시 CDATA 섹션 필수 여부를 검사합니다.
 */
public class MyBatisXmlRule extends MybatisUnitBasedRule {

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        String text = new String(ch, start, length);
        if (hasRawInequality(text)) {
            currentViolations.add(new RuleViolation(
                    "MyBatisXmlRule",
                    Status.FAIL,
                    "부등호(<, >) 사용 시 반드시 <![CDATA[ ... ]]> 섹션으로 감싸야 합니다.",
                    currentXmlPath.toString(),
                    getLineNumber()
            ));
        }
    }

    private boolean hasRawInequality(String text) {
        // SQL 연산자로 쓰일법한 패턴 체크
        return text.matches(".*[a-zA-Z0-9_]\\s*[<>]=?\\s*.*") || 
               text.matches(".*\\s+[<>]=?\\s+.*");
    }
}
