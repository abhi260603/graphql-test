package com.example.demo;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class OutputWriterUtil {

    public static void appendDependentDataTypes(StringBuilder payload, Set<ClassOrInterfaceDeclaration> capturedDataTypes, Set<MethodDeclaration> capturedMethods, ClassOrInterfaceDeclaration targetClass) {
        if (!capturedDataTypes.isEmpty()) {
            payload.append("// --- DEPENDENT DATA TYPES / ENTITIES / DTOS ---\n\n");
            for (ClassOrInterfaceDeclaration type : capturedDataTypes) {
                boolean isAlreadyTracedService = capturedMethods.stream()
                        .anyMatch(m -> m.findAncestor(ClassOrInterfaceDeclaration.class).map(type::equals).orElse(false));
                if (!type.equals(targetClass) && !isAlreadyTracedService) {
                    ClassOrInterfaceDeclaration cleanedType = cleanDataModelForPayload(type);
                    payload.append("// Type Definition: ")
                            .append(cleanedType.getFullyQualifiedName().orElse(cleanedType.getNameAsString()))
                            .append("\n");
                    payload.append(cleanedType).append("\n\n-----------------------------------\n\n");
                }
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

    public static void appendBlackboxCalls(StringBuilder payload, Set<String> blackBoxCalls) {
        if (!blackBoxCalls.isEmpty()) {
            payload.append("// --- BLACK-BOX DAO / STORED PROCEDURE CALLS ---\n\n");
            for (String callSummary : blackBoxCalls) {
                payload.append(callSummary).append("\n-----------------------------------\n\n");
            }
        }
    }

    public static void appendReferencedConstants(StringBuilder payload, Map<String, Set<String>> capturedConstants) {
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

    public static void appendCapturedInterfaces( StringBuilder payload, Set<ClassOrInterfaceDeclaration> capturedInterfaces, Map<String, Set<String>> interfaceInvokedMethods) {
        if (capturedInterfaces.isEmpty()) return;
        payload.append("// --- APPLICATION INTERFACES & CONTRACTS (JPA/SERVICES) ---\n\n");
        for (ClassOrInterfaceDeclaration iface : capturedInterfaces) {
            String qName = iface.getFullyQualifiedName().orElse(iface.getNameAsString());
            Set<String> invokedSignatures = interfaceInvokedMethods.getOrDefault(qName, Collections.emptySet());
            Set<String> invokedMethodNames = new HashSet<>();
            for (String sig : invokedSignatures) {
                String methodName = sig.substring(sig.lastIndexOf('.') + 1).split("\\(")[0];
                invokedMethodNames.add(methodName);
            }
            payload.append("// Interface: ").append(qName).append("\n");
            iface.getAnnotations().forEach(a -> payload.append(a.toString()).append("\n"));
            payload.append("public interface ").append(iface.getNameAsString());
            if (!iface.getExtendedTypes().isEmpty()) {
                payload.append(" extends ");
                for (int i = 0; i < iface.getExtendedTypes().size(); i++) {
                    payload.append(iface.getExtendedTypes().get(i).toString());
                    if (i < iface.getExtendedTypes().size() - 1) payload.append(", ");
                }
            }
            payload.append(" {\n");
            boolean printedAny = false;
            for (MethodDeclaration method : iface.getMethods()) {
                if (invokedMethodNames.contains(method.getNameAsString())) {
                    payload.append("    ").append(method.getDeclarationAsString()).append(";\n");
                    printedAny = true;
                }
            }
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

    public static void appendEntryPoint(StringBuilder payload, ClassOrInterfaceDeclaration targetClass, MethodDeclaration targetMethod) {
        payload.append("// ENTRY POINT: ")
                .append(targetClass.getFullyQualifiedName().orElse(targetClass.getNameAsString()))
                .append("#").append(targetMethod.getNameAsString()).append("\n\n");
    }

    public static void appendExecutableMethods(StringBuilder payload, Set<MethodDeclaration> capturedMethods) {
        payload.append("// --- TRACED EXECUTABLE METHODS ---\n\n");
        for (MethodDeclaration method : capturedMethods) {
            String className = method.findAncestor(ClassOrInterfaceDeclaration.class)
                    .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName)
                    .orElse("Unknown");
            payload.append("// Method: ").append(className).append("#").append(method.getNameAsString()).append("\n");
            payload.append(method).append("\n\n-----------------------------------\n\n");
        }
    }

}
