# ADR-0034 — Adopter la divulgation progressive pour les Agent Skills

- Statut : `accepted`
- Date : 2026-07-20

## Contexte et problème

NEXUS sait désormais découvrir et appliquer des instructions natives de repository, mais les `SKILL.md` sont encore seulement détectés comme personnalisations existantes.

Un skill n'est pas une instruction globale. Le standard Agent Skills définit un dossier contenant au minimum un `SKILL.md`, avec des métadonnées de découverte (`name`, `description`) et des instructions complètes chargées uniquement lorsque la tâche justifie l'activation du skill. Des ressources optionnelles peuvent accompagner le skill dans `scripts/`, `references/`, `assets/` ou d'autres fichiers.

Charger tous les `SKILL.md` dans chaque `ContextBundle` annulerait le bénéfice de la divulgation progressive et consommerait inutilement le budget de contexte. À l'inverse, ignorer complètement les skills empêcherait NEXUS de valoriser une configuration déjà présente dans un projet.

NEXUS doit donc pouvoir :

1. découvrir les skills avec un coût de contexte minimal ;
2. sélectionner les skills pertinents de manière déterministe et explicable ;
3. charger le `SKILL.md` complet uniquement après sélection ;
4. exposer les ressources associées sans les exécuter ;
5. rester compatible avec un futur AI Skills Registry sans introduire de dépendance vers celui-ci.

## Facteurs de décision

- Respect du standard Agent Skills au lieu d'inventer un format NEXUS.
- Consommation minimale du budget pendant la découverte.
- Sélection déterministe et explicable sans LLM obligatoire.
- Local-first et absence d'exécution implicite de scripts.
- Compatibilité avec plusieurs emplacements de skills de repository.
- Extensibilité vers des providers distants ou un registre externe.
- Préservation des frontières actuelles du `ContextBuilder`.

## Options envisagées

### Option A — Indexer tous les `SKILL.md` comme documentation générique

Avantages :

- réutilise directement Lucene ;
- peu de code supplémentaire.

Inconvénients :

- le corps complet de tous les skills influence la recherche ;
- ne respecte pas la divulgation progressive ;
- risque de faux positifs à cause du contenu procédural ;
- ne distingue pas découverte, activation et ressources.

### Option B — Injecter tous les skills découverts dans le `ContextBundle`

Avantages :

- implémentation simple ;
- aucun risque de manquer un skill pertinent.

Inconvénients :

- explosion du budget ;
- bruit important ;
- contraire au standard Agent Skills ;
- impossible à faire évoluer vers de grands catalogues.

### Option C — Provider dédié + catalogue léger + activation progressive

Avantages :

- respecte le standard ;
- seuls `name`, `description` et métadonnées sont nécessaires pour la découverte ;
- le corps complet est lu seulement après sélection ;
- les ressources peuvent être inventoriées sans être chargées ni exécutées ;
- le provider local pourra être complété par un provider AI Skills Registry.

Inconvénients :

- nécessite de nouveaux contrats ;
- nécessite un parseur de frontmatter YAML ;
- nécessite une stratégie de sélection dédiée.

## Décision

Nous retenons l'option C.

NEXUS introduit un port `SkillSourceProvider` distinct de `ContextSourceProvider`.

Le pipeline des skills suit trois phases :

```text
Découverte
→ lire uniquement le frontmatter de SKILL.md
→ construire SkillDescriptor

Sélection
→ comparer la requête aux métadonnées name + description
→ produire un score déterministe et des raisons

Activation
→ lire le SKILL.md complet du skill sélectionné
→ construire un ContextItem de type SKILL
→ inventorier les ressources associées
→ ne rien exécuter
```

Les emplacements locaux reconnus initialement sont :

```text
.github/skills/**/SKILL.md
.claude/skills/**/SKILL.md
.agents/skills/**/SKILL.md
```

Le modèle reste extensible à d'autres racines via le provider.

Le frontmatter est validé selon les contraintes principales du standard Agent Skills :

- `name` obligatoire, 1 à 64 caractères, minuscules alphanumériques et tirets ;
- `name` identique au nom du dossier parent ;
- `description` obligatoire, non vide, maximum 1024 caractères ;
- `license`, `compatibility`, `metadata` et `allowed-tools` optionnels.

La découverte ne charge pas le corps Markdown dans le `SkillDescriptor`.

Les ressources sous le dossier du skill sont inventoriées comme métadonnées légères. NEXUS ne lance jamais les fichiers de `scripts/`, n'interprète pas les permissions et ne charge pas automatiquement les assets binaires.

La sélection est déterministe et locale. Elle utilise uniquement les métadonnées du skill et la requête utilisateur. Un futur provider sémantique pourra enrichir cette étape derrière une abstraction sans rendre les embeddings obligatoires.

Les skills activés utilisent un budget séparé et borné. Un skill ne doit pas être tronqué silencieusement : s'il ne tient pas dans le budget prévu, il est exclu avec une raison explicable afin d'éviter de transmettre des instructions procédurales incomplètes.

## Conséquences

### Positives

- Les projets déjà configurés avec Agent Skills deviennent exploitables sans migration.
- Les nombreux skills disponibles n'augmentent presque pas le contexte tant qu'ils ne sont pas sélectionnés.
- NEXUS peut expliquer quels skills étaient disponibles, lesquels ont été sélectionnés et pourquoi.
- Les scripts et assets restent sous le contrôle du consommateur final.
- Le futur AI Skills Registry pourra implémenter `SkillSourceProvider` sans modifier le cœur du pipeline.

### Négatives

- Une sélection purement lexicale sur `name` et `description` peut manquer certains skills dont la description est mauvaise.
- Le parseur YAML devient une dépendance du composant de découverte des skills.
- Les ressources ne sont pas automatiquement injectées dans cette itération.

## Confirmation du respect de la décision

La décision est respectée si les tests démontrent que :

1. un projet avec plusieurs skills ne charge pas leurs corps lors de la découverte ;
2. seuls les skills dont les métadonnées correspondent à la requête sont activés ;
3. le `ContextBundle` contient un item `SKILL` pour un skill sélectionné ;
4. un skill non pertinent reste absent du bundle ;
5. les ressources sont listées mais aucun script n'est exécuté ;
6. un skill trop volumineux est exclu plutôt que tronqué ;
7. les mêmes entrées produisent la même sélection.

## Conditions de réexamen

Cette décision pourra être réexaminée si :

- le standard Agent Skills modifie substantiellement son mécanisme de découverte ;
- les clients imposent une sélection de skills non déterministe ou pilotée par modèle ;
- un AI Skills Registry nécessite un contrat incompatible avec `SkillSourceProvider` ;
- les mesures montrent qu'un ranking sémantique apporte un gain suffisamment important pour compléter le sélecteur lexical local.

## Références

- Agent Skills specification : https://agentskills.io/specification
- GitHub Copilot Agent Skills : https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-skills
