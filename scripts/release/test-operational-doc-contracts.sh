#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

root = Path.cwd()

def text(path: str) -> str:
    value = (root / path).read_text(encoding="utf-8")
    if not value.strip():
        raise SystemExit(f"empty contract file: {path}")
    return value


def require(path: str, needle: str) -> None:
    if needle not in text(path):
        raise SystemExit(f"documentation contract drift: {path} must contain {needle!r}")


def forbid(path: str, needle: str) -> None:
    if needle in text(path):
        raise SystemExit(f"obsolete documentation contract: {path} still contains {needle!r}")

wrapper = text(".mvn/wrapper/maven-wrapper.properties")
if "apache-maven-3.9.16-bin.zip" not in wrapper:
    raise SystemExit("wrapper contract drift: expected Maven 3.9.16")

for path in (
    "README.md",
    "docs/developer/README.md",
    "docs/developer/release-and-recovery.md",
    "docs/developer/ci-and-supply-chain.md",
):
    require(path, "Maven 3.9.16")

migrations = sorted((root / "src/main/resources/db/migration").glob("V*.sql"))
if not migrations or migrations[-1].name != "V005__enforce_symbol_range_constraints.sql":
    raise SystemExit(f"schema contract drift: latest migration is {migrations[-1].name if migrations else 'none'}")
require("docs/developer/release-and-recovery.md", "V005__enforce_symbol_range_constraints.sql")
for path in (
    "README.md",
    "docs/architecture.md",
    "docs/developer/release-and-recovery.md",
    "docs/developer/ci-and-supply-chain.md",
    "docs/developer/architecture-implementation.md",
    "docs/architecture/arc42/08-concepts-transverses.md",
):
    require(path, "start_line >= 1")
    require(path, "end_line >= start_line")

release_doc = "docs/developer/immutable-release-publishing.md"
require(release_doc, "image exacte déjà qualifiée")
require(release_doc, "reprise idempotente")
for stale in (
    "L'image est reconstruite à partir du commit qualifié",
    "build de l'image de publication exacte",
    "Si un tag immuable existe déjà, une nouvelle exécution échoue",
):
    forbid(release_doc, stale)

release_workflow = text(".github/workflows/release.yml")
if "- 'v*.*.*'" not in release_workflow:
    raise SystemExit("release trigger contract drift: expected v*.*.* tag trigger")
if "docker build " in release_workflow or "docker buildx build " in release_workflow:
    raise SystemExit("release contract drift: release.yml must not rebuild the qualified image")

codeql = text(".github/workflows/codeql.yml")
for needle in ("github.event.pull_request.head.sha", "Assert exact-head contract"):
    if needle not in codeql:
        raise SystemExit(f"CodeQL exact-head contract drift: missing {needle!r}")

dependabot = text(".github/dependabot.yml")
if dependabot.count("target-branch: develop") < 3:
    raise SystemExit("Dependabot contract drift: every managed ecosystem must target develop")

require("docs/developer/ci-and-supply-chain.md", "`develop` est la branche d'intégration")
require("docs/developer/branch-governance.md", "force pushes")
require("docs/developer/native-context-discovery-limits.md", "NativeContextDiscoveryBudgetBenchmarkTest")

require("docs/developer/rest-api.md", "Quarkus     3.39.1")
require("docs/developer/rest-api.md", "quarkus.http.insecure-requests")
require("docs/developer/rest-api.md", "trusted-proxies")
require("docs/developer/mcp.md", "MCP SDK     2.0.1")
require("docs/developer/mcp.md", "100 projets uniques")

for path in (
    "docs/architecture.md",
    "docs/developer/architecture-implementation.md",
    "docs/developer/context-building.md",
    "docs/developer/large-scale-search.md",
    "docs/developer/mcp.md",
    "docs/developer/rest-api.md",
):
    require(path, "100 projets uniques")

for path in (
    "docs/architecture.md",
    "docs/developer/architecture-implementation.md",
    "docs/developer/context-building.md",
    "docs/developer/native-context-sources.md",
    "docs/developer/ai-skills-registry.md",
):
    require(path, "ContextDiscoveryBudget")

require("docs/developer/git-context.md", "BoundedOutput")
require("docs/developer/git-context.md", "6 000 caractères")

current_docs = (
    "docs/architecture.md",
    "docs/architecture/README.md",
    "docs/developer/README.md",
    "docs/developer/architecture-implementation.md",
    "docs/architecture/arc42/07-vue-deploiement.md",
    "docs/architecture/arc42/08-concepts-transverses.md",
    "docs/architecture/arc42/11-risques-dette.md",
    "docs/architecture/risks/register.md",
    "docs/developer/rest-api.md",
    "docs/developer/mcp.md",
    "docs/developer/context-building.md",
    "docs/developer/large-scale-search.md",
    "docs/developer/git-context.md",
    "docs/developer/ai-skills-registry.md",
    "docs/developer/native-context-sources.md",
)
for path in current_docs:
    for stale in ("PR #49", "PR #61", "courant sur `main`", "Quarkus 3.33", "MCP SDK     2.0.0"):
        forbid(path, stale)

print("operational-documentation-contracts=PASS")
PY
