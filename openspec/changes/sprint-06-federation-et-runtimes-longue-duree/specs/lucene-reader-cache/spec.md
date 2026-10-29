# Spec Delta

## Purpose

Définit l'usage du cache de readers Lucene des runtimes longue durée pour les lectures de métadonnées d'index, afin qu'aucune requête n'ouvre un index complet pour lire ses métadonnées de commit.

## ADDED Requirements

### Requirement: La compatibilité sémantique est lue sur le reader en cache
La vérification de compatibilité d'un index sémantique persistant SHALL lire les métadonnées de commit sur le reader Lucene en cache lorsqu'il existe, et SHALL NOT ouvrir de `DirectoryReader` supplémentaire par requête.

#### Scenario: Recherches sémantiques successives
- **WHEN** deux recherches sémantiques successives portent sur le même projet dans un runtime longue durée
- **THEN** aucun `DirectoryReader` n'est ouvert pour la vérification de compatibilité de la seconde recherche

#### Scenario: Résultat inchangé
- **WHEN** l'index sémantique a été construit avec une provenance différente de la provenance attendue
- **THEN** la vérification rend « incompatible », comme avant le changement

### Requirement: La présence de l'index lexical est lue sans ouvrir l'index
La vérification de présence d'un index lexical persistant, utilisée par la sonde de readiness, SHALL lire les métadonnées de commit sur le reader en cache ou, à défaut, sur le dernier commit sans charger les segments.

#### Scenario: Sonde de readiness répétée
- **WHEN** la sonde de readiness est appelée deux fois sur un portefeuille de projets indexés
- **THEN** le second appel n'ouvre aucun `DirectoryReader`

#### Scenario: Index absent
- **WHEN** le répertoire d'index lexical d'un projet n'existe pas
- **THEN** la vérification de présence rend « absent » sans erreur
