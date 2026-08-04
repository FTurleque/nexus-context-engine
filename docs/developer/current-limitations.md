# Limites actuelles et dette de consolidation

> Phase 6 : intégrée dans `main` via la PR #15 ; issue #13 clôturée.
> Cycle courant : issue #16, branche `hardening/post-phase6-audit` issue de `develop`.
> Les changements post-Phase 6 sont implémentés mais **non qualifiés par CI avant validation explicite**.

Ce registre conserve les constats F01–F18 issus de l'audit de juillet 2026 et ajoute les constats de l'audit post-Phase 6. Les ADR acceptés restent historiques et ne sont pas réécrits.

## Registre Phase 6

| ID | Sujet | Traitement Phase 6 | État |
|---|---|---|---|
| F01 | top-K fédéré sous-rempli | sur-récupération bornée avant diversification + test de régression | fermé |
| F02 | gate `READY` non uniforme | gate applicatif commun pour search/context/symbols/usages/fédération/MINOS | fermé |
| F03 | fenêtre SQLite/index dérivés | lecture interdite hors `READY`, recovery non-READY par rebuild complet, génération canonique | fermé |
| F04 | scan complet recherche symbolique | préfiltrage SQLite borné avant fuzzy Java | fermé |
| F05 | `findSymbols`/`findUsages` projet-wide | requêtes repository SQL avec `LIMIT` | fermé |
| F06 | graphe reconstruit par requête | cache dérivé par génération d'index | fermé |
| F07 | composition CLI dupliquée | CLI déléguée entièrement à `NexusApplication` | fermé |
| F08 | drift Maven | reactor parent, dependency/plugin management et Enforcer communs | fermé |
| F09 | coupling Skills Registry | providers local/registry composés indépendamment | fermé |
| F10 | absence single-flight | verrou in-process par `projectId` | fermé en Phase 6, renforcé post-Phase 6 |
| F11 | fichiers non bornés | plafond configurable avant hash/lecture, 8 MiB par défaut, diagnostics | fermé, renforcé post-Phase 6 |
| F12 | MINOS full walk | chemin applicatif validé contre `indexed_files` canonique | fermé |
| F13 | lifecycle Lucene par opération | **pas de changement sans preuve de benchmark** | différé sur preuve |
| F14 | opt-in sémantique non uniforme | configuration environnement commune CLI/REST/MCP, désactivée par défaut | fermé |
| F15 | fédération non exposée | CLI + REST + MCP exposent recherche fédérée | fermé |
| F16 | coûts Git/embeddings | embeddings batchables + batch Ollama ; aucun cache Git sans mesure | partiellement optimisé, watch item Git |
| F17 | absence ContextBundle fédéré | budget global, provenance, fairness, déduplication et sources natives projet-locales | fermé, renforcé post-Phase 6 |
| F18 | distribution orientée checkout | version 0.2.0, wrapper, ZIP autonome, SHA-256, SBOM, runbook recovery | fermé |

## Audit post-Phase 6 — issue #16

| ID | Constat | Traitement implémenté | État actuel |
|---|---|---|---|
| H01 | lecture possible d'une cible extérieure via symlink | `ProjectPathGuard`, racine canonique, refus des symlinks sous le repository, ouverture finale `NOFOLLOW_LINKS`, flux et lectures bornés | implémenté, validation en attente |
| H02 | single-flight limité à une JVM | mutex local + `FileLock` OS par `projectId` sous `NEXUS_HOME/locks` ; indexations et import MINOS sérialisés | implémenté, validation en attente |
| H03 | timeout provider potentiellement bloquant et importers non bornés | `ExternalTaskRunner` daemon non bloquant ; même enveloppe pour importers et providers | implémenté, validation en attente |
| H04 | readiness mélangeait santé du service et disponibilité des projets | liveness explicite, readiness service, `allProjectsReady`, `degraded`, gate projet `READY` séparé | implémenté, validation en attente |
| H05 | budget fédéré perdu après projet clairsemé ou déduplication | fair floor, overfetch local borné, refill global et préservation de l'ordre de ranking local | implémenté, validation en attente |
| H06 | exposition REST non-loopback possible sans authentification | fail-fast hors loopback sans token + Bearer auth si token configuré | implémenté, validation en attente |
| H07 | UUID inconnu réinterprété comme nom | séparation parse UUID / résolution par nom | implémenté, validation en attente |
| H08 | map des mutex JVM conservée indéfiniment | slots référencés et suppression quand plus aucun utilisateur | implémenté, validation en attente |
| H09 | coût SQLite des recherches `%substring%` à grande échelle | aucun changement prématuré ; benchmark requis avant FTS5/trigram/autre moteur | watch item mesuré |

