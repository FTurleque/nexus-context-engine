# Section 10 — Exigences de qualité

## 10.1 Priorités

| Priorité | Attribut | Objectif mesurable |
|---|---|---|
| 1 | **Correctness** | résultats, budgets et états publiés déterministes et cohérents |
| 2 | **Sécurité locale/distante** | confinement filesystem, secrets, transports externes et REST fail-closed |
| 3 | **Résilience bornée** | travail externe, découverte, recherche et protocoles bornés avant épuisement de ressources |
| 4 | **Indépendance fournisseur** | aucune dépendance obligatoire vers LLM/IDE/orchestrateur |
| 5 | **Opérabilité** | health/readiness, métriques, recovery documenté, distributions qualifiées |
| 6 | **Supply-chain** | vulnérabilités, licences, SBOM et image Docker contrôlés avant intégration/publication |

## 10.2 Scénarios de qualité actifs

### QS-01 — Déterminisme du ranking

Deux appels identiques sans mutation du repository doivent produire les mêmes candidats, ordre, scores et budget consommé. Tolérance : 0 divergence non expliquée.

### QS-02 — Confinement filesystem et stockage

Un chemin sortant de la racine ou passant par un symlink sur une frontière durcie ne doit pas provoquer une lecture externe. Sur POSIX, le stockage NEXUS doit rester privé (`0700` répertoires, `0600` SQLite). Sur Windows, les ACL natives ne doivent pas être remplacées destructivement.

### QS-03 — Exclusion mutuelle inter-processus

Deux processus partageant le même `NEXUS_HOME` local et le même projet ne doivent pas publier deux mutations concurrentes. Mutex JVM + `FileLock` OS ; 0 corruption tolérée.

### QS-04 — Budget de contexte et découverte

`ContextBundle.estimatedTokens` ne dépasse jamais le budget final. La découverte native consomme en plus `ContextDiscoveryBudget` (visites, candidats, octets, deadline) avant la sélection. Pour la fédération, le travail préparatoire est également borné.

### QS-05 — Démarrage/distribution

CLI, ZIP autonome et distribution Windows doivent démarrer sans checkout Maven sur la machine cible ; le setup Windows embarque son runtime Java.

### QS-06 — Provider/importer en timeout ou non coopératif

Une dépendance externe lente ne doit pas bloquer indéfiniment l'appelant. `ExternalTaskRunner` borne le wall-clock et limite à **8 tâches externes réellement actives** à l'échelle JVM. Un provider explicitement demandé qui échoue fait échouer l'indexation et place le projet en `FAILED` ; il n'est pas silencieusement transformé en résultat `READY` dégradé.

Le cas d'un provider ignorant durablement l'interruption reste le watch item #51 pour une isolation processus plus forte.

### QS-07 — Framing JDT LS hostile/défectueux

Avant allocation/accumulation, NEXUS doit imposer :

```text
message       <= 16 MiB
headers       <= 64 KiB
header line   <= 8 KiB
pending queue <= 256 messages
```

Framing invalide/tronqué ou queue saturée ⇒ échec fermé de la session.

### QS-08 — Qualité et scale de recherche

Les optimisations doivent être justifiées par benchmark. Une requête Lucene à forte cardinalité est limitée à **128 termes analysés uniques** avant expansion multi-champs afin de ne pas dépasser le budget de clauses.

### QS-09 — Ajout d'un provider

Un nouveau provider doit rester optionnel, borné, sans couplage imposé aux providers existants et sans introduire de dépendance adaptateur dans le cœur.

### QS-10 — Recovery canonique

SQLite reste l'autorité. Un état persistant non-READY impose le chemin de reconstruction prévu ; Lucene reste reconstructible. La corruption physique Lucene/Ollama indisponible reste suivie par #54.

### QS-11 — Fédération bornée et fail-fast

Maximum 100 projets uniques, validé avant résolution/readiness. Fair floor, déduplication, refill et budget de travail doivent éviter starvation et travail préparatoire non borné.

### QS-12 — Mutation repository pendant indexation

Le snapshot canonique doit être revalidé avant publication. Si le repository change pendant l'opération, NEXUS échoue fail-closed au lieu de publier un état mixte.

