# Releases immuables et rulesets de tags après réglage (2026-11-11)

Relevé des tâches 2.1, 2.2 et 2.5 du changement OpenSpec `sprint-14-plafond-charge-et-release-immuable` (`AUD-DEP-37`) : état du réglage « releases immuables » et des rulesets de tags, lu par `gh api` le 2026-11-11 à 17:13 UTC, après les gestes du mainteneur. L'état antérieur et l'historique du tag `v0.2.0` sont consignés dans [releases-immuables-et-tags-2026-11-11.md](releases-immuables-et-tags-2026-11-11.md).

## Réglage « releases immuables » (tâche 2.1)

```text
$ gh api repos/FTurleque/nexus-context-engine/immutable-releases
{"enabled":true,"enforced_by_owner":false}
```

## Rulesets de tags (tâche 2.2)

```text
$ gh api "repos/FTurleque/nexus-context-engine/rulesets?includes_parents=true" --jq '.[] | select(.target=="tag") | .id'
27049344
26777159
$ gh api repos/FTurleque/nexus-context-engine/rulesets/27049344 --jq '{name, enforcement, include: .conditions.ref_name.include, rules: [.rules[].type], bypass: .bypass_actors}'
{"bypass":[],"enforcement":"active","include":["refs/tags/v*"],"name":"Immutable version tags","rules":["deletion","non_fast_forward","update"]}
$ gh api repos/FTurleque/nexus-context-engine/rulesets/26777159 --jq '{name, enforcement, include: .conditions.ref_name.include, rules: [.rules[].type], bypass: .bypass_actors}'
{"bypass":[{"actor_id":5,"actor_type":"RepositoryRole","bypass_mode":"always"}],"enforcement":"active","include":["refs/tags/v*"],"name":"Protect tags","rules":["creation"]}
```

| Ruleset | Cible | Règles | Contournement |
|---|---|---|---|
| `27049344` « Immutable version tags » | `refs/tags/v*` | `update`, `deletion`, `non_fast_forward` | aucun |
| `26777159` « Protect tags » | `refs/tags/v*` | `creation` | rôle administrateur (`RepositoryRole` 5), `always` |

Plus aucun acteur ne peut déplacer ni supprimer un tag `v*` ; seul l'administrateur peut en créer un. Aucun essai de suppression n'a été fait sur un tag réel.

## Release `v0.2.0` (tâche 2.5)

```text
$ gh api repos/FTurleque/nexus-context-engine/releases/tags/v0.2.0 --jq '{immutable, published_at}'
{"immutable":false,"published_at":"2026-11-11T02:41:58Z"}
```

Publiée avant l'activation du réglage, `v0.2.0` reste non immuable, comme l'a décidé le mainteneur.

## Reste à relever

À la release suivante : le push de son tag `v*` accepté par le ruleset « Protect tags » (scénario « Création d'un tag de release »), puis `gh api repos/FTurleque/nexus-context-engine/releases/tags/<tag> --jq .immutable` égal à `true`.
