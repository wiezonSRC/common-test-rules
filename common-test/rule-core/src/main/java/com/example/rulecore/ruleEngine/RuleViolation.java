package com.example.rulecore.ruleEngine;

import com.example.rulecore.util.Status;
import lombok.Getter;


@Getter
public record RuleViolation(String ruleName, Status status, String message, String location) {

    public String format() {
        return """
                [RULE] %s \n
                [STATUS] %s \n
                %s \n
                %s \n
                """.formatted(ruleName, status, message, location);
    }

}
