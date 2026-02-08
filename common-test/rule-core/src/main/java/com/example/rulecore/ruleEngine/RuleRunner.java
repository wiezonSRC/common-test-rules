package com.example.rulecore.ruleEngine;

import com.example.rulecore.report.Report;
import com.example.rulecore.util.Status;

import java.util.ArrayList;
import java.util.List;

public class RuleRunner {

    private final List<Rule> rules = new ArrayList<>();
    private final Report report = new Report();

    public RuleRunner(RuleGroups group) {
        this.rules.addAll(group.getRules());
    }


    public void runOrFail(RuleContext context) {
        List<RuleViolation> violations = run(context);
        report.createFailReport(violations.stream().filter( violation -> violation.getStatus() == Status.FAIL).toList());
        report.createWarnReport(violations.stream().filter( violation -> violation.getStatus() == Status.WARN).toList());
    }

    public List<RuleViolation> run(RuleContext context) {
        List<RuleViolation> all = new ArrayList<>();

        for (Rule rule : rules) {
            try {
                all.addAll(rule.check(context));
            } catch (Exception e) {
                all.add(new RuleViolation(
                        rule.getClass().getSimpleName(),
                        Status.FAIL,
                        "Exception during rule execution: " + e.getMessage(),
                        ""
                ));
            }
        }

        return all;
    }

}
