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
    value = text(path)
    if needle not in value:
        raise SystemExit(f"documentation contract drift: {path} must contain {needle!r}")


def forbid(path: str, needle: str) -> None:
    value = text(path)
    if needle in value:
        raise SystemExit(f"obsolete documentation contract: {path} still contains {needle!r}")

wrapper = text(".mvn/wrapper/maven-wrapper.properties")
if "apache-maven-3.9.16-bin.zip" not in wrapper:
    raise SystemExit("wrapper contract drift: expected Maven 3.9.16")

for path in (
    "README.md",
    "docs/developer/release-and-recovery.md",
    "docs/developer/ci-and-supply-chain.md",
):
    require(path, "Maven 3.9.16")

migrations = sorted((root / "src/main/resources/db/migration").glob("V*.sql"))
if not migrations or migrations[-1].name != "V005__enforce_symbol_range_constraints.sql":
    raise SystemExit(f"schema contract drift: latest migration is {migrations[-1].name if migrations else 'none'}")
require("docs/developer/release-and-recovery.md", "V005__enforce_symbol_range_constraints.sql")
require("docs/developer/release-and-recovery.md", "start_line >= 1")
require("docs/developer/release-and-recovery.md", "end_line >= start_line")

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

print("operational-documentation-contracts=PASS")
PY
