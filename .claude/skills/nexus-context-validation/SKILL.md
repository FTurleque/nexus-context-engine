---
name: nexus-context-validation
description: Validate NEXUS context quality and progressive disclosure. Use for ContextBundle budgets, ranking, Agent Skills integration, or self-smoke validation.
license: Apache-2.0
compatibility: NEXUS local CLI.
metadata:
  owner: nexus
  purpose: self-smoke
---

# NEXUS context validation

NEXUS_SKILL_VALIDATION_WORKFLOW

Validate that:

1. indexing is idempotent;
2. expected code ranks first;
3. `ContextBundle` stays within its token budget;
4. instructions, skills, documentation and code remain explainable;
5. metadata selection happens before full `SKILL.md` loading;
6. NEXUS never executes skill scripts during context construction.

Optional checks live in `references/quality-checks.md` and are loaded only by the consumer when needed.
