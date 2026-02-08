package com.rule.commontest.sample;

import org.springframework.stereotype.Service;

@Service
public class WarnSampleService {

    // 1. [WARN] JavaCodingConventionRule: 변수명이 너무 짧음 (한두 글자)
    private String a;
    private int no; // 'no'는 허용 리스트에 있으므로 통과할 것임

    // 2. [WARN] JavaCodingConventionRule: Enum 사용 권장 (STATUS_로 시작하는 String 상수)
    public static final String STATUS_PENDING = "P";
    public static final String STATUS_COMPLETED = "C";

    // 3. [WARN] JavaNamingRule: 클래스 PascalCase (이미 BadNaming_service에서 테스트 중이나 명시적 추가)
    public void process_data() { // 메서드 camelCase 위반은 ⚠️ 등급
        String temp_val = "test"; 
    }
}
