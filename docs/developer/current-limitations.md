# Limites actuelles et dette de consolidation

Ce registre décrit l'état courant après la campagne NXA3. Les anciens numéros de PR/runs ne constituent pas une preuve permanente ; la preuve de qualification est toujours le run attaché au HEAD exact concerné.

## Invariants techniques désormais couverts

### Filesystem et sources natives

- `ProjectPathGuard` protège les lectures sensibles sous la racine canonique ;
- traversal, symlink final et symlink d'ancêtre sont refusés sur les chemins durcis ;
- SCIP relit ses sources canoniques via la même frontière ;
- skills/customisations projet utilisent la frontière commune ;
- la découverte native partage `ContextDiscoveryLimits` avant sélection de tokens.

Limite résiduelle : les primitives Java portables ne sont pas un sandbox absolu contre un acteur local capable de muter agressivement le filesystem pendant l'opération.

### Git local

- commits, historique et chemins modifiés sont bornés ;
- les statuts sont filtrés aux cibles ;
- les diffs utilisent un sink à capacité fixe et sont tronqués déterministiquement ;
- un test de diff massif et un test du sink fixe empêchent le retour à un buffer extensible non borné.

### Fédération

- maximum de 100 projets uniques ;
- validation de cardinalité avant résolution/readiness ;
- CLI, application et MCP partagent le contrat ;
- le travail préparatoire du contexte fédéré est borné indépendamment du budget final.

### SQLite

SQLite reste canonique. V004 invalide les index historiques contenant des plages de symboles impossibles ; V005 impose ensuite :

```text
start_line >= 1
end_line >= start_line
```

Les index Lucene restent dérivés et reconstructibles.

### REST

Loopback reste disponible pour le développement local. Hors loopback, le démarrage échoue fermé si le contrat de transport sécurisé, le token robuste ou l'allowlist de racines ne sont pas démontrés.

### CI, release et supply-chain

- exact-head explicite pour NEXUS CI/CodeQL ;
- OSV, CodeQL, Trivy et SBOM actifs ;
- Maven/JDT LS vérifiés contre des ancres versionnées indépendantes ;
- image Docker construite une fois, qualifiée puis publiée sans rebuild ;
- GHCR préflight fail-closed avec reprise idempotente uniquement pour le même contenu ;
- Dependabot cible `develop`.

## Contrôle de gouvernance encore externe au code

La protection GitHub de `develop` est un état repository-admin, pas un fichier versionné. Le contrat attendu est décrit dans [`branch-governance.md`](branch-governance.md).

Tant que GitHub retourne `protected=false` pour `develop`, NXA3-14 reste ouvert : une poussée directe peut entrer avant le gate PR même si la CI se déclenche ensuite.

## Watch items

Les sujets suivants ne doivent pas être changés sans mesure ou scénario reproductible :

- lifecycle Lucene persistant ;
- isolation processus d'un provider externe réellement non coopératif ;
- garanties supplémentaires sur filesystem réseau/hostile ;
- cache Git persistant ;
- recovery sémantique face à une indisponibilité provider ou corruption physique ;
- nouveau moteur FTS/trigram pour les recherches substring.

## Règle de clôture audit

Un finding n'est déclaré fermé que si :

1. le comportement est implémenté ;
2. les preuves/tests/benchmarks exigés existent ;
3. la documentation correspond au code ;
4. les gates applicables sont verts sur le HEAD exact ;
5. les contrôles GitHub externes requis sont effectivement configurés lorsqu'ils font partie du finding.

Voir aussi [`ci-and-supply-chain.md`](ci-and-supply-chain.md), [`release-and-recovery.md`](release-and-recovery.md), [`native-context-discovery-limits.md`](native-context-discovery-limits.md) et [`branch-governance.md`](branch-governance.md).
