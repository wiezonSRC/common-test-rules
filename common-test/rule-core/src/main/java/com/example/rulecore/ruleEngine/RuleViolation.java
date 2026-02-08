package com.example.rulecore.ruleEngine;

import com.example.rulecore.util.Status;


public class RuleViolation {

    private final String ruleName;
    private final Status status;
    private final String message;
    private final String location;

    public RuleViolation(String ruleName, Status status, String message, String location) {
        this.ruleName = ruleName;
        this.status   = status;
        this.message  = message;
        this.location = location;
    }

    public Status getStatus(){
        return this.status;
    }

    public String format() {
        return """
        [RULE] %s \n
        [STATUS] %s \n
        %s \n
        %s \n
        """.formatted(ruleName,status, message, location);
    }

}
