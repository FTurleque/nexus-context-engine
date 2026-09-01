# Documentation d'architecture — NEXUS Context Engine

Ce répertoire contient la documentation arc42, les risques et les décisions d'architecture. Les ADR restent historiques/append-only ; les synthèses courantes doivent suivre le code versionné.

## Branches

```text
develop = intégration et qualification
main    = release
```

La protection effective de `develop` est une règle GitHub de repository et non un comportement du runtime. Voir [`../developer/branch-governance.md`](../developer/branch-governance.md).

## Sources primaires

| Source | Rôle |
|---|---|
| `docs/architecture.md` | synthèse courante |
| `docs/architecture/arc42/` | vues arc42 courantes |
| `docs/adr/` | historique décisionnel |
| `docs/roadmap.md` | état/travail restant |
| `docs/developer/` | contrats techniques détaillés |
| code + workflows | autorité exécutable |

## Frontières actives

### Baseline NXA3

- SQLite canonique ; Lucene/intelligence externe dérivés.
- V005 impose les plages de symboles valides au niveau base.
- filesystem projet durci par `ProjectPathGuard` ; SCIP/skills/customisations concernés utilisent la frontière commune.
- découverte native bornée avant sélection de tokens.
- portée fédérée <= 100 uniques validée avant résolution/readiness.
- Git local/read-only avec patch à capacité fixe.
- REST distant fail-closed sur auth, roots et transport TLS effectif.
- exact-head pour les gates pré-merge.
- image Docker construite une fois, qualifiée puis publiée sans rebuild.
- Maven/JDT LS contrôlés par ancres d'intégrité versionnées.

### Baseline NXA4

- management Quarkus isolé du listener applicatif sur `127.0.0.1:9000`.
- frames/backlog JDT LS bornés et concurrence externe limitée à 8 workers actifs.
- requêtes Lucene à forte cardinalité bornées à 128 termes analysés uniques.
- limites REST fédérées centralisées et `constraints` non supportées rejetées explicitement.
- Ollama distant HTTPS par défaut ; HTTP distant uniquement via opt-in administratif explicite.
- secrets à forte confiance redigés avant embeddings/fragments ; profil sémantique `content-v2`.
- `NEXUS_HOME`/SQLite privés sur POSIX ; chemins persistants symboliques durcis refusés.

## Preuve de qualification

Une ancienne PR verte n'est jamais recopiée ici comme preuve de l'état courant. La preuve applicable est le run attaché au SHA exact concerné.

## Organisation

```text
arc42/        vues architecture
quality/      scénarios qualité
risks/        registre de risques
```

Les documents d'itération peuvent conserver des numéros de PR, dates et mesures historiques ; les documents marqués courants doivent décrire le contrat actif.
