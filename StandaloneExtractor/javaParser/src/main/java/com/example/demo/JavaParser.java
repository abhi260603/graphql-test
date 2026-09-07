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

import static com.example.demo.OutputWriterUtil.*;

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
            System.out.print("Usage: java -jar java-ast-extractor-1.0.0.jar <repo-path> <ClassName> <MethodName>");
            System.exit(1);
        }
        String projectPath = args[0];
        String targetClassName = args[1];
        String targetMethodName = args[2];

        setupSymbolSolver(projectPath);
        ClassOrInterfaceDeclaration targetClass = getTargetClass(targetClassName);
        MethodDeclaration targetMethod = getTargetMethod(targetClass, targetMethodName, targetClassName);
        ExtractionContext extractionContext = new ExtractionContext();
        traceMethod(targetMethod, extractionContext);
        StringBuilder payload = buildPayload(targetClass, targetMethod, extractionContext);
        System.out.print(payload);
    }

    private static void setupSymbolSolver(String projectPath) throws IOException {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        List<File> sourceRoots = findModuleSourceRoots(projectPath);
        for (File srcDir : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(srcDir));
        }
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        StaticJavaParser.getParserConfiguration().setSymbolResolver(symbolSolver);
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

    private record TargetContext(String declaringClassName, Optional<ClassOrInterfaceDeclaration> astDecl) {}

    private static TargetContext resolveTargetContext(ResolvedMethodDeclaration target, MethodCallExpr invocation) {
        String declaringClassName = target.declaringType().getQualifiedName();
        Optional<ClassOrInterfaceDeclaration> astDecl = findClassDeclarationByQualifiedName(declaringClassName);
        if (invocation.getScope().isPresent()) {
            try {
                ResolvedType scopeType = invocation.getScope().get().calculateResolvedType();
                if (scopeType.isReferenceType()) {
                    String scopeClassName = scopeType.asReferenceType().getQualifiedName();
                    if (isApplicationClass(scopeClassName)) {
                        Optional<ClassOrInterfaceDeclaration> scopeAstDecl = findClassDeclarationByQualifiedName(scopeClassName);
                        if (scopeAstDecl.isPresent()) {
                            return new TargetContext(scopeClassName, scopeAstDecl);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return new TargetContext(declaringClassName, astDecl);
    }

    private static boolean isBlackBox(ResolvedMethodDeclaration target, Optional<ClassOrInterfaceDeclaration> astDecl) {
        return isBlackBoxClass(target.declaringType().getName())
                || astDecl.map(d -> isBlackBoxClass(d.getNameAsString())).orElse(false);
    }

    private static void processAbstractOrInterfaceInvocation(ResolvedMethodDeclaration target, MethodCallExpr invocation,
            TargetContext context, ExtractionContext extractionContext) {
        context.astDecl().ifPresent(extractionContext.capturedInterfaces::add);
        extractionContext.interfaceInvokedMethods
                .computeIfAbsent(context.declaringClassName(), k -> new LinkedHashSet<>())
                .add(target.getQualifiedSignature());
        if (isBlackBox(target, context.astDecl())) {
            extractionContext.blackBoxCalls.add(extractProcedureContract(invocation, target));
            return;
        }
        findImplementations(context.declaringClassName())
                .forEach(implClass -> processImplementationClass(target, invocation, implClass, extractionContext));
    }

    private static Optional<MethodDeclaration> findMatchingAstMethod(ResolvedMethodDeclaration target, ClassOrInterfaceDeclaration clazz) {
        if (clazz == null) return Optional.empty();
        return clazz.getMethodsByName(target.getName()).stream()
                .filter(m -> m.getParameters().size() == target.getNumberOfParams())
                .findFirst();
    }

    private static void processImplementationClass(ResolvedMethodDeclaration target, MethodCallExpr invocation,
                                                   ClassOrInterfaceDeclaration implClass, ExtractionContext extractionContext) {
        if (isBlackBoxClass(implClass.getNameAsString())) {
            extractionContext.blackBoxCalls.add(extractProcedureContract(invocation, target));
            return;
        }
        findMatchingAstMethod(target, implClass)
                .ifPresent(implMethod -> traceMethod(implMethod, extractionContext));
    }

    private static void processConcreteInvocation(ResolvedMethodDeclaration target, MethodCallExpr invocation,
                                                  Optional<ClassOrInterfaceDeclaration> astDecl, ExtractionContext extractionContext) {
        if (isBlackBox(target, astDecl)) {
            extractionContext.blackBoxCalls.add(extractProcedureContract(invocation, target));
            return;
        }
        findMatchingAstMethod(target, astDecl)
                .ifPresent(targetMethod -> traceMethod(targetMethod, extractionContext));
    }

    private static void processInvocations(ResolvedMethodDeclaration target, MethodCallExpr invocation, ExtractionContext extractionContext) {
        TargetContext targetContext = resolveTargetContext(target, invocation);
        if (!isApplicationClass(targetContext.declaringClassName())) {
            return;
        }
        if (isAbstractOrInterface(target, targetContext.astDecl())) {
            processAbstractOrInterfaceInvocation(target, invocation, targetContext, extractionContext);
        } else {
            processConcreteInvocation(target, invocation, targetContext.astDecl(), extractionContext);
        }
    }

    private static Optional<MethodDeclaration> findMatchingAstMethod(ResolvedMethodDeclaration target, Optional<ClassOrInterfaceDeclaration> astDecl) {
        return astDecl.flatMap(clazz -> findMatchingAstMethod(target, clazz));
    }

    private static boolean isAbstractOrInterface(ResolvedMethodDeclaration target, Optional<ClassOrInterfaceDeclaration> astDecl) {
        return target.declaringType().isInterface() ||
                astDecl.map(ClassOrInterfaceDeclaration::isInterface).orElse(false) ||
                astDecl.map(ClassOrInterfaceDeclaration::isAbstract).orElse(false);
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
        if (!resolvedField.isStatic()) {
            return;
        }
        String declaringClassName = resolvedField.declaringType().getQualifiedName();
        if (!isApplicationClass(declaringClassName)) {
            return;
        }
        findClassDeclarationByQualifiedName(declaringClassName)
                .flatMap(decl -> findMatchingFinalFieldSnippet(decl, resolvedField.getName()))
                .ifPresent(fieldSnippet ->
                        extractionContext.capturedConstants
                                .computeIfAbsent(declaringClassName, k -> new LinkedHashSet<>())
                                .add(fieldSnippet)
                );
    }

    private static Optional<String> findMatchingFinalFieldSnippet(ClassOrInterfaceDeclaration decl, String fieldName) {
        for (FieldDeclaration fieldDecl : decl.getFields()) {
            if (fieldDecl.isFinal()) {
                boolean matchesName = fieldDecl.getVariables().stream()
                        .anyMatch(var -> var.getNameAsString().equals(fieldName));
                if (matchesName) {
                    return Optional.of(fieldDecl.toString());
                }
            }
        }
        return Optional.empty();
    }

    private static void captureDataTypes(Set<ClassOrInterfaceDeclaration> capturedDataTypes, MethodDeclaration method) {
        addTypeIfEntityOrDto(method.getType(), capturedDataTypes);
        for (Parameter param : method.getParameters()) {
            addTypeIfEntityOrDto(param.getType(), capturedDataTypes);
        }
        method.findAll(VariableDeclarator.class).forEach(var -> {
            addTypeIfEntityOrDto(var.getType(), capturedDataTypes);
        });
    }

    private static boolean isDataModelType(ClassOrInterfaceDeclaration decl) {
        if (decl.isInterface() || decl.getNameAsString().endsWith("Repository") ||
                decl.getNameAsString().endsWith("Service") || decl.getNameAsString().endsWith("Controller")) {
            return false;
        }
        String pkg = decl.getFullyQualifiedName().orElse("").toLowerCase();
        boolean matchesPackagePattern = pkg.contains(".entity.") || pkg.contains(".dto.") ||
                pkg.contains(".model.") || pkg.contains(".data.");
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
            System.err.print("Warning: Failed to walk project paths.");
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

    private static void addTypeIfEntityOrDto(Type type, Set<ClassOrInterfaceDeclaration> capturedDataTypes) {
        if (type == null) return;
        try {
            ResolvedType resolvedType = type.resolve();
            extractAndRegisterType(resolvedType, capturedDataTypes);
        } catch (Exception ignored) {
        }
    }

    private static void extractAndRegisterType(ResolvedType resolvedType, Set<ClassOrInterfaceDeclaration> capturedDataTypes) {
        if (!resolvedType.isReferenceType()) return;
        String qualifiedName = resolvedType.asReferenceType().getQualifiedName();
        if (isApplicationClass(qualifiedName)) {
            findClassDeclarationByQualifiedName(qualifiedName).ifPresent(decl -> {
                if (isDataModelType(decl)) {
                    capturedDataTypes.add(decl);
                }
            });
        }
        try {
            var typeParametersMap = resolvedType.asReferenceType().getTypeParametersMap();
            for (var pair : typeParametersMap) {
                ResolvedType genericTypeParam = pair.b;
                extractAndRegisterType(genericTypeParam, capturedDataTypes);
            }
        } catch (Exception ignored) {
        }
    }
}