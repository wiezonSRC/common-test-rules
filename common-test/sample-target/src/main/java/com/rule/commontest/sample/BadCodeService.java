package com.rule.commontest.sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BadCodeService {

    // 1. [FAIL] NoFieldInjectionRule: 필드 주입 금지
    @Autowired
    private TransactionTestService testService;

    // 2. [FAIL] JavaConstantsRule: public static 필드는 final이어야 함
    public static String DEFAULT_STATUS = "ACTIVE";

    // 3. [FAIL] LombokUsageRule: LoggerFactory 직접 호출 금지 (@Slf4j 권장)
    private static final Logger logger = LoggerFactory.getLogger(BadCodeService.class);

    @Transactional
    public void doSomethingBad() {
        try {
            // 4. [FAIL] NoSystemOutRule: System.out 금지
            System.out.println("Processing something...");
            
        } catch (Exception e) { 
            // 5. [FAIL] NoGenericCatchRule: Exception 직접 catch 금지
            // 6. [FAIL] TransactionalSwallowExceptionRule: 트랜잭션 내 catch 후 throw 안함
            logger.error("Error occurred");
        }
    }
}
