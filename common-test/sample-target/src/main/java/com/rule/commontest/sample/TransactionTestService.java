package com.rule.commontest.sample;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TransactionTestService {

    private int Bad_Name; // JavaNamingRule 위반 유발
    private DummyMapper testMapper;

    public void run(){
        try{
            System.out.println("RUN !!!");
            throw new RuntimeException("Exception 발생");
        }catch(Exception e){
            System.out.println("Exception 발생");
            return;
        }
    }


    private class DummyMapper {

    }
}
