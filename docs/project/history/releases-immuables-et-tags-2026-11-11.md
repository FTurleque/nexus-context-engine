# Releases immuables et rulesets de tags (2026-11-11)

Relevé de la tâche 2.5 du changement OpenSpec `sprint-14-plafond-charge-et-release-immuable` (`AUD-DEP-37`) : état du réglage « releases immuables », des rulesets de tags et de la release `v0.2.0`, lu par `gh api` le 2026-11-11, avant les gestes du mainteneur (tâches 2.1 et 2.2). La politique visée est décrite dans [immutable-release-publishing.md](../../developer/immutable-release-publishing.md#politique-de-publication-cible-sec-0068-proposée) et la procédure de reprise dans [release-and-recovery.md](../../developer/release-and-recovery.md#reprise-dune-release-échouée).

## Réglage « releases immuables »

```text
$ gh api repos/FTurleque/nexus-context-engine/immutable-releases
{"enabled":false,"enforced_by_owner":false}
```

## Rulesets de tags

```text
$ gh api repos/FTurleque/nexus-context-engine/rulesets --jq '.[] | {id,name,target,enforcement}'
{"enforcement":"active","id":20503835,"name":"Protect main & develop","target":"branch"}
{"enforcement":"active","id":26777159,"name":"Protect tags","target":"tag"}
$ gh api repos/FTurleque/nexus-context-engine/rulesets/26777159 --jq '{name, enforcement, include: .conditions.ref_name.include, rules: [.rules[].type], bypass: .bypass_actors}'
{"bypass":[{"actor_id":5,"actor_type":"RepositoryRole","bypass_mode":"always"}],"enforcement":"active","include":["refs/tags/v*"],"name":"Protect tags","rules":["deletion","non_fast_forward","creation","update"]}
```

Un seul ruleset de tags porte les quatre règles, avec le rôle administrateur (`RepositoryRole` 5) en contournement `always` : le ruleset « Immutable version tags » n'existe pas encore.

## Release `v0.2.0`

```text
$ gh api repos/FTurleque/nexus-context-engine/releases/tags/v0.2.0 --jq '{immutable, published_at}'
{"immutable":false,"published_at":"2026-11-11T02:41:58Z"}
```

La release `v0.2.0` a été publiée avant l'activation du réglage ; GitHub n'applique pas l'immuabilité rétroactivement. Elle reste non immuable : le mainteneur a choisi de ne pas la supprimer ni de la republier sous un nouveau tag, ce qui changerait la version annoncée sans gain de contenu. Une reprise de `publish-github-release-assets.sh` sur ce tag sortirait en code 79.

## Historique du tag `v0.2.0`

| Run `release.yml` | Démarré (UTC) | Commit du tag | Conclusion |
|---|---|---|---|
| `42923369260` | 2026-11-10T23:26:23Z | `3ecdb2be522514d75e509afc47f31389ae2c864b` | échec au gate de charge |
| `42945695122` | 2026-11-11T02:23:27Z | `9f6e7839b049cf07e37b7660864437ee0c905163` | succès |

Entre les deux runs, le tag `v0.2.0` a été supprimé puis reposé sur un autre commit, par le contournement administrateur du ruleset « Protect tags ». `git ls-remote origin refs/tags/v0.2.0` désigne l'objet tag `46fde9fccdd22747c22c218cf2d9c55ea4293bc4`, qui pointe sur `9f6e7839`.

## Reste à relever

Après les tâches 2.1 et 2.2, un relevé daté consigne `enabled: true` pour `immutable-releases`, les deux rulesets de tags avec leurs règles et leurs acteurs de contournement, puis, après la release suivante, `immutable: true` pour son tag.
