package com.nexus.search;

import com.nexus.project.FederationDeadline;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Contexte d'une requête de recherche, partagé par les stratégies de tous les
 * projets d'une même requête fédérée.
 *
 * <p>Il mutualise ce qui ne dépend que de la requête (par exemple son vecteur
 * d'embedding) afin que ce coût ne soit pas payé par projet. Sa portée est la
 * requête : il n'est ni conservé ni partagé entre requêtes, donc il n'ajoute
 * aucun état mémoire à borner. Les valeurs sont calculées au premier besoin,
 * au plus une fois par clé.</p>
 */
public final class SearchQueryContext {

    /** Calcul d'une valeur partagée, autorisé à échouer par {@link IOException}. */
    @FunctionalInterface
    public interface Computation<T> {
        T compute() throws IOException;
    }

    private final Map<String, Object> values = new HashMap<>();
    private final FederationDeadline deadline;

    private SearchQueryContext(FederationDeadline deadline) {
        this.deadline = deadline;
    }

    /** Crée un contexte sans échéance pour une requête mono-projet. */
    public static SearchQueryContext create() {
        return new SearchQueryContext(null);
    }

    /** Crée le contexte d'une requête fédérée soumise à l'échéance globale (BE-0081). */
    public static SearchQueryContext create(FederationDeadline deadline) {
        return new SearchQueryContext(Objects.requireNonNull(deadline, "deadline"));
    }

    /**
     * Temps restant avant l'échéance globale, vide sans échéance. Il plafonne les
     * appels bornables, comme l'embedding de la requête.
     */
    public Optional<Duration> remainingTime() {
        return deadline == null ? Optional.empty() : Optional.of(deadline.remaining());
    }

    /**
     * Rend la valeur associée à la clé, en la calculant au premier appel. Un
     * calcul qui échoue n'est pas mémorisé : l'exception se propage à l'appelant.
     * La clé doit inclure toute entrée dont dépend la valeur (par exemple le texte
     * de la requête) : une valeur déjà calculée est rendue pour toute clé égale.
     */
    public synchronized <T> T shared(String key, Class<T> type, Computation<T> computation)
            throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(computation, "computation");
        Object existing = values.get(key);
        if (existing != null) {
            return type.cast(existing);
        }
        T computed = Objects.requireNonNull(computation.compute(), "computation result");
        values.put(key, computed);
        return computed;
    }
}
