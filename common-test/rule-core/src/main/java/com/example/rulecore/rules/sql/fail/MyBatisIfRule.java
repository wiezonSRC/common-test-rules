package com.example.rulecore.rules.sql.fail;

import com.example.rulecore.ruleEngine.enums.Status;
import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.rules.sql.MybatisUnitBasedRule;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.util.Set;
import java.util.Stack;

/**
 * <if> 태그가 <where>, <set>, <trim> 등 안전한 태그 내부에 위치하는지 검사합니다.
 */
public class MyBatisIfRule extends MybatisUnitBasedRule {

    private final Stack<String> tagStack = new Stack<>();
    private static final Set<String> SAFETY_TAGS = Set.of("where", "set", "trim", "foreach");

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        String tagName = qName.toLowerCase();
        
        if ("if".equals(tagName)) {
            boolean isSafe = tagStack.stream().anyMatch(SAFETY_TAGS::contains);
            if (!isSafe) {
                currentViolations.add(new RuleViolation(
                        "MyBatisIfRule",
                        Status.FAIL,
                        "<if> 태그는 SQL 오류 방지를 위해 <where>, <set>, <trim> 내부에 위치해야 합니다.",
                        currentXmlPath.toString(),
                        getLineNumber()
                ));
            }
        }
        tagStack.push(tagName);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (!tagStack.isEmpty()) {
            tagStack.pop();
        }
    }
}
