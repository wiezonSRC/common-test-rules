package com.example.ruleplugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class SimpleRulePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {

        RuleGateExtension ext =
                project.getExtensions().create("ruleGate", RuleGateExtension.class);

        // 1. 생성될 디렉토리 경로 정의
        Path genDir = project.getLayout()
                .getBuildDirectory()
                .dir("generated/rule-gate-tests")
                .get()
                .getAsFile()
                .toPath();

        // 2. SourceSet 등록은 Configuration 단계에서 미리 수행해야 함
        //    (그래야 Gradle이 컴파일할 때 이 경로를 소스 경로로 인식함)
        project.getPlugins().withType(JavaPlugin.class, plugin -> {
            JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);
            javaExt.getSourceSets()
                    .getByName("test")
                    .getJava()
                    .srcDir(genDir.toFile());
        });

        // 3. 파일을 생성하는 Task 등록 (Execution 단계에서 실행됨)
        TaskProvider<Task> generateTask = project.getTasks().register("generateRuleGateTest", task -> {
            // Task의 출력 디렉토리 명시 (Gradle 최적화 및 캐싱을 위해)
            task.getOutputs().dir(genDir.toFile());

            // 실제 로직은 doLast 블록(Execution 단계)에서 수행
            task.doLast(t -> {
                try {
                    String basePackage = resolveBasePackage(project, ext);
                    List<Path> mapperDirs = resolveMapperDirs(project, ext);
                    Path projectRoot = project.getProjectDir().toPath();
                    String springBootAppClass = inferSpringBootAppClass(project);

                    if (springBootAppClass == null) {
                        throw new IllegalStateException(
                                "Could not find @SpringBootApplication"
                        );
                    }

                    // 파일 생성 로직 호출
                    writeGateTestFile(
                            project,
                            basePackage,
                            projectRoot,
                            mapperDirs,
                            springBootAppClass,
                            genDir
                    );

                } catch (Exception e) {
                    throw new RuntimeException("[rule-gate] " + e.getMessage(), e);
                }
            });
        });

        // 4. compileTestJava 태스크가 generateRuleGateTest 태스크 이후에 실행되도록 의존성 설정
        project.getTasks().named("compileTestJava", task -> {
            task.dependsOn(generateTask);
        });
    }


    /**
     * basePackage 결정
     */
    private String resolveBasePackage(Project project, RuleGateExtension ext)
            throws IOException {

        if (ext.basePackage != null && !ext.basePackage.isBlank()) {
            project.getLogger().lifecycle(
                    "[rule-gate] basePackage set by user: " + ext.basePackage
            );
            return ext.basePackage;
        }

        String inferred = inferBasePackageFromSpringBoot(project);
        if (inferred != null) {
            project.getLogger().lifecycle(
                    "[rule-gate] basePackage inferred from @SpringBootApplication: " + inferred
            );
            return inferred;
        }

        throw new IllegalStateException(
                "basePackage not found. Please configure ruleGate.basePackage"
        );
    }

    private List<Path> resolveMapperDirs(Project project, RuleGateExtension ext) {

        if (ext.mapperDirs != null && !ext.mapperDirs.isEmpty()) {
            return ext.mapperDirs.stream()
                    .map(p -> project.getProjectDir().toPath().resolve(p))
                    .toList();
        }

        List<String> defaults = List.of(
                "src/main/resources/mapper",
                "src/main/resources/mybatis"
        );

        return defaults.stream()
                .map(p -> project.getProjectDir().toPath().resolve(p))
                .filter(Files::exists)
                .toList();
    }

    private String inferBasePackageFromSpringBoot(Project project)
            throws IOException {

        File srcMainJava = project.file("src/main/java");
        if (!srcMainJava.exists()) return null;

        try (Stream<Path> paths = Files.walk(srcMainJava.toPath())) {
            return paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(this::extractPackageIfSpringBootApp)
                    .filter(p -> p != null)
                    .findFirst()
                    .orElse(null);
        }
    }

    private String inferSpringBootAppClass(Project project) throws IOException {

        File srcMainJava = project.file("src/main/java");
        if (!srcMainJava.exists()) return null;

        try (Stream<Path> paths = Files.walk(srcMainJava.toPath())) {
            return paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("@SpringBootApplication");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> {
                        String path = p.toString().replace("\\", "/");
                        String cls = path.substring(
                                path.indexOf("/src/main/java/") + "/src/main/java/".length()
                        );
                        return cls.replace("/", ".").replace(".java", "");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    private String extractPackageIfSpringBootApp(Path javaFile) {
        try {
            String content = Files.readString(javaFile);

            if (!content.contains("@SpringBootApplication")) {
                return null;
            }

            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.startsWith("package ")) {
                    return line
                            .replace("package", "")
                            .replace(";", "")
                            .trim();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * Gate Test 파일 쓰기 (기존 generateGateTest에서 SourceSet 등록 로직 제외 및 genDir 파라미터 추가)
     */
    private void writeGateTestFile(
            Project project,
            String basePackage,
            Path projectRoot,
            List<Path> mapperDirs,
            String springBootAppClass,
            Path genDir // 파라미터로 받음
    ) throws IOException {

        Files.createDirectories(genDir);
        Path testFile = genDir.resolve("RuleGateTest.java");

        String mapperDirsCode = mapperDirs.stream()
                .map(p -> "java.nio.file.Path.of(\"" +
                        p.toAbsolutePath().toString().replace("\\", "\\\\") +
                        "\")")
                .reduce((a, b) -> a + ",\n                " + b)
                .orElse("");


        Files.writeString(testFile, """
            package generated;

            import com.example.rulecore.RuleContext;
            import com.example.rulecore.RuleRunner;
            import org.apache.ibatis.session.SqlSessionFactory;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.context.SpringBootTest;
            import javax.sql.DataSource;
            
            import java.nio.file.Path;
            import java.util.List;
            
            @SpringBootTest(classes = %s.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
            public class RuleGateTest {
                @Autowired(required=false)
                DataSource dataSource;
            
                @Autowired(required=false)
                SqlSessionFactory sqlSessionFactory;
                
                @Test
                void quality_gate() {
                
                    try{
                            RuleContext context = new RuleContext(
                                "%s",
                                Path.of("%s"),
                                List.of(
                        %s
                                ),
                                sqlSessionFactory,
                                dataSource
                            );
        
                            RuleRunner.runOrFail(context);
                    }catch(Exception e){
                        throw new RuntimeException(e);   
                    }
                }
            }
            """.formatted(
                springBootAppClass,
                basePackage,
                projectRoot.toAbsolutePath().toString().replace("\\", "\\\\"),
                mapperDirsCode
        ));


        project.getLogger().lifecycle(
                "[rule-gate] RuleGateTest generated " +
                        "(basePackage=" + basePackage +
                        ", mapperDirs=" + mapperDirs.size() + ")"
        );
    }

}
