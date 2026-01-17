package com.rule.commontest.sample;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
public abstract class SqlIntegrationTestBase {

}