# Sécurité

## Traitement des alertes de sécurité

Le dépôt public a les alertes Dependabot, les mises à jour de sécurité Dependabot, le secret scanning et la push protection activés (onglet Security de GitHub). Ces alertes portent sur l'état fusionné du dépôt ; les gates OSV des PR ne couvrent que le code en cours de revue. Le mainteneur traite chaque alerte selon la procédure ci-dessous.

### Délai de prise en charge

Toute alerte ouverte est triée dans les **7 jours** qui suivent son ouverture : elle reçoit une décision (correction, fermeture justifiée) ou une PR de correction ouverte. Un secret réel exposé se traite sans attendre ce délai (voir plus bas).

Le tri périodique se fait en lecture avec `gh api` :

```bash
gh api repos/FTurleque/nexus-context-engine/dependabot/alerts --jq '[.[] | select(.state == "open")] | length'
gh api repos/FTurleque/nexus-context-engine/secret-scanning/alerts --jq '[.[] | select(.state == "open")] | length'
```

### Alertes Dependabot

1. **Triage.** Lire l'avis (GHSA/CVE), la plage vulnérable et le chemin de dépendance. Vérifier que la dépendance est réellement embarquée : module Maven concerné, portée (`compile`, `runtime` ou `test`), présence dans le SBOM agrégé du réacteur.
2. **Correction.** Monter la version par une PR vers `develop`, comme toute montée de dépendance (skill `nexus-dependency-bump`). Une PR ouverte par les mises à jour de sécurité Dependabot suit le même chemin : checks obligatoires verts, merge exact-head ; elle est corrigée sur sa branche si elle casse le wrapper Maven ou une ancre d'intégrité.
3. **Fermeture justifiée.** Une alerte n'est fermée sans correction qu'avec le motif GitHub adapté et un commentaire qui donne la preuve :
   - `not_used` (« Vulnerable code is not actually used ») : la dépendance ou la fonction vulnérable n'est pas atteinte ; le commentaire cite le module, la portée ou l'appel absent ;
   - `inaccurate` : l'avis ne s'applique pas à la version résolue ; le commentaire cite la version effective ;
   - `tolerable_risk` : le risque est accepté ; le commentaire dit pourquoi et jusqu'à quand.

   Les motifs `no_bandwidth` et `fix_started` ne servent pas à fermer une alerte : une correction en cours garde l'alerte ouverte jusqu'à la fusion de sa PR.

### Alertes de secret scanning

1. **Triage.** Ouvrir l'alerte, lire le type de secret, le fichier et le commit. Déterminer si la valeur est un vrai identifiant ou une valeur factice.
2. **Secret réel.** Révoquer ou faire tourner l'identifiant chez son fournisseur avant toute autre action : le dépôt est public, la valeur est considérée comme compromise dès sa publication. Retirer ensuite la valeur du code par une PR vers `develop`, puis fermer l'alerte avec le motif `revoked` et un commentaire qui indique la date de révocation.
3. **Faux positif d'une fixture de redaction.** Les tests de la politique de redaction (`SensitiveContentRedactor`, `SecretAssignmentScanner`) contiennent volontairement des valeurs qui ressemblent à des secrets, pour vérifier qu'elles sont masquées. Une alerte qui pointe une telle valeur sous `core/src/test/` — notamment `core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java` et les tests `*RedactionTest` de `core/src/test/java/com/nexus/context/` — est fermée avec le motif **`used_in_tests`** (« Used in tests »). Le commentaire de fermeture donne le chemin de la fixture, par exemple : `Fixture de test de redaction : core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java`.
4. **Autre faux positif.** Une valeur hors des tests qui n'est pas un secret (identifiant public, motif de documentation) est fermée avec le motif `false_positive` et un commentaire qui explique pourquoi.

Une fixture de redaction n'utilise jamais un identifiant réel, même expiré : une valeur factice qui a la forme d'un secret suffit au test.

### Push protection

Quand la push protection bloque un push, le secret est retiré du commit avant de pousser à nouveau. Le contournement n'est admis que pour une fixture de redaction, avec le motif « It's used in tests » ; l'alerte que GitHub ouvre alors est fermée comme indiqué ci-dessus.