### QS-13 — Graphe et repositories ciblés

Le ranking graphe ne doit pas nécessiter de matérialiser tous les symboles/relations du projet ; projections/voisinages et recherches symbole/usages sont ciblés côté repository.

### QS-14 — Limites cross-surface et contraintes

CLI, REST et MCP partagent les politiques centrales de limites. Les endpoints fédérés REST ne doivent pas contourner `ResultLimitPolicy`/`ContextBudgetPolicy`.

Une map `constraints` non vide doit être rejetée explicitement tant qu'aucune sémantique de contrainte n'est implémentée.

### QS-15 — Sécurité REST distante et management

Une écoute API hors loopback est refusée si auth, allowlist de racines ou transport effectif sont insuffisants. Le bearer token doit être généré par CSPRNG et satisfaire le gate structurel NEXUS ; ce gate rejette les chaînes manifestement faibles sans prétendre mesurer leur entropie cryptographique.

Health/metrics doivent rester sur le listener management `127.0.0.1:9000`; `/q/*` doit retourner 404 sur le listener applicatif. Le reverse proxy métier ne doit pas publier le listener management.

### QS-16 — Transport sémantique et secrets

Un endpoint Ollama distant doit utiliser HTTPS par défaut. HTTP distant exige `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true`; une URI contenant des credentials est refusée.

Les secrets à forte confiance sont redigés avant embeddings et fragments de contexte. Le profil `content-v2` force la reconstruction d'un ancien index sémantique incompatible.

### QS-17 — Supply-chain reactor

- JaCoCo core < 70 % lignes ou < 50 % branches ⇒ échec ;
- vulnérabilité nouvelle en PR ⇒ OSV delta en échec ;
- vulnérabilité dans le SBOM CycloneDX agrégé ⇒ OSV gate en échec ;
- dépendance distribuée sans licence exploitable ⇒ échec ;
- Action contrôlée non épinglée à un SHA ⇒ non conforme ;
- dérive d'un contrat documentaire machine-vérifiable ⇒ NEXUS CI en échec.

### QS-18 — Supply-chain image Docker

- vulnérabilité HIGH/CRITICAL corrigible ⇒ gate Trivy en échec ;
- image publiée depuis `main` ⇒ SBOM et provenance attestés sur le digest publié ;
- l'image publiée est l'image exacte déjà qualifiée, sans rebuild dans `release.yml`.

### QS-19 — Gouvernance `develop`

Le ruleset GitHub actif `Protect main & develop` satisfait NXA3-14 / #130 : pull request obligatoire, suppression/non-fast-forward interdits et sept checks permanents requis. Le code et les workflows restent une défense en profondeur mais ne remplacent pas ce contrôle repository-admin. `strict_required_status_checks_policy=false` est le hardening résiduel : la remise à jour de la PR avec sa base n'est pas encore imposée avant merge.

## 10.3 Watch items qualité

- #50 lifecycle Lucene persistant ;
- #51 isolation plus forte d'un provider externe réellement non coopératif ;
- #52 filesystem hostile/réseau ;
- #53 cache Git persistant ;
- #54 recovery sémantique/Ollama/Lucene physique ;
- #55 revue juridique des dépendances inhabituelles ;
- mode repository-admin strict « branch up to date before merge ».

Ces sujets ne justifient pas un changement d'architecture sans benchmark, fixture ou incident démontrant le besoin, à l'exception des réglages de gouvernance explicitement décidés par la politique du dépôt.

## 10.4 Baselines et preuve

Les métriques historiques restent utiles pour comparer les régressions (par exemple hit@3, MRR@3, temps d'indexation et latences). Elles sont conservées dans les documents d'itération/benchmark dédiés.

**Elles ne constituent pas une preuve de qualification de l'état courant.** Pour un merge, une release ou une promotion, utiliser uniquement les checks attachés au **SHA exact** concerné.

Voir également [`../quality/scenarios.md`](../quality/scenarios.md), [`../../developer/current-limitations.md`](../../developer/current-limitations.md) et [`../../developer/ci-and-supply-chain.md`](../../developer/ci-and-supply-chain.md).
