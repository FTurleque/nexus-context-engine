---
id: BE-0079
title: "Charger les Agent Skills par divulgation progressive, avec AI Skills Registry en snapshot local optionnel"
domain: backend
rules: true
---

# Charger les Agent Skills par divulgation progressive, avec AI Skills Registry en snapshot local optionnel

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [BE-0034](archive/BE-0034-adopter-la-divulgation-progressive-pour-les-agent-skills.md), [ARCH-0042](archive/ARCH-0042-consommer-ai-skills-registry-comme-snapshot-local-optionnel.md)

## Context

Un skill n'est pas une instruction globale. Le standard Agent Skills définit un dossier contenant au minimum un `SKILL.md`, avec des métadonnées de découverte légères (`name`, `description`) qui résument la capacité, et une procédure opératoire complète, chargée seulement quand la tâche justifie l'activation du skill. Des ressources optionnelles l'accompagnent (`scripts/`, `references/`, `assets/` ou d'autres fichiers).

Charger tous les `SKILL.md` dans chaque `ContextBundle` annulerait le bénéfice de la divulgation progressive et consommerait le budget de contexte ; ignorer les skills priverait NEXUS d'une configuration déjà présente dans le projet. Le dépôt externe AI Skills Registry doit aussi être réutilisable sans rendre ce dépôt, le réseau ou Git obligatoires pendant la construction d'un `ContextBundle` ([ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md), [ARCH-0077](ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md)).

NEXUS doit donc : découvrir les skills à coût de contexte minimal ; trier les résumés légers des capacités pour retenir, de façon déterministe et explicable, ceux qui servent la requête ; charger la procédure complète (`SKILL.md`) uniquement après cette sélection ; exposer les ressources sans les exécuter ; consommer un registre partagé sans en dépendre.

La question : **comment découvrir, sélectionner et charger les Agent Skills d'un projet et d'un registre partagé sans charger ni exécuter ce qui n'a pas été retenu ?**

## Options envisagées

Pipeline des skills :

- **Indexer tous les `SKILL.md` comme documentation générique** : réutilise Lucene, peu de code ; mais le corps complet de tous les skills influence la recherche, faux positifs dus au contenu procédural, pas de distinction entre découverte, activation et ressources.
- **Injecter tous les skills découverts dans le `ContextBundle`** : simple, aucun skill manqué ; mais explosion du budget, bruit, contraire au standard, impossible pour de grands catalogues.
- **Provider dédié, catalogue léger et activation progressive** : respecte le standard, seuls `name`, `description` et métadonnées servent à la découverte, corps lu après sélection, ressources inventoriées sans être chargées ni exécutées, extensible à un provider de registre ; demande de nouveaux contrats, un parseur de frontmatter YAML et une stratégie de sélection dédiée.

AI Skills Registry : dépendance obligatoire ou accès réseau (contraires au local-first), ou **snapshot local optionnel** lu par un provider.

## Decision

**Options retenues : provider dédié, catalogue léger et activation progressive ; AI Skills Registry consommé comme snapshot local optionnel.**

### Port et phases

NEXUS découvre les skills par un port `SkillSourceProvider`, distinct de `ContextSourceProvider` ([ARCH-0078](ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.md)). Le pipeline suit trois phases :

```text
Découverte  → lire uniquement le frontmatter de SKILL.md → construire SkillDescriptor
Sélection   → comparer la requête aux métadonnées name + description
              → score déterministe et raisons
Activation  → lire le SKILL.md complet du skill sélectionné
              → ContextItem de type SKILL
              → inventorier les ressources associées, ne rien exécuter
```

La découverte ne charge pas le corps Markdown dans le `SkillDescriptor`. La sélection filtre sur ces seules métadonnées légères avant tout chargement de la procédure complète ; elle est déterministe et locale, n'utilise que les métadonnées du skill et la requête, et un provider sémantique pourra l'enrichir derrière une abstraction sans rendre les embeddings obligatoires.

### Emplacements et validation

Racines locales reconnues : `.github/skills/**/SKILL.md`, `.claude/skills/**/SKILL.md`, `.agents/skills/**/SKILL.md` ; le modèle reste extensible à d'autres racines via le provider.

