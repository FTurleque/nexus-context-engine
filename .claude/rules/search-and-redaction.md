---
paths:
  - "core/src/main/java/com/nexus/search/**"
  - "core/src/main/java/com/nexus/security/**"
  - "core/src/main/java/com/nexus/context/**"
  - "core/src/main/java/com/nexus/index/**"
---

# Recherche, contexte et redaction

- Lucene est dérivé de SQLite : toute évolution du contenu indexé doit pouvoir être reconstruite. Une requête analysée reste bornée à 128 termes uniques avant expansion multi-champs.
- Une seule mutation d'index par projet à la fois (`ProjectIndexLockManager`) ; les lectures passent par `acquireRead` pour ne jamais observer un dérivé en cours de remplacement. `READY` n'est publié qu'après revalidation du snapshot.
- La redaction des secrets précède **l'embedding, l'estimation de tokens et la sélection** ; les compteurs décrivent le contenu effectivement fourni. Les redactions multilignes conservent les séparateurs de lignes pour ne pas décaler les ranges persistés.
- Changer la politique de redaction (`SensitiveContentRedactor`, `SecretAssignmentScanner`) impose : incrément de `POLICY_VERSION`, migration d'invalidation lexicale, incrément de `CONTENT_PROFILE_VERSION` sémantique, mise à jour de la doc `content-vN` et des contrats. Suivre le skill `nexus-redaction-change`.
- La recherche sémantique reste opt-in ; Ollama distant en HTTPS sauf `NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true` ; credentials dans l'URI refusés ; réponses bornées en taille et en temps.
- Les providers externes (JDT LS, SCIP, MINOS) sont optionnels, bornés en temps et en concurrence (8 workers JVM), et JDT LS exige une racine approuvée (`NEXUS_JDTLS_TRUSTED_PROJECT_ROOTS`).
- Une `constraints` non supportée est rejetée explicitement, jamais ignorée.
