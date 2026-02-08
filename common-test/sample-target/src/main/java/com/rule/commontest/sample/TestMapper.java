package com.rule.commontest.sample;

import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Mapper
@Component
public interface TestMapper {
    List<Map<String,Object>> selectTest();
    void insertTest();
    void updateTest();
    void deleteTest();
    List<Map<String,Object>> selectBadPattern();
}
