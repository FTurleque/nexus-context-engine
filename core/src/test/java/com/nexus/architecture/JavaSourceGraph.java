package com.nexus.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Graphe des dépendances d'un arbre de sources Java, calculé depuis le texte des sources.
 *
 * <p>Chaque fichier est réduit à son code (commentaires, chaînes, blocs de texte et littéraux
 * caractère remplacés par des espaces), puis lu pour sa déclaration {@code package}, ses
 * déclarations {@code import} (y compris {@code import static} et les imports à joker) et les
 * références pleinement qualifiées à des préfixes surveillés. Le graphe des packages ne retient
 * que les imports entre packages de la racine analysée.</p>
 */
final class JavaSourceGraph {

    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("\\bimport\\s+(static\\s+)?([\\w.]+(?:\\s*\\.\\s*\\*)?)\\s*;");

    private final List<SourceFile> files;

    private JavaSourceGraph(List<SourceFile> files) {
        this.files = List.copyOf(files);
    }

    /** Lit tous les fichiers {@code .java} sous {@code sourceRoot}, dans un ordre déterministe. */
    static JavaSourceGraph scan(Path sourceRoot, List<String> watchedQualifiedPrefixes) {
        List<SourceFile> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<Path> sources = paths
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
            for (Path source : sources) {
                files.add(parse(sourceRoot.relativize(source), Files.readString(source, StandardCharsets.UTF_8),
                        watchedQualifiedPrefixes));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return new JavaSourceGraph(files);
    }

    /** Analyse un fichier source ; {@code relativePath} sert à nommer la classe. */
    static SourceFile parse(Path relativePath, String source, List<String> watchedQualifiedPrefixes) {
        String code = stripCommentsAndLiterals(source);
        Matcher packageMatcher = PACKAGE.matcher(code);
        String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
        String fileName = relativePath.getFileName().toString();
        String simpleName = fileName.substring(0, fileName.length() - ".java".length());
        String className = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

        List<String> imports = new ArrayList<>();
        Matcher importMatcher = IMPORT.matcher(code);
        while (importMatcher.find()) {
            imports.add(importMatcher.group(2).replaceAll("\\s+", ""));
        }

        Set<String> qualifiedReferences = new LinkedHashSet<>();
        String body = IMPORT.matcher(PACKAGE.matcher(code).replaceAll(" ")).replaceAll(" ");
        for (String prefix : watchedQualifiedPrefixes) {
            Matcher reference = Pattern.compile("(?<![\\w.])" + Pattern.quote(prefix) + "[\\w.]*").matcher(body);
            while (reference.find()) {
                qualifiedReferences.add(reference.group());
            }
        }
        return new SourceFile(className, packageName, List.copyOf(imports), List.copyOf(qualifiedReferences));
    }

    List<SourceFile> files() {
        return files;
    }

    /**
     * Graphe des packages : arête {@code a -> b} quand une classe de {@code a} importe un type de
     * {@code b}, les deux packages appartenant à la racine analysée.
     */
    SortedMap<String, SortedSet<String>> packageGraph() {
        Set<String> knownPackages = new TreeSet<>();
        for (SourceFile file : files) {
            knownPackages.add(file.packageName());
        }
        SortedMap<String, SortedSet<String>> graph = new TreeMap<>();
        for (String packageName : knownPackages) {
            graph.put(packageName, new TreeSet<>());
        }
        for (SourceFile file : files) {
            for (String imported : file.imports()) {
                String target = owningPackage(imported, knownPackages);
                if (target != null && !target.equals(file.packageName())) {
                    graph.get(file.packageName()).add(target);
                }
            }
        }
        return graph;
    }

    /**
     * Arêtes du graphe des packages dont les deux extrémités appartiennent à la même composante
     * fortement connexe, au format {@code a -> b}, triées.
     */
    SortedSet<String> cycleEdges() {
        SortedMap<String, SortedSet<String>> graph = packageGraph();
        Map<String, Integer> component = stronglyConnectedComponents(graph);
        Map<Integer, Integer> sizes = new HashMap<>();
        component.values().forEach(id -> sizes.merge(id, 1, Integer::sum));
        SortedSet<String> edges = new TreeSet<>();
        graph.forEach((from, targets) -> {
            for (String to : targets) {
                int id = component.get(from);
                if (id == component.get(to) && sizes.get(id) > 1) {
                    edges.add(from + " -> " + to);
                }
            }
        });
        return edges;
    }

    /**
     * Package propriétaire d'un import : le plus long package connu qui préfixe le nom importé
     * ({@code a.b.Type}, {@code a.b.Type.member}, {@code a.b.*}).
     */
    private static String owningPackage(String imported, Set<String> knownPackages) {
        String candidate = imported.endsWith(".*") ? imported.substring(0, imported.length() - 2) : imported;
        while (!candidate.isEmpty()) {
            if (knownPackages.contains(candidate)) {
                return candidate;
            }
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            candidate = candidate.substring(0, dot);
        }
        return null;
    }

    /** Tarjan itératif ; renvoie l'identifiant de composante de chaque nœud. */
    private static Map<String, Integer> stronglyConnectedComponents(SortedMap<String, SortedSet<String>> graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowLink = new HashMap<>();
        Map<String, Integer> component = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] counter = {0};
        int[] componentCounter = {0};
        for (String root : graph.keySet()) {
            if (index.containsKey(root)) {
                continue;
            }
            Deque<Map.Entry<String, Iterator<String>>> work = new ArrayDeque<>();
            visit(root, index, lowLink, stack, onStack, counter);
            work.push(Map.entry(root, graph.get(root).iterator()));
            while (!work.isEmpty()) {
                Map.Entry<String, Iterator<String>> frame = work.peek();
                String node = frame.getKey();
                Iterator<String> successors = frame.getValue();
                if (successors.hasNext()) {
                    String next = successors.next();
                    if (!index.containsKey(next)) {
                        visit(next, index, lowLink, stack, onStack, counter);
                        work.push(Map.entry(next, graph.get(next).iterator()));
                    } else if (onStack.contains(next)) {
                        lowLink.put(node, Math.min(lowLink.get(node), index.get(next)));
                    }
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    String parent = work.peek().getKey();
                    lowLink.put(parent, Math.min(lowLink.get(parent), lowLink.get(node)));
                }
                if (lowLink.get(node).equals(index.get(node))) {
                    String member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        component.put(member, componentCounter[0]);
                    } while (!member.equals(node));
                    componentCounter[0]++;
                }
            }
        }
        return Collections.unmodifiableMap(component);
    }

    private static void visit(String node, Map<String, Integer> index, Map<String, Integer> lowLink,
                              Deque<String> stack, Set<String> onStack, int[] counter) {
        index.put(node, counter[0]);
        lowLink.put(node, counter[0]);
        counter[0]++;
        stack.push(node);
        onStack.add(node);
    }

    /**
     * Remplace par des espaces les commentaires, chaînes, blocs de texte et littéraux caractère,
     * en gardant les retours à la ligne.
     */
    static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            char next = i + 1 < length ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < length && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? length : end + 2;
                blank(source, i, stop, out);
                i = stop;
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                while (end > 0 && isEscaped(source, end)) {
                    end = source.indexOf("\"\"\"", end + 1);
                }
                int stop = end < 0 ? length : end + 3;
                blank(source, i, stop, out);
                i = stop;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < length && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                int stop = Math.min(length, j + 1);
                blank(source, i, stop, out);
                i = stop;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isEscaped(String source, int position) {
        int backslashes = 0;
        for (int k = position - 1; k >= 0 && source.charAt(k) == '\\'; k--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static void blank(String source, int from, int to, StringBuilder out) {
        for (int k = from; k < to; k++) {
            out.append(source.charAt(k) == '\n' ? '\n' : ' ');
        }
    }

    /** Fichier source analysé. */
    record SourceFile(String className, String packageName, List<String> imports, List<String> qualifiedReferences) {

        /** Imports et références pleinement qualifiées, dans l'ordre de lecture. */
        List<String> referencedNames() {
            List<String> names = new ArrayList<>(imports);
            names.addAll(qualifiedReferences);
            return names;
        }
    }
}
