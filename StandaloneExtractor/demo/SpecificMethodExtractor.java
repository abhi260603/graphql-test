package com.example.demo;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
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

    // Standard Java/Framework packages to ignore during call graph extraction
    private static final Set<String> EXCLUDED_PACKAGES = Set.of(
        "java.", "javax.", "jakarta.", "org.springframework.", "org.apache.", "com.sun.", "lombok."
    );

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java -jar java-ast-extractor-1.0.0.jar <repo-path> <ClassName> <MethodName>");
            System.out.println("Example: java -jar java-ast-extractor-1.0.0.jar /Users/abhi/spring-boot/demo MovieController movieById");
            System.exit(1);
        }

        String projectPath = args[0];
        String targetClassName = args[1];
        String targetMethodName = args[2];

        // 1. Initialize Spoon AST Parser
        Launcher launcher = new Launcher();
        
        // --- DUPLICATE HANDLING: Scan and add only valid source directories ---
        List<File> sourceRoots = findModuleSourceRoots(projectPath);
        System.out.println("Registering source roots:");
        for (File srcDir : sourceRoots) {
            System.out.println("  - " + srcDir.getAbsolutePath());
            launcher.addInputResource(srcDir.getAbsolutePath());
        }

        // --- DUPLICATE HANDLING: Environment Settings ---
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        launcher.getEnvironment().setAutoImports(false);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(17);

        System.out.println("\nParsing codebase at: " + projectPath);
        launcher.buildModel();
        CtModel model = launcher.getModel();

        // 2. Locate Target Class (Handles potential duplicates safely)
        List<CtClass<?>> candidateClasses = model.getElements(new TypeFilter<CtClass<?>>(CtClass.class))
                .stream()
                .filter(c -> c.getSimpleName().equalsIgnoreCase(targetClassName))
                .toList();

        if (candidateClasses.isEmpty()) {
            throw new RuntimeException("Class not found: " + targetClassName);
        }

        // --- DUPLICATE HANDLING: Target Resolution ---
        // Prioritize candidate classes with valid file sources inside 'src/main/java'
        CtClass<?> targetClass = candidateClasses.stream()
                .filter(c -> c.getPosition() != null 
                          && c.getPosition().getFile() != null 
                          && c.getPosition().getFile().getAbsolutePath().contains("src/main/java"))
                .findFirst()
                .orElse(candidateClasses.get(0));

        if (candidateClasses.size() > 1) {
            String path = (targetClass.getPosition() != null && targetClass.getPosition().getFile() != null)
                    ? targetClass.getPosition().getFile().getPath()
                    : "Unknown Path";

            System.out.println("⚠️ Warning: Found " + candidateClasses.size() + " instances of '" + targetClassName 
                    + "'. Selected: " + targetClass.getQualifiedName() 
                    + " (" + path + ")");
        }

        // 3. Locate Target Method (Handles overloaded methods gracefully)
        List<CtMethod<?>> matchingMethods = targetClass.getMethodsByName(targetMethodName);
        if (matchingMethods.isEmpty()) {
            throw new RuntimeException("Method '" + targetMethodName + "' not found in class " + targetClassName);
        }
        CtExecutable<?> targetMethod = matchingMethods.get(0);

        System.out.println("Tracing call graph starting from: " + targetClass.getQualifiedName() + "#" + targetMethod.getSimpleName());

        // 4. Trace Dependencies
        Set<CtExecutable<?>> visitedMethods = new HashSet<>();
        Set<CtExecutable<?>> capturedMethods = new HashSet<>();
        Set<CtType<?>> capturedTypes = new HashSet<>();

        traceMethod(targetMethod, visitedMethods, capturedMethods, capturedTypes);

        // 5. Build Output Payload
        StringBuilder payload = new StringBuilder();
        payload.append("// ENTRY POINT: ").append(targetClass.getQualifiedName()).append("#").append(targetMethod.getSimpleName()).append("\n\n");

        // Print ONLY the targeted endpoint method body (excludes sibling endpoints)
        payload.append("// Entry Method Signature & Body:\n");
        payload.append(targetMethod.toString()).append("\n\n-----------------------------------\n\n");

        // Print Dependent Classes, Interfaces (DAOs/Repositories), and Entities
        capturedTypes.forEach(type -> {
            if (type != null && !type.equals(targetClass)) { // Omit the full controller class
                payload.append("// Dependent Type: ").append(type.getQualifiedName()).append("\n");
                payload.append(type.toString()).append("\n\n-----------------------------------\n\n");
            }
        });

        // 6. Write Payload to Disk
        String outputFile = targetClassName + "_" + targetMethodName + "_payload.txt";
        Files.writeString(Path.of(outputFile), payload.toString());
        System.out.println("Done! Precision context payload written to: " + outputFile);
    }

    /**
     * Filters repository directories to scan ONLY src/main/java folders,
     * ignoring build/target outputs, tests, and backup directories.
     */
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

        // Fallback to project root if no src/main/java folders were found
        if (sourceRoots.isEmpty()) {
            sourceRoots.add(new File(rootPath));
        }

        return sourceRoots;
    }

    private static void traceMethod(
            CtExecutable<?> method,
            Set<CtExecutable<?>> visitedMethods,
            Set<CtExecutable<?>> capturedMethods,
            Set<CtType<?>> capturedTypes) {

        if (method == null || !visitedMethods.add(method)) {
            return; // Prevent infinite loops caused by recursion or circular calls
        }

        capturedMethods.add(method);

        // A. Capture Return Type
        if (method.getType() != null) {
            addTypeIfApplicationClass(method.getType(), capturedTypes);
        }

        // B. Capture Method Parameter Types
        method.getParameters().forEach(param -> {
            if (param.getType() != null) {
                addTypeIfApplicationClass(param.getType(), capturedTypes);
            }
        });

        // C. Capture Fields Accessed Inside Method (Captures injected DAOs / Repositories)
        List<CtFieldAccess<?>> fieldAccesses = method.getElements(new TypeFilter<>(CtFieldAccess.class));
        for (CtFieldAccess<?> fieldAccess : fieldAccesses) {
            if (fieldAccess.getVariable() != null && fieldAccess.getVariable().getType() != null) {
                addTypeIfApplicationClass(fieldAccess.getVariable().getType(), capturedTypes);
            }
        }

        // D. Trace Invoked Methods Transitively
        List<CtInvocation<?>> invocations = method.getElements(new TypeFilter<>(CtInvocation.class));
        for (CtInvocation<?> invocation : invocations) {
            CtExecutable<?> target = invocation.getExecutable().getDeclaration();
            if (target != null) {
                CtType<?> targetType = target.getParent(CtType.class);
                if (targetType != null && isApplicationClass(targetType.getQualifiedName())) {
                    capturedTypes.add(targetType);
                    // Recurse down the call graph
                    traceMethod(target, visitedMethods, capturedMethods, capturedTypes);
                }
            }
        }
    }

    private static void addTypeIfApplicationClass(CtTypeReference<?> typeRef, Set<CtType<?>> capturedTypes) {
        if (typeRef == null) return;
        
        String qualifiedName = typeRef.getQualifiedName();
        if (isApplicationClass(qualifiedName)) {
            CtType<?> typeDeclaration = typeRef.getTypeDeclaration();
            if (typeDeclaration != null) {
                capturedTypes.add(typeDeclaration);
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