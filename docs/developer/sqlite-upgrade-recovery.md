# Upgrade et recovery SQLite

## Plages de symboles historiques

Depuis NXA2-01, le modèle `CodeSymbol` exige une plage structurellement valide :

```text
startLine >= 1
endLine >= startLine
```

Des bases créées par des versions antérieures peuvent contenir des symboles sans position source, historiquement persistés avec des valeurs telles que `-1/-1`. Ces coordonnées ne peuvent pas être réparées de manière fiable sans relire le code source.

## Stratégie V004

La migration `V004__invalidate_invalid_symbol_ranges.sql` applique une stratégie fail-safe et reconstructible :

1. détecter les projets possédant au moins un symbole dont la plage est invalide ;
2. passer uniquement ces projets à `NOT_INDEXED` ;
3. remettre `last_indexed_at` à `NULL` ;
4. incrémenter leur génération d'index afin d'invalider les consommateurs dérivés ;
5. supprimer leurs `indexed_files`, ce qui supprime par cascade les symboles et relations associés ;
6. conserver intégralement les projets dont toutes les plages sont valides.

Aucune coordonnée n'est inventée et aucun symbole invalide n'est ignoré silencieusement. Le prochain `index()` sur un projet invalidé effectue une reconstruction complète parce que son état persistant n'est plus `READY`.

## Propriétés d'upgrade

- une base fraîche applique V004 sans invalider de projet ;
- une base pré-V004 contenant des plages historiques invalides reste ouvrable ;
- les données structurelles invalides ne sont jamais désérialisées en `CodeSymbol` ;
- la migration est transactionnelle et idempotente ;
- une seconde ouverture ne ré-incrémente pas la génération et ne répète pas l'invalidation ;
- un projet sain présent dans la même base reste `READY` avec son index intact.

## Recovery opérateur

Après upgrade, un projet passé automatiquement à `NOT_INDEXED` doit être réindexé normalement :

```powershell
nexus index <project>
```

Une reconstruction explicite reste possible :

```powershell
nexus index <project> --rebuild
```

Les APIs de recherche et de contexte exigent déjà l'état `READY`; elles ne servent donc pas un index invalidé pendant la fenêtre précédant la reconstruction.
