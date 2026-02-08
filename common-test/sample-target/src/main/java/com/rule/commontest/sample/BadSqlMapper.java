package com.rule.commontest.sample;

import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import java.util.Map;

@Mapper
public interface BadSqlMapper {
    List<Map<String, Object>> findBadSql(Map<String, Object> params);
    List<Map<String, Object>> invalidIfScope(Map<String, Object> params);
    List<Map<String, Object>> badWithClause(Map<String, Object> params);
}