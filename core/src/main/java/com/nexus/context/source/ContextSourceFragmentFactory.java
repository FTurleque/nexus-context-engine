package com.nexus.context.source;

import com.nexus.context.ContextFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Convertit les sources normalisées en fragments compatibles avec le sélecteur
 * de budget existant.
 */
public final class ContextSourceFragmentFactory {

    private static final Pattern LINE_SEPARATOR = Pattern.compile("\\R");

    public List<ContextFragment> create(List<ContextSourceDescriptor> sources) {
        List<ContextFragment> fragments = new ArrayList<>(sources.size());
        for (ContextSourceDescriptor source : sources) {
            int lineCount = Math.max(1, LINE_SEPARATOR.split(source.content(), -1).length);
            double normalizedPriority = source.priority() / 100.0d;
            Map<String, Double> components = new LinkedHashMap<>();
            components.put("sourcePriority", normalizedPriority);

            List<String> reasons = new ArrayList<>(source.reasons());
            reasons.add("provider : " + source.provider());
            reasons.add("origine : " + source.origin());
            reasons.add("priorité source : " + source.priority() + "/100");
            if (!source.applyTo().isEmpty()) {
                reasons.add("scope applyTo : " + String.join(", ", source.applyTo()));
            }
            Object referencedFrom = source.metadata().get("referencedFrom");
            if (referencedFrom != null) {
                reasons.add("référencé depuis : " + referencedFrom);
            }

            fragments.add(new ContextFragment(
                    source.type(),
                    source.path(),
                    null,
                    1,
                    lineCount,
                    source.content(),
                    normalizedPriority,
                    components,
                    reasons));
        }
        return List.copyOf(fragments);
    }
}
