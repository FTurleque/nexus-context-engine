---
paths:
  - "core/src/main/java/**/*.java"
---

# Cœur Java NEXUS

- Java 21 (`release 21`) : pas d'API au-delà de Java 21, pas de `--enable-preview`, pas de `jdk.incubator.vector` (ADR et qualification ABBA).
- Aucun framework applicatif dans `core` : pas d'annotations CDI/Spring/Quarkus. La composition passe par `NexusApplication` (composition root partagé CLI/REST/MCP).
- Respecter ports/adaptateurs : `core` ne dépend jamais de `adapters/*`. Un nouveau besoin d'I/O passe par une interface du domaine (ex. `ProjectRepository`, `IndexRepository`, `SearchIndex`).
- Déterminisme obligatoire pour tout ce qui sort (ranking, sélection de contexte, troncature, exclusion, diagnostics) : trier explicitement, ne jamais exposer l'ordre d'itération d'un `HashMap`/`HashSet`, départager les ex æquo par une clé stable (chemin repository, puis identifiant).
- Toute exclusion, troncature ou score doit rester explicable : produire un diagnostic ou une métadonnée lisible, jamais un abandon silencieux.
- Borner le travail **avant** l'allocation : tailles, cardinalités, profondeur, deadline. Réutiliser les politiques existantes (`ContextDiscoveryLimits`/`ContextDiscoveryBudget`, `ContextMaterializationBudget`, `FederatedScopePolicy.MAX_PROJECTS` = 100, 128 termes Lucene, 8 workers externes) au lieu de nouvelles constantes ad hoc.
- Lectures de fichiers projet : passer par `ProjectPathGuard` + `SafeFileIO`. Ne jamais suivre de symlink ni accepter un chemin hors repository. Les identités de fichiers utilisent `RepositoryPath` (ADR-0049).
- Les messages d'exception et diagnostics destinés à l'utilisateur sont en français ; identifiants, noms de classes et commentaires techniques suivent le style du fichier.
- Les sorties publiques (REST/MCP/CLI) projettent les chemins en repository-relative et filtrent les diagnostics internes (`PublicOutputProjection`, qui applique `PublicContextPolicy`, `PublicDiagnosticPolicy` et `PublicProjectPathPolicy` ; ARCH-0061).
- Pas de `System.out`/`printStackTrace` dans `core` hors CLI ; utiliser `System.Logger`.
- Un changement de politique durable (borne, format persistant, contrat public) implique un ADR : voir le skill `nexus-adr`.
