package com.example.rulecore.ruleEngine;

import java.util.List;

public interface Rule {
    List<RuleViolation> check(RuleContext context) throws Exception;
}
