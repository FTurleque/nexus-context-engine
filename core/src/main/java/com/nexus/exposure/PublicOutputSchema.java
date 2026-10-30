package com.nexus.exposure;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Contrat de clés des sorties publiques et divergences déclarées entre canaux.
 *
 * <p>Les clés attendues sont les composantes des records de {@link PublicOutput}, dans leur ordre.
 * Les tests de parité de la CLI, de REST et de MCP comparent leur JSON réel à ce contrat : toute clé
 * ajoutée, retirée, renommée ou déplacée par un adaptateur est signalée.</p>
 */
public final class PublicOutputSchema {

    /** Clé que la CLI ajoute en tête de chaque objet JSON de premier niveau. */
    public static final String CLI_COMMAND_FIELD = "command";

    /** Opérations exposées par MCP seul ; la CLI et REST n'ont pas d'équivalent. */
    public static final List<String> MCP_ONLY_OPERATIONS = List.of("find_symbol", "find_usages");

    private PublicOutputSchema() {
    }

    /** Clés attendues pour un record de {@link PublicOutput}, dans l'ordre de sérialisation. */
    public static List<String> keys(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /**
     * Compare un arbre JSON désérialisé ({@code Map}, {@code List}, scalaires) au contrat du record
     * {@code type}. Les clés de {@code topLevelExtras} sont admises en tête de l'objet racine seulement.
     *
     * @return les écarts trouvés, vide si le JSON respecte le contrat
     */
    public static List<String> divergences(Class<? extends Record> type, Object json, List<String> topLevelExtras) {
        List<String> divergences = new ArrayList<>();
        if (!(json instanceof Map<?, ?> map)) {
            divergences.add("$ : objet attendu pour " + type.getSimpleName());
            return divergences;
        }
        List<String> actual = map.keySet().stream().map(String::valueOf).toList();
        int extras = 0;
        while (extras < topLevelExtras.size() && extras < actual.size()
                && actual.get(extras).equals(topLevelExtras.get(extras))) {
            extras++;
        }
        walk(type, map, actual.subList(extras, actual.size()), "$", divergences);
        return divergences;
    }

    private static void walk(Class<?> type, Map<?, ?> json, List<String> actualKeys, String path, List<String> out) {
        List<String> expected = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
        if (!expected.equals(actualKeys)) {
            out.add(path + " : clés " + actualKeys + " au lieu de " + expected);
        }
        for (RecordComponent component : type.getRecordComponents()) {
            walkComponent(component, json.get(component.getName()), path + "." + component.getName(), out);
        }
    }

    private static void walkComponent(RecordComponent component, Object value, String path, List<String> out) {
        Class<?> raw = component.getType();
        if (raw.isRecord() && value instanceof Map<?, ?> nested) {
            walk(raw, nested, keysOf(nested), path, out);
            return;
        }
        if (!List.class.isAssignableFrom(raw) || !(value instanceof List<?> list)) {
            return;
        }
        Class<?> element = elementType(component.getGenericType());
        if (element == null || !element.isRecord()) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> nested) {
                walk(element, nested, keysOf(nested), path + "[]", out);
            } else {
                out.add(path + "[] : objet attendu");
            }
        }
    }

    private static List<String> keysOf(Map<?, ?> map) {
        return map.keySet().stream().map(String::valueOf).toList();
    }

    private static Class<?> elementType(Type generic) {
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        return null;
    }
}
