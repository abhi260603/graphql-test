package com.example.demo;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class JavaParser {

    private static class ExtractionContext {
        final Set<MethodDeclaration> visitedMethods = new HashSet<>();
        final Set<MethodDeclaration> capturedMethods = new LinkedHashSet<>();
        final Set<String> blackBoxCalls = new LinkedHashSet<>();
        final Set<ClassOrInterfaceDeclaration> capturedDataTypes = new LinkedHashSet<>();
        final Map<String, Set<String>> capturedConstants = new LinkedHashMap<>();
        final Set<ClassOrInterfaceDeclaration> capturedInterfaces = new LinkedHashSet<>();
        final Map<String, Set<String>> interfaceInvokedMethods = new LinkedHashMap<>();
    }

    private static final Set<String> ALLOWED_PACKAGE_PREFIXES = Set.of("com.example.demo");
    private static final Set<String> BLACK_BOX_CLASSES = Set.of("GenericDaoImpl");

    private static final List<CompilationUnit> allCompilationUnits = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java -jar java-ast-extractor-1.0.0.jar <repo-path> <ClassName> <MethodName>");
            System.exit(1);
        }
        String projectPath = args[0];
        String targetClassName = args[1];
        String targetMethodName = args[2];

        // 1. Setup Symbol Solver
        setupSymbolSolver(projectPath);

        // 2. Resolve Target Entry Point
        ClassOrInterfaceDeclaration targetClass = getTargetClass(targetClassName);
        MethodDeclaration targetMethod = getTargetMethod(targetClass, targetMethodName, targetClassName);

        // 3. Trace Context
        ExtractionContext extractionContext = new ExtractionContext();
        traceMethod(targetMethod, extractionContext);

        // 4. Export Clean Payload
        StringBuilder payload = buildPayload(targetClass, targetMethod, extractionContext);
        System.out.println(payload);
    }

    private static void setupSymbolSolver(String projectPath) throws IOException {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());

        List<File> sourceRoots = findModuleSourceRoots(projectPath);
        for (File srcDir : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(srcDir));
        }

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);

        for (File srcDir : sourceRoots) {
            try (Stream<Path> paths = Files.walk(srcDir.toPath())) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(path);
                        allCompilationUnits.add(cu);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private static ClassOrInterfaceDeclaration getTargetClass(String targetClassName) {
        List<ClassOrInterfaceDeclaration> candidateClasses = new ArrayList<>();
        for (CompilationUnit cu : allCompilationUnits) {
            cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getNameAsString().equalsIgnoreCase(targetClassName))
                    .forEach(candidateClasses::add);
        }

        if (candidateClasses.isEmpty()) {
            throw new RuntimeException("Class not found: " + targetClassName);
        }
        return candidateClasses.get(0);
    }

    private static MethodDeclaration getTargetMethod(ClassOrInterfaceDeclaration targetClass, String targetMethodName, String targetClassName) {
        return targetClass.getMethodsByName(targetMethodName).stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Method '" + targetMethodName + "' not found in class " + targetClassName));
    }

    private static void traceMethod(MethodDeclaration method, ExtractionContext extractionContext) {
        if (method == null || !extractionContext.visitedMethods.add(method)) {
            return;
        }

        extractionContext.capturedMethods.add(method);
        captureDataTypes(extractionContext.capturedDataTypes, method);
        captureConstantsFromMethod(method, extractionContext);

        for (MethodCallExpr invocation : method.findAll(MethodCallExpr.class)) {
            try {
                ResolvedMethodDeclaration target = invocation.resolve();
                processInvocations(target, invocation, extractionContext);
            } catch (Exception ignored) {
            }
        }
    }

    private static void processInvocations(ResolvedMethodDeclaration target, MethodCallExpr invocation, ExtractionContext extractionContext) {
        String declaringClassName = target.declaringType().getQualifiedName();
        Optional<ClassOrInterfaceDeclaration> astDecl = findClassDeclarationByQualifiedName(declaringClassName);

        // Fallback scope resolution (e.g. movieRepository field typed as MovieRepository calling findById)
        if (invocation.getScope().isPresent()) {
            try {
                ResolvedType scopeType = invocation.getScope().get().calculateResolvedType();
                if (scopeType.isReferenceType()) {
                    String scopeClassName = scopeType.asReferenceType().getQualifiedName();
                    if (isApplicationClass(scopeClassName)) {
                        Optional<ClassOrInterfaceDeclaration> scopeAstDecl = findClassDeclarationByQualifiedName(scopeClassName);
                        if (scopeAstDecl.isPresent()) {
                            astDecl = scopeAstDecl;
                            declaringClassName = scopeClassName;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (!isApplicationClass(declaringClassName)) {
            return;
        }

        boolean isAbstractOrInterface = target.declaringType().isInterface() ||
                astDecl.map(ClassOrInterfaceDeclaration::isInterface).orElse(false) ||
                astDecl.map(ClassOrInterfaceDeclaration::isAbstract).orElse(false);

        if (isAbstractOrInterface) {
            final String finalDeclaringClassName = declaringClassName;

            // 1. Capture Application Interface Shell
            astDecl.ifPresent(extractionContext.capturedInterfaces::add);

            // 2. Track specific invocation signature under the interface (captures inherited methods like findById)
            String methodSig = target.getQualifiedSignature();
            extractionContext.interfaceInvokedMethods
                    .computeIfAbsent(finalDeclaringClassName, k -> new LinkedHashSet<>())
                    .add(methodSig);

            // 3. Handle explicit Black-Box DAO classes
            if (isBlackBoxClass(target.declaringType().getName()) ||
                    astDecl.map(d -> isBlackBoxClass(d.getNameAsString())).orElse(false)) {
                extractionContext.blackBoxCalls.add(extractProcedureContract(invocation, target));
                return;
            }

            List<ClassOrInterfaceDeclaration> implementations = findImplementations(declaringClassName);

            if (implementations.isEmpty()) {
                // Application repository interface without custom .java implementation:
                // ALREADY captured in capturedInterfaces and interfaceInvokedMethods above.
                // Do NOT dump into blackBoxCalls.
            } else {
                for (ClassOrInterfaceDeclaration implClass : implementations) {
                    if (isBlackBoxClass(implClass.getNameAsString())) {
                        extractionContext.blackBoxCalls.add(extractProcedureContract(invocation, target));
                        continue;
                    }

                    implClass.getMethodsByName(target.getName()).stream()
                            .filter(m -> m.getParameters().size() == target.getNumberOfParams())
                            .findFirst()
                            .ifPresent(implMethod -> traceMethod(implMethod, extractionContext));
                }
            }
        } else {
            if (isBlackBoxClass(target.declaringType().getName())) {
                extractionContext.blackBoxCalls.add(extractProcedureContract(invocation, target));
                return;
            }

            astDecl.flatMap(clazz -> clazz.getMethodsByName(target.getName()).stream()
                            .filter(m -> m.getParameters().size() == target.getNumberOfParams())
                            .findFirst())
                    .ifPresent(targetMethod -> traceMethod(targetMethod, extractionContext));
        }
    }

    private static void captureConstantsFromMethod(MethodDeclaration method, ExtractionContext extractionContext) {
        method.findAll(FieldAccessExpr.class).forEach(fae -> {
            try {
                checkAndRecordConstant(fae.resolve().asField(), extractionContext);
            } catch (Exception ignored) {
            }
        });

        method.findAll(NameExpr.class).forEach(ne -> {
            try {
                checkAndRecordConstant(ne.resolve().asField(), extractionContext);
            } catch (Exception ignored) {
            }
        });
    }

    private static void checkAndRecordConstant(ResolvedFieldDeclaration resolvedField, ExtractionContext extractionContext) {
        if (resolvedField.isStatic()) {
            String declaringClassName = resolvedField.declaringType().getQualifiedName();
            if (isApplicationClass(declaringClassName)) {
                findClassDeclarationByQualifiedName(declaringClassName).ifPresent(decl -> {
                    for (FieldDeclaration fieldDecl : decl.getFields()) {
                        if (fieldDecl.hasModifier(com.github.javaparser.ast.Modifier.Keyword.FINAL)) {
                            for (VariableDeclarator var : fieldDecl.getVariables()) {
                                if (var.getNameAsString().equals(resolvedField.getName())) {
                                    extractionContext.capturedConstants
                                            .computeIfAbsent(declaringClassName, k -> new LinkedHashSet<>())
                                            .add(fieldDecl.toString());
                                }
                            }
                        }
                    }
                });
            }
        }
    }

    private static void captureDataTypes(Set<ClassOrInterfaceDeclaration> capturedDataTypes, MethodDeclaration method) {
        addTypeIfEntityOrDto(method.getType(), capturedDataTypes);
        for (Parameter param : method.getParameters()) {
            addTypeIfEntityOrDto(param.getType(), capturedDataTypes);
        }
    }

    private static void addTypeIfEntityOrDto(Type type, Set<ClassOrInterfaceDeclaration> capturedDataTypes) {
        if (type == null) return;
        try {
            ResolvedType resolvedType = type.resolve();
            if (resolvedType.isReferenceType()) {
                String qualifiedName = resolvedType.asReferenceType().getQualifiedName();
                if (isApplicationClass(qualifiedName)) {
                    findClassDeclarationByQualifiedName(qualifiedName).ifPresent(decl -> {
                        if (isDataModelType(decl)) {
                            capturedDataTypes.add(decl);
                        }
                    });
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isDataModelType(ClassOrInterfaceDeclaration decl) {
        if (decl.isInterface() || decl.getNameAsString().endsWith("Repository") ||
                decl.getNameAsString().endsWith("Service") || decl.getNameAsString().endsWith("Controller")) {
            return false;
        }

        String pkg = decl.getFullyQualifiedName().orElse("").toLowerCase();
        boolean matchesPackagePattern = pkg.contains(".entity.") || pkg.contains(".dto.") ||
                pkg.contains(".model.") || pkg.contains(".domain.");

        boolean hasDataAnnotation = decl.getAnnotations().stream().anyMatch(a -> {
            String name = a.getNameAsString();
            return name.equals("Entity") || name.equals("Table") || name.equals("Data") || name.equals("Value");
        });

        return matchesPackagePattern || hasDataAnnotation;
    }

    private static String extractProcedureContract(MethodCallExpr invocation, ResolvedMethodDeclaration target) {
        StringBuilder sb = new StringBuilder();
        String className = target.declaringType().getName();

        sb.append("// BLACK-BOX CALL: ").append(className).append("#").append(target.getName()).append("\n");
        if (!invocation.getArguments().isEmpty()) {
            sb.append("// Passed Arguments:\n");
            for (int i = 0; i < invocation.getArguments().size(); i++) {
                sb.append("//   Arg [").append(i + 1).append("]: ").append(invocation.getArgument(i).toString()).append("\n");
            }
        }
        return sb.toString();
    }

    private static boolean isBlackBoxClass(String className) {
        return BLACK_BOX_CLASSES.contains(className);
    }

    private static List<ClassOrInterfaceDeclaration> findImplementations(String targetInterfaceQualifiedName) {
        List<ClassOrInterfaceDeclaration> implementations = new ArrayList<>();
        for (CompilationUnit cu : allCompilationUnits) {
            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (clazz.isInterface() || clazz.isAbstract()) continue;

                boolean implementsInterface = clazz.getImplementedTypes().stream()
                        .map(ClassOrInterfaceType::getNameAsString)
                        .anyMatch(targetInterfaceQualifiedName::endsWith);

                boolean extendsSuperclass = clazz.getExtendedTypes().stream()
                        .map(ClassOrInterfaceType::getNameAsString)
                        .anyMatch(targetInterfaceQualifiedName::endsWith);

                if (implementsInterface || extendsSuperclass) {
                    implementations.add(clazz);
                }
            }
        }
        return implementations;
    }

    private static Optional<ClassOrInterfaceDeclaration> findClassDeclarationByQualifiedName(String qualifiedName) {
        for (CompilationUnit cu : allCompilationUnits) {
            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (clazz.getFullyQualifiedName().orElse("").equals(qualifiedName)) {
                    return Optional.of(clazz);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isApplicationClass(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) return false;
        return ALLOWED_PACKAGE_PREFIXES.stream().anyMatch(qualifiedName::startsWith);
    }

    private static List<File> findModuleSourceRoots(String rootPath) {
        List<File> sourceRoots = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get(rootPath))) {
            paths.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Paths.get("src", "main", "java")))
                    .filter(p -> {
                        String lower = p.toString().toLowerCase();
                        return !lower.contains("/target/") && !lower.contains("/build/") &&
                                !lower.contains("/bin/") && !lower.contains("/out/");
                    })
                    .forEach(p -> sourceRoots.add(p.toFile()));
        } catch (Exception e) {
            System.err.println("Warning: Failed to walk project paths.");
        }
        if (sourceRoots.isEmpty()) {
            sourceRoots.add(new File(rootPath));
        }
        return sourceRoots;
    }

    private static StringBuilder buildPayload(ClassOrInterfaceDeclaration targetClass, MethodDeclaration targetMethod, ExtractionContext extractionContext) {
        StringBuilder payload = new StringBuilder();
        appendEntryPoint(payload, targetClass, targetMethod);
        appendExecutableMethods(payload, extractionContext.capturedMethods);
        appendCapturedInterfaces(payload, extractionContext.capturedInterfaces, extractionContext.interfaceInvokedMethods);
        appendReferencedConstants(payload, extractionContext.capturedConstants);
        appendBlackboxCalls(payload, extractionContext.blackBoxCalls);
        appendDependentDataTypes(payload, extractionContext.capturedDataTypes, extractionContext.capturedMethods, targetClass);
        return payload;
    }

    private static void appendEntryPoint(StringBuilder payload, ClassOrInterfaceDeclaration targetClass, MethodDeclaration targetMethod) {
        payload.append("// ENTRY POINT: ")
                .append(targetClass.getFullyQualifiedName().orElse(targetClass.getNameAsString()))
                .append("#").append(targetMethod.getNameAsString()).append("\n\n");
    }

    private static void appendExecutableMethods(StringBuilder payload, Set<MethodDeclaration> capturedMethods) {
        payload.append("// --- TRACED EXECUTABLE METHODS ---\n\n");
        for (MethodDeclaration method : capturedMethods) {
            String className = method.findAncestor(ClassOrInterfaceDeclaration.class)
                    .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName)
                    .orElse("Unknown");
            payload.append("// Method: ").append(className).append("#").append(method.getNameAsString()).append("\n");
            payload.append(method).append("\n\n-----------------------------------\n\n");
        }
    }

    private static void appendCapturedInterfaces(
            StringBuilder payload,
            Set<ClassOrInterfaceDeclaration> capturedInterfaces,
            Map<String, Set<String>> interfaceInvokedMethods) {

        if (capturedInterfaces.isEmpty()) return;

        payload.append("// --- APPLICATION INTERFACES & CONTRACTS (JPA/SERVICES) ---\n\n");

        for (ClassOrInterfaceDeclaration iface : capturedInterfaces) {
            String qName = iface.getFullyQualifiedName().orElse(iface.getNameAsString());
            Set<String> invokedSignatures = interfaceInvokedMethods.getOrDefault(qName, Collections.emptySet());

            // Extract just the method names invoked during tracing (e.g. "find", "findById")
            Set<String> invokedMethodNames = new HashSet<>();
            for (String sig : invokedSignatures) {
                String methodName = sig.substring(sig.lastIndexOf('.') + 1).split("\\(")[0];
                invokedMethodNames.add(methodName);
            }

            payload.append("// Interface: ").append(qName).append("\n");

            // Print Class Level Annotations
            iface.getAnnotations().forEach(a -> payload.append(a.toString()).append("\n"));

            // Print Interface Shell
            payload.append("public interface ").append(iface.getNameAsString());
            if (!iface.getExtendedTypes().isEmpty()) {
                payload.append(" extends ");
                for (int i = 0; i < iface.getExtendedTypes().size(); i++) {
                    payload.append(iface.getExtendedTypes().get(i).toString());
                    if (i < iface.getExtendedTypes().size() - 1) payload.append(", ");
                }
            }
            payload.append(" {\n");

            // 1. Print ONLY declared methods that were actually invoked
            boolean printedAny = false;
            for (MethodDeclaration method : iface.getMethods()) {
                if (invokedMethodNames.contains(method.getNameAsString())) {
                    payload.append("    ").append(method.getDeclarationAsString()).append(";\n");
                    printedAny = true;
                }
            }

            // 2. Print inherited methods (like findById) that are NOT explicitly declared in .java file
            for (String invokedSig : invokedSignatures) {
                String methodName = invokedSig.substring(invokedSig.lastIndexOf('.') + 1).split("\\(")[0];
                boolean isDeclaredInAst = iface.getMethods().stream()
                        .anyMatch(m -> m.getNameAsString().equals(methodName));

                if (!isDeclaredInAst) {
                    String simpleSig = invokedSig.substring(invokedSig.lastIndexOf('.') + 1);
                    payload.append("    // Inherited Execution Call: ").append(simpleSig).append(";\n");
                    printedAny = true;
                }
            }

            if (!printedAny) {
                payload.append("    // (No methods from this interface were referenced in this execution trace)\n");
            }

            payload.append("}\n\n-----------------------------------\n\n");
        }
    }

    private static void appendReferencedConstants(StringBuilder payload, Map<String, Set<String>> capturedConstants) {
        if (!capturedConstants.isEmpty()) {
            payload.append("// --- REFERENCED CONSTANTS ---\n\n");
            for (Map.Entry<String, Set<String>> entry : capturedConstants.entrySet()) {
                payload.append("// Declared In: ").append(entry.getKey()).append("\n");
                for (String fieldSnippet : entry.getValue()) {
                    payload.append("  ").append(fieldSnippet).append("\n");
                }
                payload.append("\n-----------------------------------\n\n");
            }
        }
    }

    private static void appendBlackboxCalls(StringBuilder payload, Set<String> blackBoxCalls) {
        if (!blackBoxCalls.isEmpty()) {
            payload.append("// --- BLACK-BOX DAO / STORED PROCEDURE CALLS ---\n\n");
            for (String callSummary : blackBoxCalls) {
                payload.append(callSummary).append("\n-----------------------------------\n\n");
            }
        }
    }

    private static ClassOrInterfaceDeclaration cleanDataModelForPayload(ClassOrInterfaceDeclaration originalDecl) {
        ClassOrInterfaceDeclaration cleanDecl = originalDecl.clone();
        cleanDecl.getConstructors().forEach(cleanDecl::remove);
        cleanDecl.getMethods().forEach(method -> {
            String name = method.getNameAsString();
            boolean isGetter = (name.startsWith("get") || name.startsWith("is"))
                    && method.getParameters().isEmpty()
                    && !method.getType().isVoidType();

            boolean isSetter = name.startsWith("set")
                    && method.getParameters().size() == 1
                    && method.getType().isVoidType();

            if (isGetter || isSetter) {
                cleanDecl.remove(method);
            }
        });
        return cleanDecl;
    }

    private static void appendDependentDataTypes(StringBuilder payload, Set<ClassOrInterfaceDeclaration> capturedDataTypes, Set<MethodDeclaration> capturedMethods, ClassOrInterfaceDeclaration targetClass) {
        if (!capturedDataTypes.isEmpty()) {
            payload.append("// --- DEPENDENT DATA TYPES / ENTITIES ---\n\n");
            for (ClassOrInterfaceDeclaration type : capturedDataTypes) {
                boolean isAlreadyTracedService = capturedMethods.stream()
                        .anyMatch(m -> m.findAncestor(ClassOrInterfaceDeclaration.class).map(type::equals).orElse(false));

                if (!type.equals(targetClass) && !isAlreadyTracedService) {
                    ClassOrInterfaceDeclaration cleanedType = cleanDataModelForPayload(type);
                    payload.append("// Type Definition: ")
                            .append(cleanedType.getFullyQualifiedName().orElse(cleanedType.getNameAsString()))
                            .append("\n");
                    payload.append(cleanedType.toString()).append("\n\n-----------------------------------\n\n");
                }
            }
        }
    }
}