---
paths:
  - "core/**"
  - "adapters/**"
---

# Revue de code

Complète le superviseur global (sécurité générique, complexité, duplication, nommage) : seuls les points propres à NEXUS sont listés.

- Java 21 sans `--enable-preview` ni `jdk.incubator.vector` ; aucune annotation CDI/Spring/Quarkus ni dépendance vers `adapters/*` dans `core`.
- Déterminisme : tri explicite avec départage stable (chemin repository, puis identifiant) ; jamais l'ordre d'itération d'un `HashMap`/`HashSet` en sortie ; toute exclusion ou troncature produit un diagnostic.
- Bornes avant allocation, via les politiques existantes (`ContextDiscoveryBudget`, `FederatedScopePolicy.MAX_PROJECTS`, 128 termes Lucene) ; pas de nouvelle constante ad hoc.
- Adaptateurs minces : ni logique métier ni ranking dans `adapters/*` ; parité CLI/REST/MCP des limites et erreurs ; stdout MCP réservé au protocole.
- Zone critique fichiers : toute lecture de projet passe par `ProjectPathGuard` + `SafeFileIO` (ni symlink ni chemin hors repository) ; sorties publiques en chemins relatifs via `PublicContextPolicy`/`PublicDiagnosticPolicy`.
- Zone critique secrets : la redaction précède embedding, comptage de tokens et sélection ; modifier `SensitiveContentRedactor`/`SecretAssignmentScanner` impose version de politique, migration, profil `content-vN`, doc et contrats.
- Zone critique SQLite : `PreparedStatement` paramétré uniquement (`json_each(?)` pour les listes), lots de 128 lignes au plus, migrations `Vnnn` append-only, un index dérivé n'est jamais une vérité.
- Zone critique REST : ne pas affaiblir `NexusRestExposureGuard` (fail-closed hors loopback) ni durcir hôte ou port en dur.
- Pas de `System.out`/`printStackTrace` dans `core` (`System.Logger`) ; messages utilisateur en français, identifiants en anglais.
- Tests : `@TempDir` + `NexusPaths` dédié, aucune assertion sur l'horloge ou l'ordonnancement, valables sous Windows et Linux, borne testée à N et N+1 ; ne jamais abaisser un budget ou un seuil pour masquer une régression.
- Ne jamais neutraliser un contrôle (`@SuppressWarnings`, `@Disabled`, `NOSONAR`) pour faire disparaître un constat.
- Documentation courante alignée sur le code et les contrats CI ; ne pas réécrire un ADR accepté ni un document daté.