Frontmatter validé selon les contraintes principales du standard Agent Skills :

- `name` obligatoire, 1 à 64 caractères, minuscules alphanumériques et tirets, identique au nom du dossier parent ;
- `description` obligatoire, non vide, 1024 caractères au plus ;
- `license`, `compatibility`, `metadata` et `allowed-tools` optionnels.

### Ressources et exécution

Les ressources du dossier du skill sont inventoriées comme métadonnées légères. NEXUS ne lance jamais les fichiers de `scripts/`, n'interprète pas les permissions et ne charge pas automatiquement les assets binaires ; scripts et assets restent sous le contrôle du consommateur final.

### Budget

Les skills activés utilisent un budget séparé et borné. Un skill n'est jamais tronqué silencieusement : s'il ne tient pas dans son budget, il est exclu avec une raison explicable, pour ne pas transmettre une procédure incomplète.

### AI Skills Registry

- NEXUS lit AI Skills Registry depuis un snapshot local placé sous `.nexus/registry/skills` dans le projet courant ; aucune requête réseau n'a lieu pendant la construction du contexte.
- `AiSkillsRegistryProvider` ne découvre que les métadonnées des `SKILL.md` ; le corps complet est chargé par `SkillLoader` après sélection, comme pour les skills locaux.
- Les skills locaux du projet ont une priorité supérieure à ceux du registre : à nom identique, la déduplication garde la définition locale.
- L'absence du snapshot n'est pas une erreur et ne modifie pas le comportement normal de NEXUS : zéro skill de registre est alors retourné.
- La synchronisation du snapshot est une opération externe explicite ; `.nexus/registry` n'est pas versionné avec le projet.

## Do's and Don'ts

### Do

- Découvrir les skills par le port `SkillSourceProvider`, distinct de `ContextSourceProvider`.
- À la découverte, lire uniquement le frontmatter de `SKILL.md` pour construire un `SkillDescriptor`.
- Sélectionner de façon déterministe et locale, à partir des seuls `name` et `description` du skill et de la requête, avec un score et des raisons.
- Lire le `SKILL.md` complet seulement à l'activation d'un skill sélectionné et en faire un `ContextItem` de type `SKILL`.
- Reconnaître `.github/skills/**/SKILL.md`, `.claude/skills/**/SKILL.md` et `.agents/skills/**/SKILL.md`.
- Valider `name` (obligatoire, 1 à 64 caractères, minuscules alphanumériques et tirets, égal au nom du dossier parent) et `description` (obligatoire, non vide, 1024 caractères au plus).
- Inventorier les ressources du dossier du skill comme métadonnées légères.
- Exclure avec une raison explicable un skill qui ne tient pas dans son budget séparé et borné.
- Lire AI Skills Registry depuis un snapshot local placé sous `.nexus/registry/skills` dans le projet courant, `AiSkillsRegistryProvider` n'en découvrant que les métadonnées.
- Donner la priorité aux skills locaux du projet : à nom identique, la définition locale est conservée.

### Don't

- Ne pas charger le corps Markdown dans le `SkillDescriptor`.
- Ne jamais lancer les fichiers de `scripts/`, interpréter les permissions ni charger automatiquement les assets binaires.
- Ne pas tronquer silencieusement un skill.
- Ne pas rendre les embeddings obligatoires pour la sélection.
- Ne pas accéder au réseau pour AI Skills Registry pendant la construction du contexte.
- Ne pas traiter l'absence du snapshot comme une erreur, ni laisser cette absence modifier le comportement normal de NEXUS.

## Consequences

### Positives

- Les projets configurés avec Agent Skills sont exploitables sans migration ; de nombreux skills disponibles n'augmentent presque pas le contexte tant qu'ils ne sont pas sélectionnés.
- NEXUS explique quels skills étaient disponibles, lesquels ont été sélectionnés et pourquoi.
- Sélection, déduplication et chargement progressif sont communs aux skills locaux et au registre ; un projet peut surcharger localement un skill partagé.
- NEXUS reste utilisable sans AI Skills Registry.

