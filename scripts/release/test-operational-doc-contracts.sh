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


# Toolchain / NXA3 baseline
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

# NXA4: management listener must remain separate and loopback-only by default.
app_props = "adapters/rest-quarkus/src/main/resources/application.properties"
for needle in (
    "quarkus.management.enabled=true",
    "quarkus.management.host=127.0.0.1",
    "quarkus.management.port=9000",
):
    require(app_props, needle)
for path in (
    "README.md",
    "docs/architecture.md",
    "docs/architecture/arc42/06-vue-execution.md",
    "docs/architecture/arc42/07-vue-deploiement.md",
    "docs/architecture/arc42/08-concepts-transverses.md",
    "docs/architecture/arc42/10-exigences-qualite.md",
    "docs/developer/current-limitations.md",
    "docs/developer/release-and-recovery.md",
    "docs/developer/rest-api.md",
):
    require(path, "127.0.0.1:9000")
require("docs/developer/rest-api.md", "ne sont pas servis par le listener applicatif")

# NXA4: remote Ollama transport and secret redaction.
semantic_cfg = "src/main/java/com/nexus/search/semantic/SemanticSearchConfiguration.java"
require(semantic_cfg, "NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA")
for path in (
    "README.md",
    "docs/architecture.md",
    "docs/architecture/arc42/06-vue-execution.md",
    "docs/architecture/arc42/07-vue-deploiement.md",
    "docs/architecture/arc42/08-concepts-transverses.md",
    "docs/architecture/arc42/10-exigences-qualite.md",
    "docs/developer/current-limitations.md",
    "docs/developer/release-and-recovery.md",
    "docs/developer/rest-api.md",
    "docs/developer/semantic-search.md",
):
    require(path, "NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA")

require("src/main/java/com/nexus/security/SensitiveContentRedactor.java", "[REDACTED]")
require("src/main/java/com/nexus/search/semantic/SemanticIndexingService.java", "CONTENT_PROFILE_VERSION = 2")
for path in (
    "README.md",
    "docs/architecture.md",
    "docs/architecture/arc42/08-concepts-transverses.md",
    "docs/architecture/arc42/10-exigences-qualite.md",
    "docs/developer/current-limitations.md",
    "docs/developer/release-and-recovery.md",
    "docs/developer/semantic-search.md",
):
    require(path, "content-v2")

# NXA4: external work/JDT framing bounds.
external_runner = "src/main/java/com/nexus/index/ExternalTaskRunner.java"
require(external_runner, "MAX_CONCURRENT_TASKS = 8")
for path in (
    "docs/architecture/arc42/06-vue-execution.md",
    "docs/architecture/arc42/10-exigences-qualite.md",
    "docs/architecture/quality/scenarios.md",
    "docs/developer/jdt-language-server.md",
):
    require(path, "8 tâches externes")

jdt_reader = "src/main/java/com/nexus/index/jdt/JdtJsonRpcFrameReader.java"
for needle in (
    "MAX_MESSAGE_BYTES = 16 * 1024 * 1024",
    "MAX_HEADER_BYTES = 64 * 1024",
    "MAX_HEADER_LINE_BYTES = 8 * 1024",
    "MAX_PENDING_MESSAGES = 256",
):
    require(jdt_reader, needle)
for path in (
    "docs/architecture.md",
    "docs/architecture/arc42/06-vue-execution.md",
    "docs/architecture/arc42/08-concepts-transverses.md",
    "docs/architecture/quality/scenarios.md",
    "docs/developer/code-intelligence.md",
    "docs/developer/current-limitations.md",
    "docs/developer/jdt-language-server.md",
):
    require(path, "256")

# JDT LS installer must trust the repository-pinned anchor, not a same-origin checksum.
install_jdt = text("scripts/install-jdtls.ps1")
for needle in ("config\\tool-integrity.properties", "Get-PinnedHash", "Verification contre l'ancre SHA-256 versionnee"):
    if needle not in install_jdt:
        raise SystemExit(f"JDT LS integrity contract drift: missing {needle!r}")
require("docs/developer/jdt-language-server.md", "ancre versionnée dans le repository")
forbid("docs/developer/jdt-language-server.md", "télécharge le checksum SHA-256 publié")

