/*
 * Copyright 2026 Franz Schöning
 * Project: https://www.zeroz4j.com
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zeroz4j.apt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class RmiAnnotationProcessorTest {

    @Test
    public void testProcessorGeneratesStubAndSerializer(@TempDir Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("com/test"));

        // Create a model and service
        String modelSrc = "package com.test;\n" +
                "import com.zeroz4j.api.BinaryModel;\n" +
                "import com.zeroz4j.api.BinaryPackable;\n" +
                "import com.zeroz4j.api.ObjectMapper;\n" +
                "import com.zeroz4j.api.GrowableBuffer;\n" +
                "import java.nio.ByteBuffer;\n" +
                "@BinaryModel\n" +
                "public class MyModel implements BinaryPackable {\n" +
                "    public String name;\n" +
                "    @Override public void writeToBuffer(GrowableBuffer b, ObjectMapper m) {}\n" +
                "    @Override public void readFromBuffer(ByteBuffer b, ObjectMapper m) {}\n" +
                "}\n";
        
        String serviceSrc = "package com.test;\n" +
                "import com.zeroz4j.api.RmiService;\n" +
                "@RmiService\n" +
                "public interface MyService {\n" +
                "    MyModel getModel(int id);\n" +
                "}\n";

        Files.writeString(srcDir.resolve("com/test/MyModel.java"), modelSrc);
        Files.writeString(srcDir.resolve("com/test/MyService.java"), serviceSrc);

        // Compile
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(Arrays.asList(
                srcDir.resolve("com/test/MyModel.java").toFile(),
                srcDir.resolve("com/test/MyService.java").toFile()
        ));

        Path outDir = tempDir.resolve("out");
        Files.createDirectories(outDir);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaCompiler.CompilationTask task = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                Arrays.asList("-d", outDir.toString(), "-s", outDir.toString()),
                null,
                compilationUnits
        );

        task.setProcessors(Collections.singletonList(new RmiAnnotationProcessor()));

        boolean success = task.call();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            System.err.println(diagnostic);
        }
        assertTrue(success, "Compilation should succeed");

        // Verify generated files
        File generatedStub = outDir.resolve("com/test/MyService_Stub.java").toFile();
        File generatedSerializer = outDir.resolve("com/test/MyModel_Serializer.java").toFile();
        File generatedRegistrar = outDir.resolve("com/zeroz4j/generated/BinaryPackableRegistrar.java").toFile();

        assertTrue(generatedStub.exists(), "Stub should be generated");
        assertTrue(generatedSerializer.exists(), "Serializer should be generated");
        assertTrue(generatedRegistrar.exists(), "Registrar should be generated");

        String stubContent = Files.readString(generatedStub.toPath());
        assertTrue(stubContent.contains("class MyService_Stub implements com.test.MyService"));
        assertTrue(stubContent.contains("RmiClientExecutor.executeCall(\"com.test.MyService\", \"getModel\", args)"));

        String serializerContent = Files.readString(generatedSerializer.toPath());
        assertTrue(serializerContent.contains("class MyModel_Serializer"));
        assertTrue(serializerContent.contains("BinarySerializer.writeString"));
    }

    @Test
    public void testStubIncludesInheritedInterfaceMethods(@TempDir Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("com/test"));

        String baseSrc = "package com.test;\n" +
                "public interface BaseService {\n" +
                "    String ping();\n" +
                "    default String version() { return \"1\"; }\n" +
                "}\n";

        String serviceSrc = "package com.test;\n" +
                "import com.zeroz4j.api.RmiService;\n" +
                "@RmiService\n" +
                "public interface ExtendedService extends BaseService {\n" +
                "    int count();\n" +
                "}\n";

        Files.writeString(srcDir.resolve("com/test/BaseService.java"), baseSrc);
        Files.writeString(srcDir.resolve("com/test/ExtendedService.java"), serviceSrc);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(Arrays.asList(
                srcDir.resolve("com/test/BaseService.java").toFile(),
                srcDir.resolve("com/test/ExtendedService.java").toFile()
        ));

        Path outDir = tempDir.resolve("out");
        Files.createDirectories(outDir);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaCompiler.CompilationTask task = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                Arrays.asList("-d", outDir.toString(), "-s", outDir.toString()),
                null,
                compilationUnits
        );

        task.setProcessors(Collections.singletonList(new RmiAnnotationProcessor()));

        boolean success = task.call();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            System.err.println(diagnostic);
        }
        assertTrue(success, "Compilation should succeed");

        String stubContent = Files.readString(outDir.resolve("com/test/ExtendedService_Stub.java"));
        assertTrue(stubContent.contains("\"count\""), "Stub should implement its own method");
        assertTrue(stubContent.contains("\"ping\""), "Stub should implement the inherited method");
        assertTrue(!stubContent.contains("version"), "Stub must not override default methods");
    }

    @Test
    public void testValidationRulesGeneration(@TempDir Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("com/test"));

        String modelSrc = "package com.test;\n" +
                "import com.zeroz4j.api.BinaryModel;\n" +
                "import com.zeroz4j.api.BinaryPackable;\n" +
                "import com.zeroz4j.api.validation.*;\n" +
                "@BinaryModel\n" +
                "public class Registration implements BinaryPackable {\n" +
                "    @NotBlank @Size(min = 2, max = 40)\n" +
                "    private String fullName;\n" +
                "    @Min(0) @Max(50)\n" +
                "    private int experience;\n" +
                "    private String note;\n" + // unconstrained
                "    public String getFullName() { return fullName; }\n" +
                "    public void setFullName(String v) { this.fullName = v; }\n" +
                "    public int getExperience() { return experience; }\n" +
                "    public void setExperience(int v) { this.experience = v; }\n" +
                "    public String getNote() { return note; }\n" +
                "    public void setNote(String v) { this.note = v; }\n" +
                "}\n";

        Files.writeString(srcDir.resolve("com/test/Registration.java"), modelSrc);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(
                Collections.singletonList(srcDir.resolve("com/test/Registration.java").toFile()));

        Path outDir = tempDir.resolve("out");
        Files.createDirectories(outDir);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics,
                Arrays.asList("-d", outDir.toString(), "-s", outDir.toString()),
                null, compilationUnits);
        task.setProcessors(Collections.singletonList(new RmiAnnotationProcessor()));

        boolean success = task.call();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            System.err.println(diagnostic);
        }
        assertTrue(success, "Compilation should succeed");

        String rulesContent = Files.readString(outDir.resolve("com/test/Registration_Rules.java"));
        assertTrue(rulesContent.contains("FieldRule<java.lang.String> fullName()"));
        assertTrue(rulesContent.contains("must not be blank"));
        assertTrue(rulesContent.contains("length must be between 2 and 40"));
        assertTrue(rulesContent.contains("FieldRule<java.lang.Integer> experience()"));
        assertTrue(rulesContent.contains("must be at most 50"));
        assertTrue(!rulesContent.contains("note()"), "Unconstrained fields get no rule");

        String registrarContent = Files.readString(
                outDir.resolve("com/zeroz4j/generated/BinaryPackableRegistrar.java"));
        assertTrue(registrarContent.contains("ValidationRegistry.register(\"com.test.Registration\""),
                "Registrar must register the generated validator");
    }
}
