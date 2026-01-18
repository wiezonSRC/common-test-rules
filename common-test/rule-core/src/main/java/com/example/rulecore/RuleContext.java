package com.example.rulecore;

import org.apache.ibatis.session.SqlSessionFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

public class RuleContext {
    private final String basePackage;
    private final Path projectRoot;
    private final List<Path> mapperDirs;
    private final SqlSessionFactory sqlSessionFactory;
    private final DataSource dataSource;

    public RuleContext(
            String basePackage,
            Path projectRoot,
            List<Path> mapperDirs,
            SqlSessionFactory sqlSessionFactory,
            DataSource dataSource
    ) {
        this.basePackage = basePackage;
        this.projectRoot = projectRoot;
        this.mapperDirs = mapperDirs;
        this.sqlSessionFactory = sqlSessionFactory;
        this.dataSource = dataSource;
    }

    public String getBasePackage() {
        return basePackage;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public List<Path> getMapperDirs() {
        return mapperDirs;
    }

    public SqlSessionFactory getSqlSessionFactory(){
        return sqlSessionFactory;
    }

    public DataSource getDataSource(){
        return dataSource;
    }
}
