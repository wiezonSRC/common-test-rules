package com.rule.commontest.sample;

import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import java.util.Map;

@Mapper
public interface WarnSqlMapper {
    List<Map<String, Object>> findWithBadAlias();
    List<Map<String, Object>> findWithDeepNesting();
    List<Map<String, Object>> findWithFullScan();
}
