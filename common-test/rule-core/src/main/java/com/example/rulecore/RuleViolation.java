package com.example.rulecore;

public class RuleViolation {

    private final String ruleName;
    private final String message;
    private final String location;

    public RuleViolation(String ruleName, String message, String location) {
        this.ruleName = ruleName;
        this.message = message;
        this.location = location;
    }

    public String format() {
        return """
        [RULE] %s \n
        %s \n
        %s \n
        """.formatted(ruleName, message, location);
    }
}
