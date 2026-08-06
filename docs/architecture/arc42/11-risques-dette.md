# Section 11 — Risques et dette technique

Probabilité : **F** = faible, **M** = moyenne, **E** = élevée  
Impact : **F** = faible, **M** = moyen, **E** = élevé  
Exposition = Probabilité × Impact

## 11.1 Registre des risques

| ID | Risque | Prob. | Impact | Exp. | Statut | Mitigation / suivi |
|----|--------|-------|--------|------|--------|--------------------|
| R1 | **Scale SQLite lexical** — `LOWER(...) LIKE '%...%'` peut se dégrader sur très grands corpus | M | M | MM | Surveillance | Benchmark #23 avant FTS5/trigram/autre moteur |
| R2 | **Corruption SQLite** lors d'une coupure en écriture | F | E | FM | Accepté | transactions ACID, sauvegarde canonique, runbook recovery, Lucene reconstructible |
| R3 | **Race filesystem locale hostile** — remplacement d'un ancêtre/hard-link pendant le traitement | F | E | FM | Limite documentée | `ProjectPathGuard` + `SafeFileIO` + `NOFOLLOW_LINKS`; isolation OS uniquement si ce threat model devient requis |
| R4 | **`FileLock` sur filesystem réseau** | M | E | ME | Non supporté | `NEXUS_HOME` local requis pour la garantie inter-processus |
| R5 | **Provider Java non-coopératif** — ignore définitivement l'interruption | M | M | MM | Accepté / surveillé | worker daemon + timeout wall-clock ; isolation processus/circuit-breaker uniquement avec preuve opérationnelle |
| R6 | **Migration SQLite forward-only** | F | E | FM | Watch item | tester sur copie, backup avant upgrade, restore canonique en cas d'échec |
| R7 | **Glissement fonctionnel** — NEXUS devient orchestrateur/chatbot | F | E | FM | Maîtrisé | ADR-0001 + revues de frontière |
| R8 | **Dépendance Quarkus dans le cœur** | F | M | FM | Maîtrisé | reactor séparé, aucune dépendance Quarkus dans le core |
| R9 | **Dérive qualité de recherche** lors de nouveaux langages/rankers | M | M | MM | Watch item | baseline qualité + qualification avant intégration |
| R10 | **Compatibilité MCP SDK** lors d'une mise à jour | M | M | MM | Watch item | ADR-0016 + tests d'intégration MCP |
| R11 | **AI Skills Registry indisponible** | M | F | MF | Accepté | provider local/fallback ; registry opt-in |
| R12 | **Qualification post-Phase 6 non exécutée** | E | M | EM | **Clôturé** | hardening qualifié et intégré ; PR #24 également qualifiée Windows/Linux |
| R13 | **Snapshots externes obsolètes** après changement canonique | M | E | ME | **Clôturé** | invalidation des providers externes persistés sur changement SOURCE/TEST ; PR #24 / test de régression |
| R14 | **Index sémantique incompatible** avec état/modèle/dimensions/profil courant | M | E | ME | **Clôturé** | manifeste de provenance Lucene + rebuild sur mismatch + garde de recherche ; PR #24 |
| R15 | **Supply-chain publique / obligations tierces** | M | E | ME | Ouvert | issue #22 : notices tierces, vulnérabilités, scanning, actions immuables, couverture, SBOM release |

Le registre opérationnel détaillé est maintenu dans [`../risks/register.md`](../risks/register.md).

## 11.2 Dette technique identifiée

| ID | Dette | Impact | Effort | Priorité |
|----|-------|--------|--------|---------|
| D1 | **Sources historiques dans `src/`** — `core/pom.xml` utilise `sourceDirectory` | Faible | Moyen | Faible |
| D2 | **Pas de rollback de schéma SQLite** — migrations forward-only | Moyen | Faible | Moyen |
| D3 | **Sélection de skills principalement lexicale** | Moyen | Élevé | Faible |
| D4 | **Instructions utilisateur home non chargées** | Faible | Moyen | Faible |
| D5 | **Scale SQLite lexical non benchmarké** | Moyen potentiel | Élevé | Moyen — #23 |
| D6 | **Gates supply-chain incomplets** | Élevé pour distribution publique | Moyen | Haute — #22 |

## 11.3 Frontières de support

### Filesystem

NEXUS protège les chemins applicatifs contre les symlinks et borne les lectures, mais ne constitue pas un sandbox absolu contre un utilisateur local hostile modifiant activement l'arborescence pendant l'indexation.

### Verrouillage

La garantie de single-flight combine mutex JVM et `FileLock` OS et vise un `NEXUS_HOME` local. Un filesystem réseau doit faire l'objet d'une qualification séparée avant d'être déclaré supporté.

### Index dérivés

SQLite reste canonique. Un index sémantique sans provenance compatible n'est pas réutilisé ; un snapshot externe obsolète n'est pas conservé comme autorité courante.

## 11.4 Choix volontairement non adoptés

> Ces choix ne sont pas de la dette ; ils restent conditionnés à une mesure démontrant leur nécessité.

- Pas de Zoekt/OpenGrok/OpenSearch, index distribué, vector DB ou FTS supplémentaire sans benchmark.
- Pas de cache Git persistant ni de lifecycle Lucene partagé plus complexe sans mesure.
- Pas d'isolation processus/circuit-breaker systématique pour les providers sans incident réel démontrant le besoin.
