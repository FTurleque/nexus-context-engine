# AI Skills Registry dans NEXUS

## Statut

Capacité **livrée et validée** depuis l'Itération 14.

NEXUS peut découvrir des skills provenant d'un snapshot local AI Skills Registry sans accès réseau pendant la construction du contexte.

## Emplacement du snapshot

```text
.nexus/registry/skills/**/SKILL.md
```

`.nexus/registry` est un cache local non versionné. Son absence n'empêche jamais NEXUS de fonctionner avec les skills du projet.

## Comportement fonctionnel

Deux origines sont prises en compte :

```text
skills locaux du projet       priorité 80
snapshot AI Skills Registry   priorité 60
```

La déduplication est effectuée par nom dans `SkillDiscoveryService`. Un skill local de même nom garde donc la priorité sur le snapshot partagé.

La divulgation progressive reste la même pour les deux origines :

```text
découverte
→ frontmatter seulement

sélection
→ name + description + metadata

activation
→ chargement du SKILL.md complet

ressources
→ inventoriées mais non chargées/exécutées automatiquement
```

## Architecture contractuelle

Le contrat prévu est :

```text
SkillSourceProvider[]
        │
        ▼
SkillDiscoveryService
        │
        ├── tri par priorité
        └── déduplication par nom
        │
        ▼
SkillSelector
        ▼
SkillLoader
        ▼
SkillContextSelector
```

`AiSkillsRegistryProvider` implémente bien `SkillSourceProvider` et lit uniquement les métadonnées nécessaires pendant la découverte.

## Composition actuelle — dette connue

Le comportement fonctionnel ci-dessus est correct et testé, mais la composition courante n'utilise pas encore les deux providers comme deux entrées indépendantes.

Aujourd'hui :

```text
NexusApplication
    │
    └── LocalAgentSkillsProvider
             │
             └── crée AiSkillsRegistryProvider pendant discover()
```

Autrement dit, `LocalAgentSkillsProvider` délègue lui-même au registre avant de retourner son résultat, alors que `SkillDiscoveryService` possède déjà le contrat nécessaire pour agréger plusieurs `SkillSourceProvider`.

Cette divergence ne change pas la priorité locale > registre, mais elle couple deux providers qui devraient rester indépendants.

La correction est planifiée en **Phase 6 — Itération 20** :

```text
NexusApplication
    │
    ├── LocalAgentSkillsProvider
    └── AiSkillsRegistryProvider
             │
             ▼
      SkillDiscoveryService
```

La priorité et la déduplication continueront d'être portées par les descriptors/service de discovery, pas par une dépendance provider → provider.

## Absence de réseau

NEXUS ne synchronise pas lui-même un registre distant pendant une demande de contexte :

- aucune requête HTTP ;
- aucun clone/fetch Git ;
- aucun secret nécessaire ;
- aucune indisponibilité distante susceptible de bloquer `ContextBuilder`.

La synchronisation éventuelle du snapshot appartient à un outil ou workflow externe.

## Validation livrée

L'Itération 14 a validé :

- découverte des métadonnées sans charger tous les corps ;
- chargement du corps uniquement après sélection ;
- priorité du skill local sur un doublon registry ;
- absence de snapshot sans erreur ;
- build du cœur ;
- self-smoke historique sans dépendance au registre.

Runner :

```powershell
.\scripts\validate-iteration-14.ps1
```

## Limites actuelles

Le snapshot n'exploite pas encore nécessairement tous les champs possibles d'un registre partagé (`version`, `status`, `category`, `tags`) pour le ranking.

Ces enrichissements ne doivent être ajoutés que s'ils améliorent réellement la sélection. Ils peuvent rester derrière `SkillSourceProvider` sans modifier `DefaultContextBuilder`.

Voir également :

- [`agent-skills.md`](agent-skills.md) ;
- [`current-limitations.md`](current-limitations.md), F09 ;
- [`../roadmap.md`](../roadmap.md), Itération 20.
