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

import com.zeroz4j.api.BinaryModel;
import com.zeroz4j.api.RmiService;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 * Annotation Processor (APT) for zeroz4j compile-time code generation.
 *
 * <p>Scans for classes annotated with {@link BinaryModel} and interfaces annotated with {@link RmiService}.</p>
 *
 * <p>Generates high-performance binary serializers ({@code <Model>_Serializer}), client RMI stubs ({@code <Service>_Stub}),
 * and SPI registrars ({@code BinaryPackableRegistrar}) registered via {@code META-INF/services/com.zeroz4j.api.BinaryRegistrar}.</p>
 *
 * <p><b>AI Agent Execution Notes:</b></p>
 * <ul>
 *   <li><b>AOT Serializer Generation:</b> Inspects fields, getters, and setters of {@code @BinaryModel} classes. Generates static {@code write} and {@code read} methods using primitive byte buffers.</li>
 *   <li><b>RMI Stub Generation:</b> Inspects methods of {@code @RmiService} interfaces. Generates strongly typed proxy stubs delegating calls to {@link com.zeroz4j.api.RmiClientExecutor#executeCall}.</li>
 *   <li><b>SPI Registration:</b> Generates {@code com.zeroz4j.generated.BinaryPackableRegistrar} class and corresponding ServiceLoader config file in {@code META-INF/services}.</li>
 * </ul>
 */
@SupportedAnnotationTypes({
    "com.zeroz4j.api.BinaryModel",
    "com.zeroz4j.api.RmiService"
})
public class RmiAnnotationProcessor extends AbstractProcessor {

    private final List<String> binaryModels = new ArrayList<>();

    /**
     * Specifies the supported Java source version (latest supported).
     *
     * @return latest supported Java source version
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * Processes annotations during javac compilation rounds.
     *
     * @param annotations set of target annotations to process
     * @param roundEnv    round environment context
     * @return true if annotations were claimed and processed
     *
     * <p><b>Under the hood:</b> Queries {@code roundEnv.getElementsAnnotatedWith(BinaryModel.class)} and invokes {@link #generateSerializer}.
     * Queries {@code roundEnv.getElementsAnnotatedWith(RmiService.class)} and invokes {@link #generateStub}.
     * Generates SPI registrar via {@link #generateRegistrar()}.</p>
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }

        ProcessingEnvironment env = processingEnv;
        Types typeUtils = env.getTypeUtils();

        // Process BinaryModels
        Set<? extends Element> models = roundEnv.getElementsAnnotatedWith(BinaryModel.class);
        for (Element element : models) {
            if (element.getKind() == ElementKind.CLASS) {
                TypeElement typeElement = (TypeElement) element;
                String fqcn = typeElement.getQualifiedName().toString();
                binaryModels.add(fqcn);
                try {
                    generateSerializer(typeElement, typeUtils);
                } catch (IOException e) {
                    env.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to generate serializer for " + fqcn + ": " + e.getMessage(), element);
                }
            }
        }

        // Process RmiServices
        Set<? extends Element> services = roundEnv.getElementsAnnotatedWith(RmiService.class);
        for (Element element : services) {
            if (element.getKind() == ElementKind.INTERFACE) {
                TypeElement typeElement = (TypeElement) element;
                String fqcn = typeElement.getQualifiedName().toString();
                try {
                    generateStub(typeElement, typeUtils);
                } catch (IOException e) {
                    env.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to generate RMI stub for " + fqcn + ": " + e.getMessage(), element);
                }
            }
        }

        // Generate Registrar on the final processing round or when models are collected
        if (!binaryModels.isEmpty()) {
            try {
                generateRegistrar();
            } catch (IOException e) {
                env.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to generate BinaryPackableRegistrar: " + e.getMessage());
            }
            // Clear to avoid generating it again in the next round
            binaryModels.clear();
        }

        return true;
    }

    private void generateSerializer(TypeElement typeElement, Types typeUtils) throws IOException {
        String packageName = getPackageName(typeElement);
        String className = typeElement.getSimpleName().toString();
        String serializerClassName = className + "_Serializer";

        JavaFileObject builderFile = processingEnv.getFiler().createSourceFile(packageName + "." + serializerClassName);
        try (Writer writer = builderFile.openWriter()) {
            writer.write("package " + packageName + ";\n\n");
            writer.write("import java.nio.ByteBuffer;\n");
            writer.write("import com.zeroz4j.api.BinarySerializer;\n");
            writer.write("import com.zeroz4j.api.GrowableBuffer;\n");
            writer.write("import com.zeroz4j.api.ObjectMapper;\n\n");
            writer.write("// Auto-generated by zeroz4j APT \u2014 do not edit\n");
            writer.write("public class " + serializerClassName + " {\n\n");

            // Write method
            writer.write("    public static void write(" + className + " obj, GrowableBuffer buffer, ObjectMapper mapper) {\n");
            writer.write("        if (obj == null) {\n");
            writer.write("            buffer.put((byte) 0);\n");
            writer.write("            return;\n");
            writer.write("        }\n");
            writer.write("        buffer.put((byte) 1);\n");

            List<FieldInfo> fields = getFields(typeElement, typeUtils);
            for (FieldInfo field : fields) {
                String readExpr = getReadExpression(field, "obj");
                writeSerializationCode(writer, field, readExpr);
            }
            writer.write("    }\n\n");

            // Read method
            writer.write("    public static void read(" + className + " obj, ByteBuffer buffer, ObjectMapper mapper) {\n");
            writer.write("        if (buffer.get() == 0) {\n");
            writer.write("            return;\n");
            writer.write("        }\n");

            for (FieldInfo field : fields) {
                writeDeserializationCode(writer, field, "obj");
            }
            writer.write("    }\n");

            writer.write("}\n");
        }
    }

    private void writeSerializationCode(Writer writer, FieldInfo field, String readExpr) throws IOException {
        String typeStr = field.type.toString();
        if (typeStr.equals("int")) {
            writer.write("        buffer.putInt(" + readExpr + ");\n");
        } else if (typeStr.equals("long")) {
            writer.write("        buffer.putLong(" + readExpr + ");\n");
        } else if (typeStr.equals("double")) {
            writer.write("        buffer.putDouble(" + readExpr + ");\n");
        } else if (typeStr.equals("float")) {
            writer.write("        buffer.putFloat(" + readExpr + ");\n");
        } else if (typeStr.equals("boolean")) {
            writer.write("        buffer.put((byte) (" + readExpr + " ? 1 : 0));\n");
        } else if (typeStr.equals("short")) {
            writer.write("        buffer.putShort(" + readExpr + ");\n");
        } else if (typeStr.equals("byte")) {
            writer.write("        buffer.put(" + readExpr + ");\n");
        } else if (typeStr.equals("char")) {
            writer.write("        buffer.putChar(" + readExpr + ");\n");
        } else if (typeStr.equals("java.lang.String")) {
            writer.write("        BinarySerializer.writeString(buffer, " + readExpr + ");\n");
        } else if (isBinaryPackable(field.type)) {
            writer.write("        if (" + readExpr + " == null) {\n");
            writer.write("            buffer.put((byte) 0);\n");
            writer.write("        } else {\n");
            writer.write("            buffer.put((byte) 1);\n");
            writer.write("            " + readExpr + ".writeToBuffer(buffer, mapper);\n");
            writer.write("        }\n");
        } else {
            // Fallback
            writer.write("        BinarySerializer.writeValue(buffer, " + readExpr + ", mapper);\n");
        }
    }

    private void writeDeserializationCode(Writer writer, FieldInfo field, String objName) throws IOException {
        String typeStr = field.type.toString();
        String writeTarget = getWriteStatementPrefix(field, objName);
        String suffix = getWriteStatementSuffix(field);

        if (typeStr.equals("int")) {
            writer.write("        " + writeTarget + "buffer.getInt()" + suffix + ";\n");
        } else if (typeStr.equals("long")) {
            writer.write("        " + writeTarget + "buffer.getLong()" + suffix + ";\n");
        } else if (typeStr.equals("double")) {
            writer.write("        " + writeTarget + "buffer.getDouble()" + suffix + ";\n");
        } else if (typeStr.equals("float")) {
            writer.write("        " + writeTarget + "buffer.getFloat()" + suffix + ";\n");
        } else if (typeStr.equals("boolean")) {
            writer.write("        " + writeTarget + "(buffer.get() != 0)" + suffix + ";\n");
        } else if (typeStr.equals("short")) {
            writer.write("        " + writeTarget + "buffer.getShort()" + suffix + ";\n");
        } else if (typeStr.equals("byte")) {
            writer.write("        " + writeTarget + "buffer.get()" + suffix + ";\n");
        } else if (typeStr.equals("char")) {
            writer.write("        " + writeTarget + "buffer.getChar()" + suffix + ";\n");
        } else if (typeStr.equals("java.lang.String")) {
            writer.write("        " + writeTarget + "BinarySerializer.readString(buffer)" + suffix + ";\n");
        } else if (isBinaryPackable(field.type)) {
            writer.write("        if (buffer.get() != 0) {\n");
            writer.write("            " + typeStr + " nested = new " + typeStr + "();\n");
            writer.write("            nested.readFromBuffer(buffer, mapper);\n");
            writer.write("            " + writeTarget + "nested" + suffix + ";\n");
            writer.write("        } else {\n");
            writer.write("            " + writeTarget + "null" + suffix + ";\n");
            writer.write("        }\n");
        } else {
            // Fallback
            writer.write("        " + writeTarget + "(" + typeStr + ") BinarySerializer.readValue(buffer, mapper)" + suffix + ";\n");
        }
    }

    private void generateStub(TypeElement typeElement, Types typeUtils) throws IOException {
        String packageName = getPackageName(typeElement);
        String interfaceName = typeElement.getSimpleName().toString();
        String stubClassName = interfaceName + "_Stub";
        String fqcn = typeElement.getQualifiedName().toString();

        JavaFileObject builderFile = processingEnv.getFiler().createSourceFile(packageName + "." + stubClassName);
        try (Writer writer = builderFile.openWriter()) {
            writer.write("package " + packageName + ";\n\n");
            writer.write("import com.zeroz4j.api.RmiClientExecutor;\n\n");
            writer.write("// Auto-generated by zeroz4j APT \u2014 do not edit\n");
            writer.write("public class " + stubClassName + " implements " + fqcn + " {\n\n");

            // Methods
            for (Element enclosed : typeElement.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD) {
                    ExecutableElement method = (ExecutableElement) enclosed;
                    String mName = method.getSimpleName().toString();
                    TypeMirror retType = method.getReturnType();

                    // Generate signature
                    writer.write("    @Override\n");
                    writer.write("    public " + retType.toString() + " " + mName + "(");
                    List<? extends VariableElement> params = method.getParameters();
                    for (int i = 0; i < params.size(); i++) {
                        VariableElement param = params.get(i);
                        writer.write(param.asType().toString() + " " + param.getSimpleName().toString());
                        if (i < params.size() - 1) {
                            writer.write(", ");
                        }
                    }
                    writer.write(") {\n");

                    // Method body
                    if (params.isEmpty()) {
                        writer.write("        Object[] args = new Object[0];\n");
                    } else {
                        writer.write("        Object[] args = new Object[] { ");
                        for (int i = 0; i < params.size(); i++) {
                            writer.write(params.get(i).getSimpleName().toString());
                            if (i < params.size() - 1) {
                                writer.write(", ");
                            }
                        }
                        writer.write(" };\n");
                    }

                    String serviceName = fqcn;
                    if (retType.toString().equals("void")) {
                        writer.write("        RmiClientExecutor.executeCall(\"" + serviceName + "\", \"" + mName + "\", args);\n");
                    } else {
                        writer.write("        Object result = RmiClientExecutor.executeCall(\"" + serviceName + "\", \"" + mName + "\", args);\n");
                        writer.write("        return " + getCastExpression(retType, "result") + ";\n");
                    }

                    writer.write("    }\n\n");
                }
            }

            writer.write("}\n");
        }
    }

    private void generateRegistrar() throws IOException {
        String packageName = "com.zeroz4j.generated";
        String className = "BinaryPackableRegistrar";

        JavaFileObject builderFile = processingEnv.getFiler().createSourceFile(packageName + "." + className);
        try (Writer writer = builderFile.openWriter()) {
            writer.write("package " + packageName + ";\n\n");
            writer.write("import com.zeroz4j.api.BinaryRegistry;\n");
            writer.write("import com.zeroz4j.api.BinaryRegistrar;\n");
            writer.write("import com.zeroz4j.api.BinarySerializerDelegate;\n");
            writer.write("import com.zeroz4j.api.GrowableBuffer;\n");
            writer.write("import com.zeroz4j.api.ObjectMapper;\n");
            writer.write("import java.nio.ByteBuffer;\n\n");
            writer.write("// Auto-generated by zeroz4j APT \u2014 do not edit\n");
            writer.write("public class " + className + " implements BinaryRegistrar {\n");
            writer.write("    @Override\n");
            writer.write("    public void registerAll() {\n");
            for (String model : binaryModels) {
                writer.write("        BinaryRegistry.register(\"" + model + "\", " + model + "::new, new BinarySerializerDelegate<" + model + ">() {\n");
                writer.write("            @Override\n");
                writer.write("            public void write(" + model + " obj, GrowableBuffer buffer, ObjectMapper mapper) {\n");
                writer.write("                " + model + "_Serializer.write(obj, buffer, mapper);\n");
                writer.write("            }\n");
                writer.write("            @Override\n");
                writer.write("            public void read(" + model + " obj, ByteBuffer buffer, ObjectMapper mapper) {\n");
                writer.write("                " + model + "_Serializer.read(obj, buffer, mapper);\n");
                writer.write("            }\n");
                writer.write("        });\n");
            }
            writer.write("    }\n");
            writer.write("}\n");
        }
        
        try {
            javax.tools.FileObject resourceFile = processingEnv.getFiler().createResource(
                javax.tools.StandardLocation.CLASS_OUTPUT, "", "META-INF/services/com.zeroz4j.api.BinaryRegistrar");
            try (Writer writer = resourceFile.openWriter()) {
                writer.write(packageName + "." + className + "\n");
            }
        } catch (IOException e) {
            // Ignore if already created
        }
    }

    private String getPackageName(TypeElement typeElement) {
        Element owner = typeElement.getEnclosingElement();
        while (owner != null && owner.getKind() != ElementKind.PACKAGE) {
            owner = owner.getEnclosingElement();
        }
        return owner != null ? ((PackageElement) owner).getQualifiedName().toString() : "";
    }

    private List<FieldInfo> getFields(TypeElement typeElement, Types typeUtils) {
        List<FieldInfo> fields = new ArrayList<>();
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD) {
                VariableElement fieldVar = (VariableElement) enclosed;
                Set<Modifier> mods = fieldVar.getModifiers();
                if (mods.contains(Modifier.STATIC) || mods.contains(Modifier.TRANSIENT)) {
                    continue;
                }

                String fName = fieldVar.getSimpleName().toString();
                TypeMirror fType = fieldVar.asType();

                // Check getters/setters
                String getter = findGetter(typeElement, fName, fType, typeUtils);
                String setter = findSetter(typeElement, fName, fType, typeUtils);

                fields.add(new FieldInfo(fName, fType, getter, setter, mods.contains(Modifier.PRIVATE)));
            }
        }
        return fields;
    }

    private String findGetter(TypeElement typeElement, String fieldName, TypeMirror fieldType, Types typeUtils) {
        String capitalized = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                String mName = method.getSimpleName().toString();
                if (method.getParameters().isEmpty()) {
                    if (mName.equals("get" + capitalized) || mName.equals("is" + capitalized) || mName.equals(fieldName)) {
                        return mName;
                    }
                }
            }
        }
        return null;
    }

    private String findSetter(TypeElement typeElement, String fieldName, TypeMirror fieldType, Types typeUtils) {
        String capitalized = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                String mName = method.getSimpleName().toString();
                if (method.getParameters().size() == 1) {
                    if (mName.equals("set" + capitalized) || mName.equals(fieldName)) {
                        return mName;
                    }
                }
            }
        }
        return null;
    }

    private String getReadExpression(FieldInfo field, String objName) {
        if (field.getter != null) {
            return objName + "." + field.getter + "()";
        } else if (!field.isPrivate) {
            return objName + "." + field.name;
        } else {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.WARNING,
                "Field '" + field.name + "' is private with no getter. "
                + "Generated serializer may not compile. Add a public getter.");
            return objName + "." + field.name;
        }
    }

    private String getWriteStatementPrefix(FieldInfo field, String objName) {
        if (field.setter != null) {
            return objName + "." + field.setter + "(";
        } else {
            return objName + "." + field.name + " = ";
        }
    }

    private String getWriteStatementSuffix(FieldInfo field) {
        if (field.setter != null) {
            return ")";
        } else {
            return "";
        }
    }

    private boolean isBinaryPackable(TypeMirror type) {
        if (type instanceof DeclaredType) {
            TypeElement typeElem = (TypeElement) ((DeclaredType) type).asElement();
            for (TypeMirror iface : typeElem.getInterfaces()) {
                if (iface.toString().equals("com.zeroz4j.api.BinaryPackable")) {
                    return true;
                }
            }
            TypeMirror superclass = typeElem.getSuperclass();
            if (superclass != null && !superclass.toString().equals("java.lang.Object")) {
                return isBinaryPackable(superclass);
            }
        }
        return false;
    }

    private String getCastExpression(TypeMirror type, String varName) {
        String typeStr = type.toString();
        if (typeStr.equals("int")) {
            return varName + " != null ? (Integer) " + varName + " : 0";
        } else if (typeStr.equals("long")) {
            return varName + " != null ? (Long) " + varName + " : 0L";
        } else if (typeStr.equals("double")) {
            return varName + " != null ? (Double) " + varName + " : 0.0";
        } else if (typeStr.equals("float")) {
            return varName + " != null ? (Float) " + varName + " : 0.0f";
        } else if (typeStr.equals("boolean")) {
            return varName + " != null ? (Boolean) " + varName + " : false";
        } else if (typeStr.equals("short")) {
            return varName + " != null ? (Short) " + varName + " : (short) 0";
        } else if (typeStr.equals("byte")) {
            return varName + " != null ? (Byte) " + varName + " : (byte) 0";
        } else if (typeStr.equals("char")) {
            return varName + " != null ? (Character) " + varName + " : (char) 0";
        } else {
            return "(" + typeStr + ") " + varName;
        }
    }

    private static class FieldInfo {
        final String name;
        final TypeMirror type;
        final String getter;
        final String setter;
        final boolean isPrivate;

        FieldInfo(String name, TypeMirror type, String getter, String setter, boolean isPrivate) {
            this.name = name;
            this.type = type;
            this.getter = getter;
            this.setter = setter;
            this.isPrivate = isPrivate;
        }
    }
}
