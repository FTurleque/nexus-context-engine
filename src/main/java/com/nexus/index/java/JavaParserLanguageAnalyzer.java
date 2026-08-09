package com.nexus.index.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.nexus.index.AnalysisResult;
import com.nexus.index.CodeSymbol;
import com.nexus.index.LanguageAnalyzer;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import com.nexus.security.SafeFileIO;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class JavaParserLanguageAnalyzer implements LanguageAnalyzer {

    private static final ParserConfiguration.LanguageLevel LANGUAGE_LEVEL =
            ParserConfiguration.LanguageLevel.JAVA_21;

    @Override
    public boolean supports(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java");
    }

    @Override
    public AnalysisResult analyze(Path projectRoot, Path file) throws IOException {
        return analyze(projectRoot, file, SafeFileIO.readStringNoFollow(file));
    }

    @Override
    public AnalysisResult analyze(Path projectRoot, Path file, String source) throws IOException {
        Objects.requireNonNull(source, "source");
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL));
        CompilationUnit unit = parser.parse(source)
                .getResult()
                .orElseThrow(() -> new IOException("Impossible d'analyser le fichier Java 21 : " + file));
        String packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");

        List<CodeSymbol> symbols = new ArrayList<>();
        for (Node node : unit.findAll(Node.class)) {
            if (node instanceof TypeDeclaration<?> type) {
                type.getRange().ifPresent(range -> symbols.add(new CodeSymbol(
                        symbolKind(type),
                        type.getNameAsString(),
                        qualifiedOwner(packageName, type),
                        type.getNameAsString(),
                        range.begin.line,
                        range.end.line)));
            }
        }

        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            String qualifiedName = qualifiedOwner(packageName, method)
                    + "#" + method.getSignature().asString();
            method.getRange().ifPresent(range -> symbols.add(new CodeSymbol(
                    SymbolKind.METHOD,
                    method.getNameAsString(),
                    qualifiedName,
                    method.getSignature().asString(),
                    range.begin.line,
                    range.end.line)));
        }

        for (ConstructorDeclaration constructor : unit.findAll(ConstructorDeclaration.class)) {
            String qualifiedName = qualifiedOwner(packageName, constructor)
                    + "#" + constructor.getSignature().asString();
            constructor.getRange().ifPresent(range -> symbols.add(new CodeSymbol(
                    SymbolKind.CONSTRUCTOR,
                    constructor.getNameAsString(),
                    qualifiedName,
                    constructor.getSignature().asString(),
                    range.begin.line,
                    range.end.line)));
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

    private static String qualifiedOwner(String packageName, Node node) {
        List<String> typeNames = new ArrayList<>();
        Node current = node;
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                typeNames.add(0, type.getNameAsString());
            }
            current = current.getParentNode().orElse(null);
        }
        String owner = typeNames.isEmpty() ? "<unknown>" : String.join(".", typeNames);
        return qualify(packageName, owner);
    }

    private static String qualify(String packageName, String name) {
        return packageName.isBlank() ? name : packageName + "." + name;
    }
}
