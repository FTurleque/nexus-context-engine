# Section 11 — Risques et dette technique

Probabilité : **F** = faible, **M** = moyenne, **E** = élevée  
Impact : **F** = faible, **M** = moyen, **E** = élevé

## 11.1 Registre des risques

| ID | Risque | Prob. | Impact | Statut | Mitigation / suivi |
|---|---|---:|---:|---|---|
| R1 | Scale SQLite lexical `%substring%` | M | M | Surveillance | benchmark avant FTS5/trigram/autre moteur |
| R2 | Corruption SQLite lors d'une coupure | F | E | Accepté | ACID, sauvegarde canonique, runbook recovery |
| R3 | Race filesystem locale hostile | F | E | Limite documentée | `ProjectPathGuard` + `SafeFileIO` + `NOFOLLOW_LINKS`; #52 |
| R4 | `FileLock` sur filesystem réseau | M | E | Non supporté sans qualification | `NEXUS_HOME` local ; #52 |
| R5 | Provider Java non coopératif | M | M | Watch item | timeout wall-clock ; isolation seulement sur cas réel ; #51 |
| R6 | Migration SQLite forward-only | F | E | Watch item | backup avant upgrade, restore canonique si nécessaire |
| R7 | Glissement fonctionnel vers orchestrateur/chatbot | F | E | Maîtrisé | frontières ADR + revues |
| R8 | Dépendance adaptateur dans le cœur | F | M | Maîtrisé | reactor séparé |
| R9 | Dérive qualité de recherche | M | M | Watch item | baseline + benchmark/qualification |
| R10 | Compatibilité MCP lors d'une mise à jour | M | M | Watch item | tests protocole/intégration |
| R11 | AI Skills Registry indisponible | M | F | Accepté | provider local/fallback |
| R13 | Snapshots externes obsolètes | M | E | **Clôturé** | invalidation sur changement SOURCE/TEST ; PR #24/#49 |
| R14 | Index sémantique incompatible | M | E | **Clôturé** | provenance Lucene + rebuild + garde de recherche |
| R15 | Supply-chain reactor/image incomplète | M | E | **Clôturé / renforcé** | PR #28 + PR #49 : JaCoCo, OSV reactor, CodeQL, Trivy, SBOM, attestations |
| R16 | Nouvelle dépendance à licence inhabituelle | F | E | Watch item | revue explicite ; #55 |
| R17 | Snapshot publié après mutation concurrente | M | E | **Clôturé** | revalidation canonique avant READY ; PR #49 |
| R18 | Exposition REST distante trop permissive | M | E | **Clôturé** | token robuste + roots + exposure mode ; PR #49 |
| R19 | Graphe/contexte fédéré non bornés en travail | M | E | **Clôturé** | projections SQL + budget de travail ; PR #49 |
| R20 | Recovery sémantique opérationnel incomplet | M | M | Watch item | Ollama/corruption Lucene ; #54 |
| R21 | Cache Git persistant complexe/invalide | M | M | Watch item | aucune adoption sans mesures + modèle d'invalidation ; #53 |
| R22 | Lifecycle Lucene partagé ajoute complexité/recovery | M | M | Watch item | benchmark + qualification crash/rebuild ; #50 |

Le registre opérationnel détaillé est maintenu dans [`../risks/register.md`](../risks/register.md).

## 11.2 Dette technique identifiée

| ID | Dette | Impact | Effort | Priorité |
|---|---|---|---|---|
| D1 | Sources historiques dans `src/`, référencées par `core/pom.xml` | Faible | Moyen | Faible |
| D2 | Pas de rollback de schéma SQLite | Moyen | Faible | Moyen |
| D3 | Sélection de skills principalement lexicale | Moyen | Élevé | Faible |
| D4 | Instructions utilisateur home non chargées | Faible | Moyen | Faible |
| D5 | Scale SQLite lexical | Moyen potentiel | Élevé | Conditionné au benchmark |
| D6 | Gates supply-chain incomplets | Élevé | Moyen | **Clôturé par PR #28/#49** |
| D7 | Recovery Ollama/Lucene physique | Moyen | Moyen | Watch item #54 |

## 11.3 Frontières de support

### Filesystem

NEXUS protège les chemins applicatifs contre les symlinks et borne les lectures, mais ne constitue pas un sandbox absolu contre un utilisateur local hostile modifiant activement l'arborescence. Le support réseau exige une qualification dédiée (#52).

### Verrouillage et cohérence

La garantie de single-flight combine mutex JVM et `FileLock` OS sur `NEXUS_HOME` local. Le snapshot canonique est revalidé avant publication ; une mutation concurrente détectée fait échouer l'indexation plutôt que de publier un état mixte.

### Index dérivés

SQLite reste canonique. Un index sémantique sans provenance compatible n'est pas réutilisé ; les snapshots externes obsolètes sont invalidés.

### REST

Loopback est la configuration locale sûre. Hors loopback, token robuste, allowlist de racines et mode d'exposition explicite sont obligatoires. `reverse-proxy-https` ou `direct-https` sont les modes distants admis ; `loopback-forward` est réservé au runtime Docker publié sur loopback côté hôte.

### Supply-chain

Le reactor et l'image Docker ont des gates distincts et complémentaires. OSV, CodeQL, Trivy, SBOM, notices tierces et attestations ne remplacent pas la revue juridique contextuelle d'une nouvelle dépendance inhabituelle.

## 11.4 Choix volontairement non adoptés

Ces choix ne sont pas de la dette ; ils restent conditionnés à une preuve :

- pas de Zoekt/OpenGrok/OpenSearch, index distribué, vector DB ou FTS supplémentaire sans benchmark ;
- pas de cache Git persistant sans mesures multi-repository ;
- pas de lifecycle Lucene partagé sans benchmark + qualification recovery ;
- pas d'isolation processus systématique des providers externes sans cas réel non coopératif.

## 11.5 Qualification récente

PR #49 : head exact `4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9` — NEXUS CI, Scale Benchmark, Windows Installer, Docker Distribution, CodeQL et OSV-Scanner PASS.

PR #61 : head exact `ba91be044a600d2396e0939fc154848dc47f6310` — NEXUS CI, CodeQL et OSV-Scanner PASS ; merge `660ca9f07a23950d2a5284605531524372331bc5`.
