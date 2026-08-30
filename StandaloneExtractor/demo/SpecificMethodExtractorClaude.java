package com.example.demo;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class SpecificMethodExtractorClaude {

    // Standard Java/Framework packages to ignore during call graph extraction.
    // Add any of your own shared/common internal packages here too if they're
    // effectively "framework" from the endpoint's point of view.
    private static final Set<String> EXCLUDED_PACKAGES = Set.of(
        "java.", "javax.", "jakarta.", "org.springframework.", "org.apache.", "com.sun.", "lombok.",
        "org.hibernate.", "org.slf4j.", "com.fasterxml.jackson.", "org.junit.", "org.mockito."
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
        launcher.addInputResource(projectPath);

        // NoClasspath mode allows Spoon to analyze code without full compiled binaries.
        // Trade-off: a handful of calls that only resolve via an external dependency's
        // type information may fail to bind to a declaration and get skipped. This is
        // rare for calls that stay within your own module, which is the common case here.
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(17);

        System.out.println("Parsing codebase at: " + projectPath);
        launcher.buildModel();
        CtModel model = launcher.getModel();

        // 2. Locate Target Class — warn instead of silently guessing if the name is ambiguous
        List<CtClass<?>> matchingClasses = model.getElements(new TypeFilter<CtClass<?>>(CtClass.class))
                .stream()
                .filter(c -> c.getSimpleName().equalsIgnoreCase(targetClassName))
                .collect(Collectors.toList());

        if (matchingClasses.isEmpty()) {
            throw new RuntimeException("Class not found: " + targetClassName);
        }
        if (matchingClasses.size() > 1) {
            System.out.println("Warning: " + matchingClasses.size() + " classes named '" + targetClassName + "' found; using the first one:");
            matchingClasses.forEach(c -> System.out.println("  - " + c.getQualifiedName()));
        }
        CtClass<?> targetClass = matchingClasses.get(0);

        // 3. Locate Target Method — warn on overloads instead of silently picking one
        List<CtMethod<?>> matchingMethods = targetClass.getMethodsByName(targetMethodName);
        if (matchingMethods.isEmpty()) {
            throw new RuntimeException("Method '" + targetMethodName + "' not found in class " + targetClassName);
        }
        if (matchingMethods.size() > 1) {
            System.out.println("Warning: '" + targetMethodName + "' is overloaded (" + matchingMethods.size() + " variants); using the first one:");
            matchingMethods.forEach(m -> System.out.println("  - " + m.getSignature()));
        }
        CtExecutable<?> targetMethod = matchingMethods.get(0);

        System.out.println("Tracing call graph starting from: " + targetClass.getQualifiedName() + "#" + targetMethod.getSimpleName());

        // 4. Trace Dependencies
        Set<CtExecutable<?>> visitedMethods = new LinkedHashSet<>();
        Set<CtExecutable<?>> capturedMethods = new LinkedHashSet<>();
        Set<CtType<?>> capturedTypes = new LinkedHashSet<>();

        traceMethod(targetMethod, visitedMethods, capturedMethods, capturedTypes);

        // 5. Build Output Payload
        StringBuilder payload = new StringBuilder();
        payload.append("// ENTRY POINT: ").append(targetClass.getQualifiedName()).append("#").append(targetMethod.getSimpleName()).append("\n\n");

        // Call-graph summary first, so a reader (or an LLM) sees the shape of the trace
        // before wading through the full source of every dependent type.
        payload.append("// CALL GRAPH (").append(capturedMethods.size()).append(" methods traced):\n");
        for (CtExecutable<?> m : capturedMethods) {
            payload.append("//   ").append(describeMethod(m)).append("\n");
        }
        payload.append("\n-----------------------------------\n\n");

        // Print ONLY the targeted endpoint method body (excludes sibling endpoints)
        payload.append("// Entry Method Signature & Body:\n");
        payload.append(targetMethod.toString()).append("\n\n-----------------------------------\n\n");

        // Print Dependent Classes, Interfaces (DAOs/Repositories), and Entities
        for (CtType<?> type : capturedTypes) {
            if (type != null && !type.equals(targetClass)) { // Omit the full controller class
                payload.append("// Dependent Type: ").append(type.getQualifiedName()).append("\n");
                payload.append(type.toString()).append("\n\n-----------------------------------\n\n");
            }
        }

        // 6. Write Payload to Disk
        String outputFile = targetClassName + "_" + targetMethodName + "_payload.txt";
        Path outputPath = Path.of(outputFile);
        Files.writeString(outputPath, payload.toString());
        System.out.println("Done! Precision context payload written to: " + outputPath.toAbsolutePath());
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

        // A. Capture Return Type — including generic type arguments (List<OrderDto>, etc.)
        if (method.getType() != null) {
            addTypeIfApplicationClass(method.getType(), capturedTypes);
        }

        // B. Capture Method Parameter Types — including generics (Map<String, Customer>, etc.)
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

        // E. Trace Constructor Calls (`new OrderDto(...)`, custom exceptions, etc.)
        // Many DTOs/entities rely on a Lombok-generated constructor (@AllArgsConstructor,
        // @Builder, ...) that never exists as an explicit AST node, so getDeclaration()
        // returns null for them. In that case, fall back to capturing the constructed
        // type straight from the `new` expression so it isn't silently dropped.
        List<CtConstructorCall<?>> constructorCalls = method.getElements(new TypeFilter<>(CtConstructorCall.class));
        for (CtConstructorCall<?> constructorCall : constructorCalls) {
            CtExecutable<?> target = constructorCall.getExecutable().getDeclaration();
            if (target != null) {
                CtType<?> targetType = target.getParent(CtType.class);
                if (targetType != null && isApplicationClass(targetType.getQualifiedName())) {
                    capturedTypes.add(targetType);
                    traceMethod(target, visitedMethods, capturedMethods, capturedTypes);
                }
            } else {
                addTypeIfApplicationClass(constructorCall.getType(), capturedTypes);
            }
        }

        // F. Trace Method References (`.stream().map(this::toDto)`, `Foo::new`, etc.)
        // NOTE: left as raw types below since the exact <T, E> bounds on
        // CtExecutableReferenceExpression weren't verified against a live Spoon build in
        // this environment — tighten them if your IDE flags the unchecked warning.
        List<CtExecutableReferenceExpression> methodRefs =
                method.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class));
        for (CtExecutableReferenceExpression methodRef : methodRefs) {
            CtExecutable<?> target = (CtExecutable<?>) methodRef.getExecutable().getDeclaration();
            if (target != null) {
                CtType<?> targetType = target.getParent(CtType.class);
                if (targetType != null && isApplicationClass(targetType.getQualifiedName())) {
                    capturedTypes.add(targetType);
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

        // Recurse into generic type arguments so wrapper types don't hide the real
        // dependency, e.g. List<OrderDto> -> OrderDto, Map<String, Customer> -> Customer.
        for (CtTypeReference<?> typeArgument : typeRef.getActualTypeArguments()) {
            addTypeIfApplicationClass(typeArgument, capturedTypes);
        }
    }

    private static boolean isApplicationClass(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return false;
        }
        return EXCLUDED_PACKAGES.stream().noneMatch(qualifiedName::startsWith);
    }

    private static String describeMethod(CtExecutable<?> method) {
        CtType<?> declaringType = method.getParent(CtType.class);
        String typeName = declaringType != null ? declaringType.getQualifiedName() : "?";
        return typeName + "#" + method.getSignature();
    }
}