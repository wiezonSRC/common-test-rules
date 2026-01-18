package com.example.rulecore.util;

import org.apache.ibatis.mapping.BoundSql;

import java.util.HashMap;
import java.util.Map;

public class SqlParamFactory {

    public static Map<String, Object> createDefault() {
        Map<String, Object> p = new HashMap<>();

        // 공통적으로 자주 쓰이는 것들
        p.put("EMP_NO", "TEST01");
        p.put("WORKER", "tester");
        p.put("sys_type", "1");
        p.put("rule_id", "R001");
        p.put("menu_id", "010101");
        p.put("MENU_ID", "010101");

        p.put("stRow", 0);
        p.put("iRows", 10);

        return p;
    }



    public static String explainableSql(BoundSql bs){
        String sql = bs.getSql();

        int paramCnt = bs.getParameterMappings().size();

        for (int i = 0; i <paramCnt; i++) {
            sql = sql.replaceFirst("\\?", "TEST");
        }

        return sql;
    }

}