## Invariants renforcés

### Frontière filesystem

La racine projet est canonicalisée une fois. Les chemins lus par le scanner, les ignore files, les instructions/références et les Agent Skills sont validés par rapport à cette frontière.

La politique post-Phase 6 est volontairement conservatrice : **un lien symbolique présent sous la racine du repository n'est pas suivi**. Une racine fournie elle-même via symlink peut être résolue vers sa cible canonique ; cette cible devient ensuite la frontière de confiance.

La protection est en deux couches :

1. `ProjectPathGuard` valide le confinement réel, le type de l'entrée et l'absence de composants symlinkés sous la racine ;
2. `SafeFileIO` ouvre le composant final avec `NOFOLLOW_LINKS` et borne effectivement le nombre d'octets consommés.

`NEXUS_MAX_FILE_SIZE_BYTES` est une politique commune : scanner, hash et lectures de contenu utilisent le même plafond de production. Les `.gitignore`, `.nexusignore`, instructions, références et `SKILL.md` héritent aussi des flux bornés, même lorsqu'ils sont découverts hors du scanner générique.

Cela ferme notamment le remplacement concurrent du **fichier final** par un symlink ou un fichier devenu beaucoup plus gros entre validation et consommation.

**Limites résiduelles du modèle Java portable :** NEXUS ne prétend pas fournir un sandbox filesystem contre un acteur local qui modifie agressivement l'arborescence pendant l'indexation. Un remplacement concurrent d'un **répertoire ancêtre** après validation, ou un hard-link créé localement vers un inode extérieur au modèle logique du repository, ne peut pas être éliminé complètement avec les seules primitives `Path` portables utilisées ici. Une défense absolue nécessiterait une marche par handles/`SecureDirectoryStream` disponible selon le filesystem, ou une isolation de processus/OS. Git ne versionne pas les hard-links en tant que tels, ce risque concerne donc surtout un repository local activement hostile pendant son traitement.

Le provider JDT LS opt-in réutilise le scanner sécurisé pour déterminer les fichiers, mais sa lecture interne de document reste une surface TOCTOU plus étroite si le filesystem est modifié activement après le scan. Les repositories non fiables ne doivent pas être mutés concurremment pendant `--deep-java` tant qu'une isolation plus forte n'est pas justifiée.

### Cohérence et concurrence d'index

SQLite reste la source canonique. Lucene lexical et sémantique restent dérivés et reconstructibles. Une lecture dépendant d'un index exige `IndexStatus.READY` au niveau de la façade applicative.

Une panne pendant l'indexation conduit normalement à `FAILED`. Un crash brutal peut laisser `INDEXING`; cet état ne constitue pas un lease persistant et la prochaine indexation force un rebuild complet.

La façade de production utilise désormais deux niveaux de single-flight :

1. mutex JVM par projet ;
2. verrou fichier OS par projet sous `NEXUS_HOME/locks`.

Le verrou OS couvre les indexations/rebuilds, les providers de code intelligence et l'import MINOS piloté par la façade. Le fichier de lock peut rester présent après libération ; c'est le `FileLock` OS, et non la présence du fichier, qui représente la propriété exclusive.

La garantie inter-processus suppose un filesystem local respectant correctement les locks de fichiers. Un `NEXUS_HOME` placé sur un filesystem réseau exotique doit être qualifié séparément avant d'être considéré supporté.

### Ressources et providers

- `NEXUS_MAX_FILE_SIZE_BYTES` : 8 MiB par défaut ;
- `NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS` : 180 s par défaut ;
- importers et providers passent par la même enveloppe de temps globale ;
- le runner annule/interrompt le worker et ne bloque pas sur la fermeture d'un executor récalcitrant ;
- les durées importer/provider restent structurées dans `IndexingReport`.

