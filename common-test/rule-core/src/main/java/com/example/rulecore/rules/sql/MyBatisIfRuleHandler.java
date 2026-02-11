package com.example.rulecore.rules.sql;

import com.example.rulecore.ruleEngine.RuleViolation;
import com.example.rulecore.util.Status;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.util.List;

public class MyBatisIfRuleHandler extends DefaultHandler {

    private final File file;
    private final List<RuleViolation> violations;
    private Locator locator;

    public MyBatisIfRuleHandler(File file, List<RuleViolation> violations) {
        this.file = file;
        this.violations = violations;
    }

    // 📌 라인번호 얻기 위해 반드시 필요
    @Override
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
    }

    @Override
    public void startElement(String uri, String localName,
                             String qName, Attributes attributes)
            throws SAXException {

        if ("if".equals(qName)) {

            int lineNumber = locator != null
                    ? locator.getLineNumber()
                    : 1;

            violations.add(new RuleViolation(
                    "MyBatisXmlRule",
                    Status.FAIL,
                    "<if> 태그 사용시 mapper 의 <where>, <set>, <trim> or <foreach> 를 사용해서 SQL Error 를 방지",
                    file.getAbsolutePath(),
                    lineNumber
            ));
        }
    }
}
