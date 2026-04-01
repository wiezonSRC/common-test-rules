//package com.example.sqlanalyzer.plugin;
//
//import org.gradle.api.Plugin;
//import org.gradle.api.Project;
//
//public class SqlAnalyzerPlugin implements Plugin<Project> {
//    @Override
//    public void apply(Project project) {
//        SqlAnalyzerExtension extension = project.getExtensions().create("sqlAnalyzer", SqlAnalyzerExtension.class);
//
//        project.getTasks().register("generateSqlPrompt", SqlAnalyzerTask.class, task -> {
//            task.setExtension(extension);
//            task.setGroup("sql analyzer");
//            task.setDescription("Generates an AI prompt for a specific MyBatis SQL query performance analysis.");
//        });
//    }
//}
