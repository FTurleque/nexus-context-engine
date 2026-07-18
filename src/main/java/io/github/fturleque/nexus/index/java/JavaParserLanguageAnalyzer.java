package io.github.fturleque.nexus.index.java;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import io.github.fturleque.nexus.index.AnalysisResult;
import io.github.fturleque.nexus.index.CodeSymbol;
import io.github.fturleque.nexus.index.LanguageAnalyzer;
import io.github.fturleque.nexus.index.RelationKind;
import io.github.fturleque.nexus.index.SymbolKind;
import io.github.fturleque.nexus.index.SymbolRelation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class JavaParserLanguageAnalyzer implements LanguageAnalyzer {

    @Override
    public boolean supports(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java");
    }

    @Override
    public AnalysisResult analyze(Path projectRoot, Path file) throws IOException {
        CompilationUnit unit = StaticJavaParser.parse(file);
        String packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");

        List<CodeSymbol> symbols = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            String qualifiedName = qualify(packageName, type.getNameAsString());
            symbols.add(new CodeSymbol(
                    symbolKind(type),
                    type.getNameAsString(),
                    qualifiedName,
                    type.getNameAsString(),
                    startLine(type),
                    endLine(type)));
        }

        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            String owner = method.findAncestor(TypeDeclaration.class)
                    .map(TypeDeclaration::getNameAsString)
                    .orElse("<unknown>");
            String qualifiedName = qualify(packageName, owner) + "#" + method.getSignature().asString();
            symbols.add(new CodeSymbol(
                    SymbolKind.METHOD,
                    method.getNameAsString(),
                    qualifiedName,
                    method.getSignature().asString(),
                    startLine(method),
                    endLine(method)));
        }

        for (ConstructorDeclaration constructor : unit.findAll(ConstructorDeclaration.class)) {
            String qualifiedName = qualify(packageName, constructor.getNameAsString())
                    + "#" + constructor.getSignature().asString();
            symbols.add(new CodeSymbol(
                    SymbolKind.CONSTRUCTOR,
                    constructor.getNameAsString(),
                    qualifiedName,
                    constructor.getSignature().asString(),
                    startLine(constructor),
                    endLine(constructor)));
        }

        String relationSource = projectRoot.relativize(file).toString().replace('\\', '/');
        List<SymbolRelation> relations = unit.getImports().stream()
                .map(importDeclaration -> new SymbolRelation(
                        RelationKind.IMPORTS,
                        relationSource,
                        importDeclaration.getNameAsString()))
                .toList();

        return new AnalysisResult(file, "java", symbols, relations);
    }

    private static SymbolKind symbolKind(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.isInterface() ? SymbolKind.INTERFACE : SymbolKind.CLASS;
        }
        if (type instanceof RecordDeclaration) {
            return SymbolKind.RECORD;
        }
        if (type instanceof EnumDeclaration) {
            return SymbolKind.ENUM;
        }
        if (type instanceof AnnotationDeclaration) {
            return SymbolKind.ANNOTATION;
        }
        return SymbolKind.TYPE;
    }

    private static String qualify(String packageName, String name) {
        return packageName.isBlank() ? name : packageName + "." + name;
    }

    private static int startLine(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(-1);
    }

    private static int endLine(Node node) {
        return node.getRange().map(range -> range.end.line).orElse(-1);
    }
}
