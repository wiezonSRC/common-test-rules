package com.example.rulecore.rules.sql.fail;

import com.example.rulecore.ruleEngine.enums.Status;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.rules.sql.MybatisUnitBasedRule;
import org.xml.sax.SAXException;

public class NoDollarExpressionRule extends MybatisUnitBasedRule {

    @Override
    public void characters(char[] ch, int start, int length) {
        String text = new String(ch, start, length);
        if (text.contains("${")) {
            currentViolations.add(new RuleViolation(
                    "NoDollarExpressionRule",
                    Status.FAIL,
                    "Found ${} usage (SQL Injection risk). Use #{} instead.",
                    currentXmlPath.toString(),
                    getLineNumber()
            ));
        }
    }
}
