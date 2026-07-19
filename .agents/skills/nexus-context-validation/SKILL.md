---
name: nexus-context-validation
description: Validate NEXUS context quality, token budgets, search ranking and progressive disclosure. Use when validating NEXUS ContextBundle behavior, Agent Skills integration, or self-smoke scenarios.
license: Apache-2.0
compatibility: Designed for the NEXUS local CLI validation workflow.
metadata:
  owner: nexus
  purpose: self-smoke
---

# NEXUS context validation

NEXUS_SKILL_VALIDATION_WORKFLOW

Use this skill when validating the context engine itself.

1. Confirm that project indexing is idempotent.
2. Confirm that search still ranks the expected code first.
3. Confirm that `ContextBundle.estimatedTokens` never exceeds its budget.
4. Confirm that native instructions, skills, documentation and code remain separately explainable.
5. Confirm that skills are selected from metadata before their full `SKILL.md` is loaded.
6. Never execute bundled scripts as part of NEXUS context construction.

Detailed checks are available in `references/quality-checks.md` and must only be loaded by a consumer when they are actually needed.
