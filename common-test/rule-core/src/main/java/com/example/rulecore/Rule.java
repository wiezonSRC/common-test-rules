package com.example.rulecore;

import java.util.List;

public interface Rule {
    List<RuleViolation> check();
}
