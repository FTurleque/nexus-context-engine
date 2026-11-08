package com.nexus.index;

import java.util.List;
import java.util.Set;

/**
 * Écritures canoniques d'une indexation (ADR-0051, DATA-0074). Les analyses sont mises en attente
 * par lots bornés, dans de courtes transactions qui ne rendent aucun fait visible ; {@link #publish}
 * les applique aux tables canoniques et publie la génération en une seule transaction. Fermer la
 * session sans la publier écarte les analyses en attente : la génération précédente reste celle
 * servie et aucun fait partiel n'est visible.
 */
public interface IndexWriteSession extends AutoCloseable {

    /** Met en attente les analyses d'un lot de fichiers ; ne modifie ni les faits canoniques ni la génération. */
    void stage(List<IndexedFileUpdate> updates);

    /**
     * Applique en une transaction les analyses en attente et les suppressions, puis incrémente la
     * génération s'il y a eu un changement. À appeler au plus une fois.
     */
    void publish(Set<String> removedPaths);

    /** Écarte les analyses en attente non publiées ; n'échoue jamais. */
    @Override
    void close();
}
