package com.nexus.context.source.skill;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sélection déterministe de skills à partir de leur nom et de leur description.
 */
public final class SkillSelector {

    private static final double MIN_SCORE = 0.22d;
    private static final int MAX_SELECTED_SKILLS = 3;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it",
            "of", "on", "or", "that", "the", "this", "to", "use", "when", "with",
            "de", "des", "du", "et", "en", "la", "le", "les", "pour", "sur", "un", "une",
            "avec", "dans", "est", "ce", "cette", "ces", "au", "aux");

    public List<SkillMatch> select(String query, List<SkillDescriptor> skills) {
        String normalizedQuery = normalize(query);
        Set<String> queryTerms = terms(normalizedQuery);
        List<SkillMatch> matches = new ArrayList<>();

        for (SkillDescriptor skill : skills) {
            String normalizedName = normalize(skill.name().replace('-', ' '));
            String normalizedDescription = normalize(skill.description());
            Set<String> nameTerms = terms(normalizedName);
            Set<String> descriptionTerms = terms(normalizedDescription);

            boolean exactName = normalizedQuery.contains(normalizedName)
                    || normalizedQuery.contains(normalize(skill.name()));
            int matchedNameTerms = matchingTerms(queryTerms, nameTerms);
            int matchedDescriptionTerms = matchingTerms(queryTerms, descriptionTerms);
            int matchedQueryTerms = matchingTerms(queryTerms, union(nameTerms, descriptionTerms));

            double nameCoverage = nameTerms.isEmpty()
                    ? 0.0d
                    : (double) matchedNameTerms / nameTerms.size();
            double queryCoverage = queryTerms.isEmpty()
                    ? 0.0d
                    : (double) matchedQueryTerms / queryTerms.size();
            boolean fullQueryPhrase = normalizedQuery.length() >= 4
                    && normalizedDescription.contains(normalizedQuery);

            double score = 0.0d;
            if (exactName) {
                score += 0.65d;
            }
            score += 0.25d * nameCoverage;
            score += 0.55d * queryCoverage;
            if (fullQueryPhrase) {
                score += 0.15d;
            }
            score = Math.min(1.0d, score);

            if (!exactName && matchedNameTerms == 0 && matchedDescriptionTerms == 0) {
                continue;
            }
            if (score < MIN_SCORE) {
                continue;
            }

            List<String> reasons = new ArrayList<>();
            if (exactName) {
                reasons.add("nom du skill mentionné explicitement dans la requête");
            }
            if (matchedNameTerms > 0) {
                reasons.add("correspondance avec le nom du skill : " + matchedNameTerms + " terme(s)");
            }
            if (matchedDescriptionTerms > 0) {
                reasons.add("correspondance avec la description : " + matchedDescriptionTerms + " terme(s)");
            }
            reasons.add(String.format(Locale.ROOT, "score de sélection du skill : %.3f", score));
            matches.add(new SkillMatch(skill, score, reasons));
        }

        return matches.stream()
                .sorted(Comparator
                        .comparingDouble(SkillMatch::score).reversed()
                        .thenComparing(match -> match.skill().priority(), Comparator.reverseOrder())
                        .thenComparing(match -> match.skill().name())
                        .thenComparing(match -> repositoryPath(match.skill().definitionPath())))
                .limit(MAX_SELECTED_SKILLS)
                .toList();
    }

    private static int matchingTerms(Set<String> left, Set<String> right) {
        int matches = 0;
        for (String candidate : left) {
            if (right.stream().anyMatch(other -> equivalentToken(candidate, other))) {
                matches++;
            }
        }
        return matches;
    }

    private static boolean equivalentToken(String left, String right) {
        return left.equals(right) || stem(left).equals(stem(right));
    }

    private static String stem(String token) {
        if (token.length() > 5 && token.endsWith("ing")) {
            return token.substring(0, token.length() - 3);
        }
        if (token.length() > 4 && token.endsWith("ed")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.length() > 4 && token.endsWith("es")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.length() > 3 && token.endsWith("s")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.addAll(right);
        return result;
    }

    private static Set<String> terms(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String term : text.split("[^\\p{L}\\p{N}]+")) {
            if (!term.isBlank() && term.length() >= 2 && !STOP_WORDS.contains(term)) {
                result.add(term);
            }
        }
        return result;
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String repositoryPath(java.nio.file.Path path) {
        return path.toString().replace('\\', '/');
    }
}