# NXA4: Lucene high-cardinality query cap.
lucene = "src/main/java/com/nexus/search/lucene/LuceneSearchIndex.java"
require(lucene, "MAX_ANALYZED_QUERY_TERMS = 128")
for path in (
    "README.md",
    "docs/architecture.md",
    "docs/architecture/arc42/06-vue-execution.md",
    "docs/architecture/arc42/08-concepts-transverses.md",
    "docs/architecture/arc42/10-exigences-qualite.md",
    "docs/architecture/quality/scenarios.md",
    "docs/developer/code-intelligence.md",
    "docs/developer/current-limitations.md",
    "docs/developer/large-scale-search.md",
):
    require(path, "128 termes")

# NXA4: persistent storage permissions.
nexus_paths = "src/main/java/com/nexus/config/NexusPaths.java"
require(nexus_paths, 'PosixFilePermissions.fromString("rwx------")')
require(nexus_paths, 'PosixFilePermissions.fromString("rw-------")')
for path in (
    "README.md",
    "docs/architecture.md",
    "docs/architecture/arc42/07-vue-deploiement.md",
    "docs/developer/current-limitations.md",
    "docs/developer/release-and-recovery.md",
):
    require(path, "0700")
    require(path, "0600")

# NXA4: unsupported constraints must fail explicitly.
context_request = "src/main/java/com/nexus/context/ContextRequest.java"
require(context_request, "constraints are not supported yet")
for path in (
    "docs/architecture.md",
    "docs/architecture/arc42/05-vue-blocs.md",
    "docs/architecture/arc42/06-vue-execution.md",
    "docs/architecture/arc42/10-exigences-qualite.md",
    "docs/developer/context-building.md",
    "docs/developer/rest-api.md",
):
    require(path, "constraints")

# Provider names and schema migration checksum naming in the current block view.
require("docs/architecture/arc42/05-vue-blocs.md", "javaparser|jdtls|scip|minos")
require("docs/architecture/arc42/05-vue-blocs.md", "script_sha256")
for stale in ("java_parser|jdt|scip|minos", "string checksum"):
    forbid("docs/architecture/arc42/05-vue-blocs.md", stale)

# Known stale statements that survived the first NXA3 documentation pass.
forbid("docs/developer/code-intelligence.md", "planifié en Itération 19")
forbid("docs/developer/code-intelligence.md", "chargent encore des ensembles projet-wide")
forbid("docs/architecture/arc42/06-vue-execution.md", "continuer sans résultats JDT")
forbid("docs/architecture/arc42/06-vue-execution.md", "indexation réussie sans intelligence profonde")

current_docs = (
    "README.md",
    "docs/roadmap.md",
    "docs/architecture.md",
    "docs/architecture/README.md",
    "docs/architecture/arc42/05-vue-blocs.md",
    "docs/architecture/arc42/06-vue-execution.md",
    "docs/architecture/arc42/07-vue-deploiement.md",
    "docs/architecture/arc42/08-concepts-transverses.md",
    "docs/architecture/arc42/10-exigences-qualite.md",
    "docs/architecture/arc42/11-risques-dette.md",
    "docs/architecture/quality/scenarios.md",
    "docs/architecture/risks/register.md",
    "docs/developer/README.md",
    "docs/developer/architecture-implementation.md",
    "docs/developer/ci-and-supply-chain.md",
    "docs/developer/release-and-recovery.md",
    "docs/developer/rest-api.md",
    "docs/developer/mcp.md",
    "docs/developer/context-building.md",
    "docs/developer/large-scale-search.md",
    "docs/developer/git-context.md",
    "docs/developer/ai-skills-registry.md",
    "docs/developer/native-context-sources.md",
    "docs/developer/code-intelligence.md",
    "docs/developer/jdt-language-server.md",
    "docs/developer/current-limitations.md",
)
for path in current_docs:
    for stale in (
        "PR #49",
        "PR #61",
        "courant sur `main`",
        "Quarkus 3.33",
        "MCP SDK     2.0.0",
        "après la campagne NXA3.",
    ):
        forbid(path, stale)

print("operational-documentation-contracts=PASS")
PY
