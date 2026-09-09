# Guide développeur NEXUS

Ce répertoire distingue :

1. **documentation courante** — doit suivre l'exact head ;
2. **documents d'itération/benchmark** — résultats historiques, volontairement datés ;
3. **ADR** — décisions append-only.

## État courant

```text
version      0.2.0
Java         >=21, release 21
Maven        3.9.16 via wrapper vérifié
integration  develop
release      main
Quarkus      3.39.1
MCP SDK      2.0.1
```

Le build courant utilise **Maven 3.9.16** via le wrapper et son ancre d'intégrité versionnée.

Les campagnes NXA3 et NXA4 constituent la baseline de hardening courante. Une ancienne PR ou un ancien run vert n'est jamais une preuve pour un nouveau HEAD : la preuve applicable reste le run exact-head du commit concerné.

## Parcours recommandé

| Sujet | Document |
|---|---|
| architecture globale | [Architecture](../architecture.md) |
| Arc42 | [Documentation d'architecture](../architecture/README.md) |
| architecture concrète | [Architecture d'implémentation](architecture-implementation.md) |
| limites et watch items | [Limites actuelles](current-limitations.md) |
| filesystem / locks / support réseau | [Support filesystem](filesystem-support.md) |
| roadmap | [Roadmap](../roadmap.md) |
| release / migration / recovery | [Release et recovery](release-and-recovery.md) |
| CI / supply-chain | [CI et supply-chain](ci-and-supply-chain.md) |
| gouvernance branches | [Gouvernance](branch-governance.md) |
| contexte et budgets | [Construction du contexte](context-building.md) |
| découverte native | [Limites de découverte](native-context-discovery-limits.md) |
| REST | [API REST](rest-api.md) |
| sémantique / Ollama | [Recherche sémantique](semantic-search.md) |
| Code Intelligence | [Code Intelligence](code-intelligence.md) |
| JDT LS | [JDT Language Server](jdt-language-server.md) |
| MCP | [MCP](mcp.md) |
| Git | [Contexte Git](git-context.md) |
| AI Skills Registry | [AI Skills Registry](ai-skills-registry.md) |
| scale | [Recherche à grande échelle](large-scale-search.md) |

## Composition

```text
CLI / REST / MCP
       │
       ▼
NexusApplication
       │
       ├─ SQLite canonique / Lucene dérivé
       ├─ Search + FederatedSearch
       ├─ DefaultContextBuilder
       └─ FederatedContextService
```

## Contrats à préserver

### Baseline NXA3

- SQLite canonique ; index dérivés reconstructibles.
- `ProjectPathGuard` pour les lectures projet sensibles ; traversal/symlinks refusés.
- support filesystem borné aux filesystems locaux qualifiés Linux/Windows ; SMB/NFS et filesystems distribués restent non supportés sans qualification dédiée.
- découverte native bornée avant sélection de tokens par `ContextDiscoveryLimits`/`ContextDiscoveryBudget`.
- maximum 100 projets uniques, validé avant résolution/readiness.
- Git local/read-only, historique borné et diff à capacité fixe.
- REST distant uniquement avec auth, roots et transport TLS effectif.
- CodeQL/NEXUS CI exact-head.
- outils fixes vérifiés contre des hashes versionnés.
- image Docker construite une fois, qualifiée, publiée sans rebuild.
- V005 impose les invariants de plage `CodeSymbol` dans SQLite.
- Dependabot cible `develop`.

### Baseline NXA4 et post-audit

- management Quarkus séparé du listener applicatif (`127.0.0.1:9000`).
- frames JDT LS et backlog JSON-RPC bornés ; tâche externe globale <= 8 workers actifs.
- `--deep-java` exige une racine canonique explicitement approuvée avant démarrage JDT LS.
- métadonnées Code Intelligence bornées en taille et cardinalité avant canonicalisation.
- requête Lucene analysée <= 128 termes uniques avant expansion multi-champs.
- limites REST fédérées alignées sur les politiques centrales.
- `constraints` non supportées rejetées explicitement.
- Ollama distant HTTPS par défaut ; HTTP distant seulement via `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true`.
- credentials dans l'URI Ollama refusés.
- secrets à forte confiance redigés avant embeddings et fragments de contexte.
- profil sémantique `content-v2` pour reconstruire les vecteurs historiques incompatibles.
- `NEXUS_HOME` privé sur POSIX et chemins persistants symboliques concernés refusés.
- sous Windows/filesystems ACL, `NEXUS_REQUIRE_PRIVATE_STORAGE=true` permet d'exiger une preuve de confidentialité et d'échouer fermé sans réécrire naïvement les ACL.
- le fallback `SafeFileIO` sans `SecureDirectoryStream` capture et revalide l'identité filesystem autour de l'ouverture.
- la qualification sémantique réelle Ollama est disponible périodiquement/manuellement avec runtime vérifié par SHA-256 et seuils de non-régression.
- `jdk.incubator.vector` reste volontairement non activé après qualification ABBA ; toute adoption exige une nouvelle mesure probante.

## Build

```powershell
.\mvnw.cmd clean install
```

ou :

```bash
./mvnw clean install
```

## Contribution

1. Modifier la documentation courante lorsque le contrat change.
2. Ajouter une preuve automatique lorsqu'un fait documentaire est machine-vérifiable.
3. Ne pas présenter un ancien run vert comme preuve d'un nouveau head.
4. Ne pas abaisser un budget/seuil pour masquer une régression.
5. Toute décision structurante durable implique un ADR lorsque le changement dépasse un simple durcissement d'implémentation.

La protection effective de `develop` reste une configuration GitHub externe au code et doit être vérifiée par API après modification. Le ruleset `Protect main & develop` est actif et #130 est satisfait. Le projet est maintenu par **un seul mainteneur** : les audits ne doivent pas transformer l'absence d'une seconde approbation humaine ou d'une resynchronisation stricte de branche en finding tant que ce modèle n'est pas explicitement modifié.

## Contrat de hardening du 9 septembre 2026

Voir [ADR-0048](../adr/0048-distinguer-traversee-stricte-et-compatibilite-filesystem.md)
pour la distinction entre traversée strict et compatibility. Le fallback sans
`SecureDirectoryStream` est best-effort : la revalidation ne détecte pas un ABA
restauré. `NEXUS_REQUIRE_STRICT_PATH_IO=true` le refuse avant ouverture du fichier.
REST HTTPS distant et REST loopback hardened exigent ce flag ainsi que
`NEXUS_REQUIRE_PRIVATE_STORAGE=true`. Le mode Docker `loopback-forward` reste local
selon sa déclaration opérateur. Windows/UNC local conserve la compatibilité.
`READY` atteste la génération indexée revalidée, sans snapshot atomique du
filesystem vivant. Les mutations détectées restent refusées.

Les contextes REST/MCP/CLI projettent les chemins en repository-relative et
filtrent les diagnostics/métadonnées internes à la frontière de sortie. Les
requêtes client sont conservées séparément. La redaction des skills précède
estimation et sélection ; les compteurs décrivent le contenu effectivement fourni.


## Identité repository et upgrade V007

Le contrat unique `RepositoryPath` encode les composants filesystem sans remplacer
les backslashes POSIX. `a\b.java` et `a/b.java` sont deux identités distinctes sur
POSIX. La résolution sur un autre filesystem refuse les composants qui y seraient
réinterprétés. V007 invalide les anciens index et impose une reconstruction ; le
fingerprint `nexus-repository-path-v2` invalide également les dérivés sémantiques
restés désactivés pendant l'upgrade. Voir [ADR-0049](../adr/0049-identite-repository-par-composants-et-diagnostics-publics.md).
