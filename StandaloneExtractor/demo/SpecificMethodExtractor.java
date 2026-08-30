package com.example.demo;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class SpecificMethodExtractor {

    private static class ExtractionContext {
        final Set<CtExecutable<?>> visitedMethods = new HashSet<>();
        final Set<CtExecutable<?>> capturedMethods = new LinkedHashSet<>();
        final Set<String> blackBoxCalls = new LinkedHashSet<>();
        final Set<CtType<?>> capturedDataTypes = new LinkedHashSet<>();
    }

    private static final Set<String> ALLOWED_PACKAGE_PREFIXES = Set.of("com.example.demo");

    private static final Set<String> BLACK_BOX_CLASSES = Set.of("GenericDaoImpl");

    private static CtModel generateModel(String projectPath) {
        Launcher launcher = new Launcher();
        List<File> sourceRoots = findModuleSourceRoots(projectPath);
        for (File srcDir : sourceRoots) {
            launcher.addInputResource(srcDir.getAbsolutePath());
        }
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        launcher.getEnvironment().setAutoImports(false);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.buildModel();
        return launcher.getModel();
    }

    private static CtClass<?> getTargetClass(CtModel model, String targetClassName) {
        List<CtClass<?>> candidateClasses = model.getElements(new TypeFilter<CtClass<?>>(CtClass.class))
                .stream().filter(c -> c.getSimpleName().equalsIgnoreCase(targetClassName)).toList();
        if (candidateClasses.isEmpty()) {
            throw new RuntimeException("Class not found: " + targetClassName);
        }
        CtClass<?> targetClass = candidateClasses.get(0);
        if (candidateClasses.size() > 1) {
            System.out.println("⚠️ Warning: Found " + candidateClasses.size() + " classes matching '" + targetClassName + "':");
            for (CtClass<?> clazz : candidateClasses) {
                System.out.println("  - " + clazz.getQualifiedName());
            }
            System.out.println("Defaulting to: " + targetClass.getQualifiedName());
        }
        return targetClass;
    }

    private static CtMethod<?> getTargetMethod(CtClass<?> targetClass, String targetMethodName, String targetClassName) {
        List<CtMethod<?>> matchingMethods = targetClass.getMethodsByName(targetMethodName);
        if (matchingMethods.isEmpty()) {
            throw new RuntimeException("Method '" + targetMethodName + "' not found in class " + targetClassName);
        }
        return matchingMethods.get(0);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java -jar java-ast-extractor-1.0.0.jar <repo-path> <ClassName> <MethodName>");
            System.exit(1);
        }
        String projectPath = args[0];
        String targetClassName = args[1];
        String targetMethodName = args[2];
        CtModel model = generateModel(projectPath);
        CtClass<?> targetClass = getTargetClass(model, targetClassName);
        CtExecutable<?> targetMethod = getTargetMethod(targetClass, targetMethodName, targetClassName);
        ExtractionContext extractionContext = new ExtractionContext();
        traceMethod(model, targetMethod, extractionContext);
        StringBuilder payload = buildPayload(targetClass, targetMethod, extractionContext);
        String outputFile = targetClassName + "_" + targetMethodName + "_payload.txt";
        Files.writeString(Path.of(outputFile), payload.toString());
        System.out.println("Done! Precision context payload written to: " + outputFile);
    }

    private static StringBuilder buildPayload(CtClass<?> targetClass, CtExecutable<?> targetMethod, ExtractionContext extractionContext) {
        StringBuilder payload = new StringBuilder();
        appendEntryPoint(payload, targetClass, targetMethod);
        appendExecutableMethods(payload, extractionContext.capturedMethods);
        appendBlackboxCalls(payload, extractionContext.blackBoxCalls);
        appendDependentDataTypes(payload, extractionContext.capturedDataTypes, extractionContext.capturedMethods, targetClass);
        return payload;
    }

    private static void appendEntryPoint(StringBuilder payload, CtClass<?> targetClass, CtExecutable<?> targetMethod) {
        payload.append("// ENTRY POINT: ").append(targetClass.getQualifiedName()).append("#").append(targetMethod.getSimpleName()).append("\n\n");
    }

    private static void appendBlackboxCalls(StringBuilder payload, Set<String> blackBoxCalls) {
        if (!blackBoxCalls.isEmpty()) {
            payload.append("// --- BLACK-BOX DAO / STORED PROCEDURE CALLS ---\n\n");
            for (String callSummary : blackBoxCalls) {
                payload.append(callSummary).append("\n-----------------------------------\n\n");
            }
        }
    }

    private static void appendExecutableMethods(StringBuilder payload, Set<CtExecutable<?>> capturedMethods) {
        payload.append("// --- TRACED EXECUTABLE METHODS ---\n\n");
        for (CtExecutable<?> method : capturedMethods) {
            CtType<?> declaringType = method.getParent(CtType.class);
            String className = (declaringType != null) ? declaringType.getQualifiedName() : "Unknown";
            payload.append("// Method: ").append(className).append("#").append(method.getSimpleName()).append("\n");
            payload.append(method).append("\n\n-----------------------------------\n\n");
        }
    }

    private static void appendDependentDataTypes(StringBuilder payload ,Set<CtType<?>> capturedDataTypes, Set<CtExecutable<?>> capturedMethods, CtClass<?> targetClass) {
        if (!capturedDataTypes.isEmpty()) {
            payload.append("// --- DEPENDENT DATA TYPES / ENTITIES ---\n\n");
            for (CtType<?> type : capturedDataTypes) {
                boolean isAlreadyTracedService = capturedMethods.stream()
                        .anyMatch(m -> type.equals(m.getParent(CtType.class)));
                if (!type.equals(targetClass) && !isAlreadyTracedService) {
                    payload.append("// Type Definition: ").append(type.getQualifiedName()).append("\n");
                    payload.append(type).append("\n\n-----------------------------------\n\n");
                }
            }
        }
    }

    private static void addTypeIfEntityOrDto(CtTypeReference<?> typeRef, Set<CtType<?>> capturedDataTypes) {
        if (typeRef == null) return;

        String qualifiedName = typeRef.getQualifiedName();
        if (isApplicationClass(qualifiedName)) {
            CtType<?> typeDecl = typeRef.getTypeDeclaration();
            if (typeDecl != null && isDataModelType(typeDecl)) {
                capturedDataTypes.add(typeDecl);
            }
        }
    }

    private static boolean isDataModelType(CtType<?> type) {
        if (type.isInterface() || type.getSimpleName().endsWith("Repository") || type.getSimpleName().endsWith("Service") || type.getSimpleName().endsWith("Controller")) {
            return false;
        }
        String pkg = type.getQualifiedName().toLowerCase();
        return pkg.contains(".entity.") ||
                pkg.contains(".dto.") ||
                pkg.contains(".model.") ||
                pkg.contains(".domain.") ||
                type.getAnnotations().stream().anyMatch(a -> {
                    String name = a.getAnnotationType().getSimpleName();
                    return name.equals("Entity") || name.equals("Table") || name.equals("Data") || name.equals("Value");
                });
    }

    private static String extractProcedureContract(CtInvocation<?> invocation, CtExecutable<?> target) {
        StringBuilder sb = new StringBuilder();
        CtType<?> declaringType = target.getParent(CtType.class);
        String className = (declaringType != null) ? declaringType.getSimpleName() : "GenericDao";

        sb.append("// BLACK-BOX CALL: ").append(className).append("#").append(target.getSimpleName()).append("\n");
        List<?> args = invocation.getArguments();
        if (!args.isEmpty()) {
            sb.append("// Passed Arguments / Procedure Contract:\n");
            for (int i = 0; i < args.size(); i++) {
                sb.append("//   Arg [").append(i + 1).append("]: ").append(args.get(i).toString()).append("\n");
            }
        }
        return sb.toString();
    }

    private static boolean isBlackBoxClass(CtType<?> type) {
        return type != null && BLACK_BOX_CLASSES.contains(type.getSimpleName());
    }

    private static List<CtClass<?>> findImplementations(CtModel model, CtType<?> interfaceOrAbstractClass) {
        CtTypeReference<?> targetRef = interfaceOrAbstractClass.getReference();
        return model.getElements(new TypeFilter<CtClass<?>>(CtClass.class))
                .stream()
                .filter(c -> !c.isAbstract() && !c.isInterface())
                .filter(c -> {
                    boolean implementsInterface = c.getSuperInterfaces().stream()
                            .anyMatch(i -> i.getQualifiedName().equals(targetRef.getQualifiedName()));
                    boolean extendsSuperclass = c.getSuperclass() != null
                            && c.getSuperclass().getQualifiedName().equals(targetRef.getQualifiedName());
                    return implementsInterface || extendsSuperclass;
                })
                .toList();
    }

    private static List<File> findModuleSourceRoots(String rootPath) {
        List<File> sourceRoots = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get(rootPath))) {
            paths.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Paths.get("src", "main", "java")))
                    .filter(p -> {
                        String lower = p.toString().toLowerCase();
                        return !lower.contains("/target/") &&
                                !lower.contains("/build/") &&
                                !lower.contains("/bin/") &&
                                !lower.contains("/out/") &&
                                !lower.contains("_backup") &&
                                !lower.contains("_old");
                    })
                    .forEach(p -> sourceRoots.add(p.toFile()));
        } catch (Exception e) {
            System.err.println("Warning: Failed to walk project paths. Falling back to root directory.");
        }
        if (sourceRoots.isEmpty()) {
            sourceRoots.add(new File(rootPath));
        }
        return sourceRoots;
    }

    private static boolean isApplicationClass(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) return false;
        return ALLOWED_PACKAGE_PREFIXES.stream().anyMatch(qualifiedName::startsWith);
    }

    private static void captureDataTypes(Set<CtType<?>> capturedDataTypes, CtExecutable<?> method) {
        // Capture Parameter and Return Types (Check for domain model / entity types)
        if (method.getType() != null) {
            addTypeIfEntityOrDto(method.getType(), capturedDataTypes);
        }
        method.getParameters().forEach(param -> {
            if (param.getType() != null) {
                addTypeIfEntityOrDto(param.getType(), capturedDataTypes);
            }
        });
    }

    private static void traceMethod(CtModel model, CtExecutable<?> method, ExtractionContext extractionContext) {
        if (method == null || !extractionContext.visitedMethods.add(method)) {
            return;
        }
        extractionContext.capturedMethods.add(method);
        captureDataTypes(extractionContext.capturedDataTypes, method);
        List<CtInvocation<?>> invocations = method.getElements(new TypeFilter<>(CtInvocation.class));
        for (CtInvocation<?> invocation : invocations) {
            CtExecutable<?> target = invocation.getExecutable().getDeclaration();
            if (target == null) continue;
            CtType<?> targetType = target.getParent(CtType.class);
            if (targetType == null || !isApplicationClass(targetType.getQualifiedName())) {
                continue;
            }
            if (isBlackBoxClass(targetType)) {
                extractionContext.blackBoxCalls.add(extractProcedureContract(invocation, target));
                continue;
            }
            processInvocations(targetType, target, invocation, model, extractionContext);
        }
    }

    private static void processInvocations(CtType<?> targetType, CtExecutable<?> target, CtInvocation<?> invocation, CtModel model, ExtractionContext extractionContext) {
        if (targetType.isInterface() || targetType.hasModifier(spoon.reflect.declaration.ModifierKind.ABSTRACT)) {
            List<CtClass<?>> implementations = findImplementations(model, targetType);
            for (CtClass<?> implClass : implementations) {
                if (isBlackBoxClass(implClass)) {
                    extractionContext.blackBoxCalls.add(extractProcedureContract(invocation, target));
                    continue;
                }
                CtTypeReference<?>[] paramTypes = target.getParameters().stream()
                        .map(CtParameter::getType)
                        .toArray(CtTypeReference[]::new);

                CtMethod<?> implMethod = implClass.getMethod(target.getSimpleName(), paramTypes);
                if (implMethod != null) {
                    traceMethod(model, implMethod, extractionContext);
                }
            }
        } else {
            traceMethod(model, target, extractionContext);
        }
    }
}