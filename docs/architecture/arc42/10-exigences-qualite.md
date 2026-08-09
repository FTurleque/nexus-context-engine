# Section 10 — Exigences de qualité

## 10.1 Priorités

| Priorité | Attribut | Objectif mesurable |
|---|---|---|
| 1 | **Correctness** | résultats, budgets et états publiés déterministes et cohérents |
| 2 | **Sécurité locale/distante** | confinement filesystem local et exposition REST distante fail-closed |
| 3 | **Indépendance fournisseur** | aucune dépendance obligatoire vers LLM/IDE/orchestrateur |
| 4 | **Opérabilité** | health/readiness, métriques, recovery documenté, distributions qualifiées |
| 5 | **Évolutivité** | nouveaux providers/adaptateurs sans casser le cœur |
| 6 | **Supply-chain** | vulnérabilités, licences, SBOM et image Docker contrôlés avant intégration/publication |

## 10.2 Scénarios de qualité actifs

### QS-01 — Déterminisme du ranking

Deux appels identiques sans mutation du repository doivent produire les mêmes candidats, ordre, scores et budget consommé. Tolérance : 0 divergence non expliquée.

### QS-02 — Confinement filesystem

Un chemin sortant de la racine ou passant par un symlink ne doit jamais provoquer une lecture externe. `ProjectPathGuard`, `SafeFileIO` et `NOFOLLOW_LINKS` couvrent cette frontière.

### QS-03 — Exclusion mutuelle inter-processus

Deux processus partageant le même `NEXUS_HOME` local et le même projet ne doivent pas publier deux mutations concurrentes. Mutex JVM + `FileLock` OS ; 0 corruption tolérée.

### QS-04 — Budget de contexte

`ContextBundle.estimatedTokens` ne dépasse jamais le budget final. Pour la fédération, le coût de préparation est également borné par un budget de travail distinct.

### QS-05 — Démarrage/distribution

CLI, ZIP autonome et distribution Windows doivent démarrer sans checkout Maven sur la machine cible ; le setup Windows embarque son runtime Java.

### QS-06 — Provider/importer en timeout

Une dépendance externe lente ne doit pas bloquer indéfiniment l'indexation. `ExternalTaskRunner` borne le wall-clock. Les workers ignorant définitivement l'interruption restent un watch item #51.

### QS-07 — Qualité de recherche

La baseline historique conserve hit@3 et MRR@3 comme métriques de non-régression ; les optimisations de scale doivent être justifiées par benchmark.

### QS-08 — Ajout d'un provider

Un nouveau provider doit rester optionnel, borné, sans couplage imposé aux providers existants et sans introduire de dépendance adaptateur dans le cœur.

### QS-09 — Recovery canonique

SQLite reste l'autorité. Un état persistant non-READY impose un rebuild complet ; Lucene reste reconstructible. La corruption physique Lucene/Ollama indisponible est suivie par #54.

### QS-10 — Fédération bornée

Fair floor, déduplication, refill et budget de travail doivent éviter starvation et travail préparatoire non borné.

### QS-11 — Readiness sans projet

Aucun projet enregistré ne doit pas être interprété comme « tous les projets sont READY ».

### QS-12 — Mutation repository pendant indexation

Le snapshot canonique doit être revalidé avant publication. Si le repository change pendant l'opération, NEXUS échoue fail-closed au lieu de publier un état mixte.

### QS-13 — Graphe borné

Le ranking graphe ne doit pas nécessiter de matérialiser tous les symboles/relations du projet ; projections/voisinages SQL bornés obligatoires.

### QS-14 — Limite de résultats cross-surface

CLI, REST et MCP doivent partager la même limite maximale via `ResultLimitPolicy`.

### QS-15 — Sécurité REST distante

Une écoute hors loopback est refusée si l'un des éléments suivants manque ou est invalide :

- token robuste ;
- allowlist de racines ;
- mode d'exposition explicite ;
- mode HTTPS admissible.

`loopback-forward` n'est accepté que pour le runtime Docker publié côté hôte sur loopback.

### QS-16 — Supply-chain reactor

- JaCoCo core < 70 % lignes ou < 50 % branches ⇒ échec ;
- vulnérabilité nouvelle en PR ⇒ OSV delta en échec ;
- vulnérabilité dans le SBOM CycloneDX agrégé ⇒ OSV gate en échec ;
- dépendance distribuée sans licence exploitable ⇒ échec ;
- Action contrôlée non épinglée à un SHA ⇒ non conforme.

### QS-17 — Supply-chain image Docker

- vulnérabilité HIGH/CRITICAL corrigible ⇒ gate Trivy en échec ;
- image publiée depuis `main` ⇒ SBOM et provenance attestés sur le digest publié.

## 10.3 Watch items qualité

- #50 lifecycle Lucene persistant ;
- #51 provider externe non coopératif ;
- #52 filesystem hostile/réseau ;
- #53 cache Git persistant ;
- #54 recovery sémantique/Ollama/Lucene ;
- #55 revue juridique des dépendances inhabituelles.

Ces sujets ne justifient pas un changement d'architecture sans benchmark, fixture ou incident démontrant le besoin.

## 10.4 Baselines et preuves

Baseline historique : 2 104 fichiers, 10 878 symboles, 10 087 relations, indexation complète 8 818 ms, fédération p50/p95 133/304 ms, contexte p50/p95 48/206 ms, hit@3=1.0, MRR@3=1.0.

PR #49, head exact `4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9` : NEXUS CI, Scale Benchmark, Windows Installer, Docker Distribution, CodeQL et OSV-Scanner PASS.

PR #61, head exact `ba91be044a600d2396e0939fc154848dc47f6310` : NEXUS CI, CodeQL et OSV-Scanner PASS.

Voir également [`../quality/scenarios.md`](../quality/scenarios.md).