### Négatives

- Une sélection lexicale sur `name` et `description` peut manquer un skill mal décrit.
- Le parseur YAML est une dépendance du composant de découverte des skills.
- Les ressources ne sont pas injectées automatiquement.
- Le snapshot du registre peut être périmé tant que sa synchronisation externe n'a pas été faite.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Chargement excessif de skills | Élevé | Divulgation progressive et budget séparé |
| Exécution implicite d'une ressource | Élevé | Ressources inventoriées seulement ; règle Archgate `skill-resources-never-executed` |
| Procédure incomplète transmise | Moyen | Exclusion plutôt que troncature |
| Skill partagé masquant un skill local | Moyen | Priorité locale supérieure à celle du registre |

## Compliance and Enforcement

- Tests (`core`, lancés par `./mvnw -B clean install`, job « Linux reactor Maven build » de `.github/workflows/ci.yml`) : `AgentSkillsIntegrationTest#activatesOnlyRelevantSkillAndNeverLoadsOrExecutesItsResourcesAutomatically`, `LocalAgentSkillsProviderTest#discoversOnlyValidSkillsAndInventoriesResourcesWithoutLoadingTheirContent`, `SkillFrontmatterParserTest`, `SkillSelectorTest` (sélection sur `name` et `description`, déterministe), `SkillContextSelectorTest#excludesOversizedSkillInsteadOfTruncatingIt`, `AiSkillsRegistryProviderTest#discoversRegistryMetadataBeforeLoadingTheSelectedSkillBody` et `#keepsProjectLocalSkillWhenRegistryContainsTheSameName`.
- `scripts/self-smoke.ps1` (lancé par `/nx-validate`) vérifie qu'un item `SKILL` sélectionné porte le `SKILL.md` complet, sans snapshot de registre.
- Règle Archgate `BE-0079-charger-les-agent-skills-par-divulgation-progressive.rules.ts` / `skill-resources-never-executed` : refuse dans `com.nexus.context.source.skill` tout lancement de processus (`ProcessBuilder`, `Runtime.exec`, `ProcessHandle`), tout moteur de script (`javax.script`) et toute ouverture par `java.awt.Desktop` ; commentaires et littéraux effacés.
- Règle Archgate `ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.rules.ts` / `core-network-only-in-opt-in-providers` : aucun client réseau dans le cœur hors des providers opt-in, donc aucun dans `AiSkillsRegistryProvider`.
- Revue : absence de snapshot sans erreur.

## Conditions de réexamen

La décision est réévaluée si le standard Agent Skills modifie substantiellement son mécanisme de découverte ; si les clients imposent une sélection non déterministe ou pilotée par modèle ; si AI Skills Registry impose un contrat incompatible avec `SkillSourceProvider` ou plus riche, sans toutefois devenir obligatoire ; si des mesures montrent qu'un classement sémantique apporte un gain suffisant pour compléter le sélecteur lexical local.

## Mise en œuvre

- Paquetage `core/src/main/java/com/nexus/context/source/skill/` : port `SkillSourceProvider`, `LocalAgentSkillsProvider` (priorité 80), `AiSkillsRegistryProvider` (priorité 60), `SkillDiscoveryService` (tri par priorité, déduplication), `SkillFrontmatterParser`, `SkillSelector`, `SkillLoader`, `SkillContextSelector` (budget dédié, exclusion sans troncature), `SkillResourceDescriptor`.
- Documentation : [agent-skills.md](../../developer/agent-skills.md), [ai-skills-registry.md](../../developer/ai-skills-registry.md).

## References

- [ARCH-0005](ARCH-0005-adopter-un-fonctionnement-local-first-et-opt-in.md) — local-first, intégrations opt-in.
- [ARCH-0077](ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md) — AI Skills Registry consommateur ou fournisseur optionnel.
- [ARCH-0078](ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.md) — standard Agent Skills, sélection sans exécution.
- [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) — budget du `ContextBundle`.
- Agent Skills specification : https://agentskills.io/specification
- GitHub Copilot Agent Skills : https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-skills
