package com.rule.commontest.sample;

import org.springframework.stereotype.Service;

// [FAIL] JavaStandardRule: 'Service' 패키지가 아니더라도 @Service면 'Service' 접미사 권장 (설정에 따라 다름)
// [FAIL] JavaNamingRule: 클래스 이름에 언더스코어(_) 사용 금지 (PascalCase 위반)
@Service
public class BadNaming_service {
}