**Risque résiduel connu :** une intégration Java tierce qui ignore définitivement l'interruption peut continuer sur son thread daemon après le timeout. Elle ne bloque plus l'indexation ni l'arrêt de la JVM, mais des timeouts répétés d'un provider malveillant pourraient accumuler des workers jusqu'au redémarrage. Les providers pilotant un processus externe doivent donc conserver leur propre politique de destruction ; JDT LS dispose déjà d'un cleanup de session/processus.

### Readiness

Les notions suivantes sont séparées :

- **liveness** : le processus Quarkus est vivant ;
- **readiness service** : la façade peut accéder à ses dépendances de base et accepter les opérations de gestion ;
- **project readiness** : un projet individuel doit être `READY` avant toute lecture dépendant de son index ;
- **degraded** : au moins un projet est en `FAILED` ;
- **allProjectsReady** : tous les projets enregistrés sont `READY`.

Un service peut donc rester opérationnel pendant l'indexation d'un projet sans faire croire que ce projet est consultable.

### Fédération

Le contexte fédéré conserve un fair floor déterministe par projet, mais les builders locaux peuvent produire un pool de candidats borné par le budget global. La première passe ne consomme qu'un **préfixe** du ranking local de chaque projet ; dès que le prochain candidat ne tient plus dans son fair floor, le suffixe est différé. Après déduplication, ces suffixes peuvent réutiliser les tokens rendus disponibles sans faire passer un candidat local moins bien classé devant un candidat différé plus pertinent.

Les métadonnées exposent notamment `refillTokens`, `refillItems`, `unusedTokens`, allocation/sélection par projet, starvation et déduplication.

Le coût de construction local peut augmenter avec le nombre de projets car chaque builder peut produire un pool jusqu'au budget fédéré global. Ce coût reste borné, mais doit être mesuré si les portfolios deviennent très larges.

### Sécurité REST

Le host par défaut reste `127.0.0.1`.

- loopback + aucun token : fonctionnement local historique ;
- token configuré : les ressources REST JAX-RS exigent `Authorization: Bearer ...` ;
- host non-loopback + aucun token : bootstrap refusé ;
- host non-loopback + token : exposition autorisée avec Bearer auth.

Variable : `NEXUS_REST_API_TOKEN` ; alternative JVM : `-Dnexus.rest.api-token=...`.

Les endpoints techniques fournis directement par Quarkus (health/métriques) ne doivent pas être assimilés à des endpoints de contexte applicatif ; leur exposition doit rester traitée comme un choix d'exploitation.

## Watch items conservés

1. **Recherche SQLite substring / scale** : mesurer 10k / 100k / 500k / 1M symboles avant de choisir FTS5, n-gram/trigram ou un moteur supplémentaire.
2. **Lifecycle Lucene persistant** : `SearcherManager`/writer partagé uniquement si un benchmark REST/MCP démontre un gain matériel.
3. **Cache Git** : aucun cache persistant sans mesure multi-repository.
4. **Moteur externe** : Zoekt/OpenGrok/OpenSearch uniquement si les requêtes bornées et le cache graphe ne suffisent plus.
5. **Vector DB** : non justifiée par le corpus actuel.
6. **Transport MCP distant** : hors périmètre ; stdio local reste la surface prévue.
7. **Worker externe récalcitrant** : envisager isolation processus/circuit-breaker si de futurs providers Java non coopératifs deviennent une réalité opérationnelle.
8. **Filesystem activement hostile / TOCTOU ancêtre / hard-links** : isolation par handles ou processus seulement si NEXUS doit accepter ce threat model.
9. **JDT LS et mutation concurrente du repository** : qualifier/renforcer si `--deep-java` doit devenir sûr contre un workspace local hostile en mouvement.
10. **Portfolios très larges** : mesurer le coût d'overfetch du contexte fédéré avant toute stratégie adaptative plus complexe.

## Gate de validation post-Phase 6

Aucun PASS n'est déclaré à ce stade pour l'issue #16. Conformément au gate demandé :

1. revue statique complète de la branche ;
2. validation explicite du propriétaire ;
3. seulement ensuite intégration/qualification autorisée selon la stratégie convenue ;
4. qualification exacte du commit livré, incluant build reactor, tests, packaging et scénarios de concurrence/sécurité ;
5. mise à jour de ce registre avec les preuves réelles.

Voir aussi : `docs/developer/release-and-recovery.md`, `docs/roadmap.md` et l'issue #16.
