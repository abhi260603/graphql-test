package com.example.demo;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeInformation;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class SpecificMethodExtractor {

    private static final Set<String> EXCLUDED_PACKAGES = Set.of(
        "java.", "javax.", "jakarta.", "org.springframework.", "org.apache.", "com.sun.", "lombok."
    );

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java -jar java-ast-extractor-1.0.0.jar <repo-path> <ClassName> <MethodName>");
            System.exit(1);
        }

        String projectPath = args[0];
        String targetClassName = args[1];
        String targetMethodName = args[2];

        Launcher launcher = new Launcher();
        
        List<File> sourceRoots = findModuleSourceRoots(projectPath);
        System.out.println("Registering source roots:");
        for (File srcDir : sourceRoots) {
            System.out.println("  - " + srcDir.getAbsolutePath());
            launcher.addInputResource(srcDir.getAbsolutePath());
        }

        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        launcher.getEnvironment().setAutoImports(false);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(17);

        System.out.println("\nParsing codebase at: " + projectPath);
        launcher.buildModel();
        CtModel model = launcher.getModel();

        List<CtClass<?>> candidateClasses = model.getElements(new TypeFilter<CtClass<?>>(CtClass.class))
                .stream()
                .filter(c -> c.getSimpleName().equalsIgnoreCase(targetClassName))
                .toList();

        if (candidateClasses.isEmpty()) {
            throw new RuntimeException("Class not found: " + targetClassName);
        }

        CtClass<?> targetClass = candidateClasses.stream()
                .filter(c -> c.getPosition() != null 
                          && c.getPosition().getFile() != null 
                          && c.getPosition().getFile().getAbsolutePath().contains("src/main/java"))
                .findFirst()
                .orElse(candidateClasses.get(0));

        List<CtMethod<?>> matchingMethods = targetClass.getMethodsByName(targetMethodName);
        if (matchingMethods.isEmpty()) {
            throw new RuntimeException("Method '" + targetMethodName + "' not found in class " + targetClassName);
        }
        CtExecutable<?> targetMethod = matchingMethods.get(0);

        System.out.println("Tracing call graph starting from: " + targetClass.getQualifiedName() + "#" + targetMethod.getSimpleName());

        Set<CtExecutable<?>> visitedMethods = new HashSet<>();
        Set<CtExecutable<?>> capturedMethods = new LinkedHashSet<>();
        Set<CtType<?>> capturedDataTypes = new LinkedHashSet<>();

        // Start tracing from the target method
        traceMethod(model, targetMethod, visitedMethods, capturedMethods, capturedDataTypes);

        // Build Payload
        StringBuilder payload = new StringBuilder();
        payload.append("// ENTRY POINT: ").append(targetClass.getQualifiedName()).append("#").append(targetMethod.getSimpleName()).append("\n\n");

        payload.append("// --- TRACED EXECUTABLE METHODS ---\n\n");
        for (CtExecutable<?> method : capturedMethods) {
            CtType<?> declaringType = method.getParent(CtType.class);
            String className = (declaringType != null) ? declaringType.getQualifiedName() : "Unknown";
            
            payload.append("// Method: ").append(className).append("#").append(method.getSimpleName()).append("\n");
            payload.append(method.toString()).append("\n\n-----------------------------------\n\n");
        }

        if (!capturedDataTypes.isEmpty()) {
            payload.append("// --- DEPENDENT DATA TYPES / ENTITIES ---\n\n");
            for (CtType<?> type : capturedDataTypes) {
                if (!type.equals(targetClass)) {
                    payload.append("// Type Definition: ").append(type.getQualifiedName()).append("\n");
                    payload.append(type.toString()).append("\n\n-----------------------------------\n\n");
                }
            }
        }

        String outputFile = targetClassName + "_" + targetMethodName + "_payload.txt";
        Files.writeString(Path.of(outputFile), payload.toString());
        System.out.println("Done! Precision context payload written to: " + outputFile);
    }

    private static void traceMethod(
            CtModel model,
            CtExecutable<?> method,
            Set<CtExecutable<?>> visitedMethods,
            Set<CtExecutable<?>> capturedMethods,
            Set<CtType<?>> capturedDataTypes) {

        if (method == null || !visitedMethods.add(method)) {
            return;
        }

        // Capture only the specific method hit during execution
        capturedMethods.add(method);

        // Capture Parameter and Return Data Types
        if (method.getType() != null) {
            addTypeIfApplicationClass(method.getType(), capturedDataTypes);
        }
        method.getParameters().forEach(param -> {
            if (param.getType() != null) {
                addTypeIfApplicationClass(param.getType(), capturedDataTypes);
            }
        });

        // Capture Accessed Fields
        List<CtFieldAccess<?>> fieldAccesses = method.getElements(new TypeFilter<>(CtFieldAccess.class));
        for (CtFieldAccess<?> fieldAccess : fieldAccesses) {
            if (fieldAccess.getVariable() != null && fieldAccess.getVariable().getType() != null) {
                addTypeIfApplicationClass(fieldAccess.getVariable().getType(), capturedDataTypes);
            }
        }

        // Trace Method Invocations
        List<CtInvocation<?>> invocations = method.getElements(new TypeFilter<>(CtInvocation.class));
        for (CtInvocation<?> invocation : invocations) {
            CtExecutable<?> target = invocation.getExecutable().getDeclaration();
            if (target == null) continue;

            CtType<?> targetType = target.getParent(CtType.class);
            if (targetType == null || !isApplicationClass(targetType.getQualifiedName())) {
                continue;
            }

            // Check if the target is an Interface or Abstract Class
            if (targetType.isInterface() || targetType.hasModifier(spoon.reflect.declaration.ModifierKind.ABSTRACT)) {
                // Find concrete implementing classes in the AST model
                List<CtClass<?>> implementations = findImplementations(model, targetType);

                for (CtClass<?> implClass : implementations) {
                    // Find the matching method in the implementing class
                    CtMethod<?> implMethod = implClass.getMethod(
                            target.getSimpleName(), 
                            target.getParameters().stream().map(p -> p.getType()).toArray(CtTypeReference[]::new)
                    );

                    if (implMethod != null) {
                        traceMethod(model, implMethod, visitedMethods, capturedMethods, capturedDataTypes);
                    }
                }
            } else {
                // Concrete method call
                traceMethod(model, target, visitedMethods, capturedMethods, capturedDataTypes);
            }
        }
    }

    /**
     * Finds concrete classes that implement an interface or extend an abstract class.
     */
    private static List<CtClass<?>> findImplementations(CtModel model, CtType<?> interfaceOrAbstractClass) {
        CtTypeReference<?> targetRef = interfaceOrAbstractClass.getReference();

        return model.getElements(new TypeFilter<CtClass<?>>(CtClass.class))
                .stream()
                .filter(c -> !c.isAbstract() && !c.isInterface())
                .filter(c -> {
                    // Check direct interface implementation
                    boolean implementsInterface = c.getSuperInterfaces().stream()
                            .anyMatch(i -> i.getQualifiedName().equals(targetRef.getQualifiedName()));
                    
                    // Check superclass extension
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

    private static void addTypeIfApplicationClass(CtTypeReference<?> typeRef, Set<CtType<?>> capturedDataTypes) {
        if (typeRef == null) return;
        
        String qualifiedName = typeRef.getQualifiedName();
        if (isApplicationClass(qualifiedName)) {
            CtType<?> typeDeclaration = typeRef.getTypeDeclaration();
            if (typeDeclaration != null && !typeDeclaration.isInterface()) {
                capturedDataTypes.add(typeDeclaration);
            }
        }
    }

    private static boolean isApplicationClass(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return false;
        }
        return EXCLUDED_PACKAGES.stream().noneMatch(qualifiedName::startsWith);
    }
}