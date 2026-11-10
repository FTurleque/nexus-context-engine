#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re
import xml.etree.ElementTree as ET

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


# Dependabot-managed versions live in pom.xml only: the documentation names the property.
pom = ET.parse(root / "pom.xml").getroot()


def pom_property(name: str) -> str:
    value = next(
        (
            element.text.strip()
            for element in pom.iter()
            if element.tag.rsplit("}", 1)[-1] == name and element.text and element.text.strip()
        ),
        None,
    )
    if not value:
        raise SystemExit(f"version contract drift: pom.xml does not declare {name}")
    return value


managed_versions = {
    name: pom_property(name) for name in ("quarkus.platform.version", "mcp.sdk.version")
}


# Toolchain / NXA3 baseline
wrapper = text(".mvn/wrapper/maven-wrapper.properties")
if "apache-maven-3.10.0-bin.zip" not in wrapper:
    raise SystemExit("wrapper contract drift: expected Maven 3.10.0")

for path in (
    "README.md",
    "docs/developer/README.md",
    "docs/developer/release-and-recovery.md",
    "docs/developer/ci-and-supply-chain.md",
):
    require(path, "Maven 3.10.0")

# Core must own a standard Maven layout; the historical root src tree must not return.
if (root / "src").exists():
    raise SystemExit("core layout drift: repository-root src/ must not exist")
for path in (
    "core/src/main/java/com/nexus/application/NexusApplication.java",
    "core/src/main/resources/db/migration/V005__enforce_symbol_range_constraints.sql",
    "core/src/main/resources/db/migration/V006__invalidate_unredacted_lexical_indexes.sql",
    "core/src/main/resources/db/migration/V007__invalidate_legacy_repository_paths.sql",
    "core/src/main/resources/db/migration/V008__indexed_substring_search.sql",
    "core/src/main/resources/db/migration/V009__invalidate_quoted_secret_indexes.sql",
    "core/src/main/resources/db/migration/V010__invalidate_scalar_secret_indexes.sql",
    "core/src/main/resources/db/migration/V011__invalidate_secret_format_indexes.sql",
    "core/src/main/resources/db/migration/V018__invalidate_sk_token_bound_indexes.sql",
    "core/src/main/resources/db/migration/V019__invalidate_token_context_bound_indexes.sql",
    "core/src/test/java/com/nexus/application/NexusApplicationSemanticConfigurationTest.java",
):
    if not (root / path).is_file():
        raise SystemExit(f"core layout drift: missing {path}")
core_pom = text("core/pom.xml")
for stale in ("<sourceDirectory>", "<testSourceDirectory>", "../src/main", "../src/test"):
    if stale in core_pom:
        raise SystemExit(f"core layout drift: core/pom.xml still contains {stale!r}")

migrations = sorted((root / "core/src/main/resources/db/migration").glob("V*.sql"))
if not migrations or migrations[-1].name != "V019__invalidate_token_context_bound_indexes.sql":
    raise SystemExit(f"schema contract drift: latest migration is {migrations[-1].name if migrations else 'none'}")
require("docs/developer/release-and-recovery.md", "V005__enforce_symbol_range_constraints.sql")
require("docs/developer/release-and-recovery.md", "V006__invalidate_unredacted_lexical_indexes.sql")
require("docs/developer/release-and-recovery.md", "V007__invalidate_legacy_repository_paths.sql")
require("docs/developer/release-and-recovery.md", "V008__indexed_substring_search.sql")
for path in (
    "README.md",
    "docs/architecture/overview.md",
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

for path, properties in (
    ("docs/developer/README.md", ("quarkus.platform.version", "mcp.sdk.version")),
    ("docs/developer/architecture-implementation.md", ("quarkus.platform.version", "mcp.sdk.version")),
    ("docs/developer/rest-api.md", ("quarkus.platform.version",)),
    ("docs/developer/mcp.md", ("mcp.sdk.version",)),
):
    for name in properties:
        require(path, name)

version_literal = re.compile(
    r"\b(?:Quarkus|MCP SDK|SDK MCP|MCP Java)(?:\s+(?:est\s+)?en)?[\s:`(]+v?\d+\.\d+(?:\.\d+)*",
    re.IGNORECASE,
)
version_docs = [
    "docs/developer/README.md",
    "docs/developer/architecture-implementation.md",
    "docs/developer/rest-api.md",
    "docs/developer/mcp.md",
    "docs/architecture/overview.md",
    *sorted(
        path.relative_to(root).as_posix()
        for path in (root / "docs/architecture/arc42").glob("*.md")
    ),
]
for path in version_docs:
    content = text(path)
    match = version_literal.search(content)
    if match:
        raise SystemExit(
            f"version contract drift: {path} copies a dependency version ({match.group(0)!r}); "
            "name the pom.xml property instead"
        )
    for name, value in managed_versions.items():
        if re.search(rf"(?<![\w.]){re.escape(value)}(?!\w|\.\w)", content):
            raise SystemExit(
                f"version contract drift: {path} copies {name} value {value!r}; "
                "name the pom.xml property instead"
            )

require("docs/developer/rest-api.md", "quarkus.http.insecure-requests")
require("docs/developer/rest-api.md", "trusted-proxies")
require("docs/developer/mcp.md", "100 projets uniques")

for path in (
    "docs/architecture/overview.md",
    "docs/developer/architecture-implementation.md",
    "docs/developer/context-building.md",
    "docs/developer/large-scale-search.md",
    "docs/developer/mcp.md",
    "docs/developer/rest-api.md",
):
    require(path, "100 projets uniques")

for path in (
    "docs/architecture/overview.md",
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
    "docs/architecture/overview.md",
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
semantic_cfg = "core/src/main/java/com/nexus/search/semantic/SemanticSearchConfiguration.java"
require(semantic_cfg, "NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA")
for path in (
    "README.md",
    "docs/architecture/overview.md",
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

require("core/src/main/java/com/nexus/security/SensitiveContentRedactor.java", "[REDACTED]")
require("core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java", "CONTENT_PROFILE_VERSION = 7")
for path in (
    "README.md",
    "docs/architecture/overview.md",
    "docs/architecture/arc42/08-concepts-transverses.md",
    "docs/architecture/arc42/10-exigences-qualite.md",
    "docs/developer/current-limitations.md",
    "docs/developer/release-and-recovery.md",
    "docs/developer/semantic-search.md",
):
    require(path, "content-v7")

# AUD-SEC-01/AUD-SEC-02/AUD-SEC-20: redaction policy v8, V011, V018 and V019 invalidation, per-file exclusion.
require("core/src/main/java/com/nexus/security/SensitiveContentRedactor.java", '"secret-redaction-v8"')
require("docs/developer/secret-redaction.md", "secret-redaction-v8")
require("docs/developer/secret-redaction.md", "INDEX_REDACTION_LIMIT")
require("docs/developer/secret-redaction.md", "SecretRedactionProbeTest")
require("docs/developer/release-and-recovery.md", "V011__invalidate_secret_format_indexes.sql")
require("docs/developer/release-and-recovery.md", "V012__index_symbol_relations_by_file.sql")
require("docs/developer/release-and-recovery.md", "V013__drop_redundant_prefix_indexes.sql")
require("docs/developer/release-and-recovery.md", "V014__index_staged_analyses.sql")
require("docs/developer/release-and-recovery.md", "V015__index_symbols_by_file_for_fuzzy_search.sql")
require("docs/developer/release-and-recovery.md", "V016__index_staged_files_by_project.sql")
require("docs/developer/release-and-recovery.md", "V017__index_symbols_for_dominant_project_fuzzy_search.sql")
require("docs/developer/release-and-recovery.md", "V018__invalidate_sk_token_bound_indexes.sql")
require("docs/developer/release-and-recovery.md", "V019__invalidate_token_context_bound_indexes.sql")
require("docs/developer/current-limitations.md", "INDEX_REDACTION_LIMIT")

# NXA4 + audit follow-up: lexical content is redacted before Lucene tokenization.
lucene = "core/src/main/java/com/nexus/search/lucene/LuceneSearchIndex.java"
require(lucene, "SensitiveContentRedactor.redact(source.content())")
require("docs/developer/release-and-recovery.md", "anciens segments Lucene")

# NXA4: external work/JDT framing bounds.
external_runner = "core/src/main/java/com/nexus/index/ExternalTaskRunner.java"
require(external_runner, "MAX_CONCURRENT_TASKS = 8")
require(external_runner, "Circuit-breaker ouvert")
require("docs/developer/release-and-recovery.md", "ExternalTaskRunner.status()")
for path in (
    "docs/architecture/arc42/06-vue-execution.md",
    "docs/architecture/arc42/10-exigences-qualite.md",
    "docs/architecture/quality/scenarios.md",
    "docs/developer/jdt-language-server.md",
):
    require(path, "8 tâches externes")

jdt_reader = "core/src/main/java/com/nexus/index/jdt/JdtJsonRpcFrameReader.java"
for needle in (
    "MAX_MESSAGE_BYTES = 16 * 1024 * 1024",
    "MAX_HEADER_BYTES = 64 * 1024",
    "MAX_HEADER_LINE_BYTES = 8 * 1024",
    "MAX_PENDING_MESSAGES = 256",
):
    require(jdt_reader, needle)
for path in (
    "docs/architecture/overview.md",
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
require(lucene, "MAX_ANALYZED_QUERY_TERMS = 128")
for path in (
    "README.md",
    "docs/architecture/overview.md",
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
private_storage = "core/src/main/java/com/nexus/security/PrivateStoragePolicy.java"
require(private_storage, 'PosixFilePermissions.fromString("rwx------")')
require(private_storage, 'PosixFilePermissions.fromString("rw-------")')
require(private_storage, "AclFileAttributeView")
for path in (
    "README.md",
    "docs/architecture/overview.md",
    "docs/architecture/arc42/07-vue-deploiement.md",
    "docs/developer/current-limitations.md",
    "docs/developer/release-and-recovery.md",
):
    require(path, "0700")
    require(path, "0600")

# NXA4: unsupported constraints must fail explicitly.
context_request = "core/src/main/java/com/nexus/context/ContextRequest.java"
require(context_request, "Les contraintes ne sont pas encore prises en charge")
forbid(context_request, "constraints are not supported yet")
for path in (
    "docs/architecture/overview.md",
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

# Import graph neighbourhood (BE-0087): projected on demand, bounded, never cached.
require("docs/developer/search-ranking.md", "GraphNeighborhoodProjection.MAX_NEIGHBOR_EDGES")
require("docs/developer/search-ranking.md", "IndexRepository.findGraphNeighbors")
for stale in ("ProjectGraph cache", "ProjectGraphBuilder", "Graphe dérivé par génération"):
    forbid("docs/developer/search-ranking.md", stale)

current_docs = (
    "README.md",
    "docs/project/governance/ROADMAP.md",
    "docs/architecture/overview.md",
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


# Scale benchmark: the pull_request trigger has no paths filter, so the required
# "Hermetic scale benchmark" check exists on every PR. The detection step's
# WATCHED_PATHS block is the single list of watched paths; the develop push
# caller must watch exactly that list.
def trigger_paths(source: str, event: str) -> list[str] | None:
    paths: list[str] | None = None
    in_on = in_event = in_paths = False
    for raw in source.splitlines():
        line = raw.rstrip()
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent == 0:
            in_on = stripped == "on:"
            in_event = in_paths = False
        elif in_on and indent == 2:
            in_event = stripped == f"{event}:"
            in_paths = False
            if in_event and paths is None:
                paths = []
        elif in_event and indent == 4:
            in_paths = stripped == "paths:"
        elif in_paths and indent == 6 and stripped.startswith("- "):
            paths.append(stripped[2:].strip().strip("'\""))
    return paths


def watched_paths(source: str) -> list[str]:
    paths: list[str] = []
    block_indent = None
    lines = source.splitlines()
    for index, raw in enumerate(lines):
        if raw.strip() == "WATCHED_PATHS: |":
            for item in lines[index + 1:]:
                if not item.strip():
                    break
                indent = len(item) - len(item.lstrip(" "))
                if block_indent is None:
                    block_indent = indent
                if indent != block_indent or block_indent <= len(raw) - len(raw.lstrip(" ")):
                    break
                paths.append(item.strip())
            break
    return paths


def check_scale_benchmark_paths(reusable: str, caller: str) -> None:
    pull_request = trigger_paths(reusable, "pull_request")
    if pull_request is None:
        raise SystemExit("scale benchmark contract drift: pull_request trigger not found")
    if pull_request:
        raise SystemExit("scale benchmark contract drift: pull_request trigger must not filter paths")
    watched = watched_paths(reusable)
    push = trigger_paths(caller, "push") or []
    if not watched or not push:
        raise SystemExit("scale benchmark contract drift: watched paths or push paths not found")
    for name, values in (("WATCHED_PATHS", watched), ("push", push)):
        if len(values) != len(set(values)):
            raise SystemExit(f"scale benchmark contract drift: duplicate {name} paths")
    missing = sorted(set(watched) - set(push))
    extra = sorted(set(push) - set(watched))
    if missing or extra:
        raise SystemExit(
            "scale benchmark contract drift: develop push paths differ from WATCHED_PATHS"
            f" (missing={missing}, extra={extra})"
        )


scale_caller_path = ".github/workflows/develop-push-scale-benchmark.yml"
scale_reusable = text(".github/workflows/scale-benchmark.yml")
scale_caller = text(scale_caller_path)
check_scale_benchmark_paths(scale_reusable, scale_caller)
if scale_caller_path not in watched_paths(scale_reusable):
    raise SystemExit("scale benchmark contract drift: WATCHED_PATHS must include the develop push caller")
if f"'{scale_caller_path}'" not in scale_caller.split("pull_request:", 1)[-1]:
    raise SystemExit("scale benchmark contract drift: caller must run on its own pull_request changes")
if "    name: Hermetic scale benchmark\n" not in scale_reusable:
    raise SystemExit("scale benchmark contract drift: required check name 'Hermetic scale benchmark' changed")
# github.workflow names the caller under workflow_call: the wrapper and the
# direct pull_request run must never share (and cancel) a concurrency group.
if "  group: ${{ github.workflow }}-" not in scale_reusable:
    raise SystemExit("scale benchmark contract drift: concurrency group must start with github.workflow")
drifted_caller = scale_caller.replace("      - 'config/scale-benchmark-protocol'\n", "", 1)
drifted_watched = scale_reusable.replace(
    "            config/scale-benchmark-protocol\n",
    "            config/scale-benchmark-protocol\n            config/drift-fixture\n",
    1,
)
drifted_trigger = scale_reusable.replace(
    "      - main\n  workflow_dispatch:", "      - main\n    paths:\n      - 'core/**'\n  workflow_dispatch:", 1
)
for label, reusable, caller in (
    ("push", scale_reusable, drifted_caller),
    ("WATCHED_PATHS", drifted_watched, scale_caller),
    ("pull_request paths", drifted_trigger, scale_caller),
):
    if (reusable, caller) == (scale_reusable, scale_caller):
        raise SystemExit(f"scale benchmark contract self-test: {label} fixture not found")
    try:
        check_scale_benchmark_paths(reusable, caller)
    except SystemExit:
        pass
    else:
        raise SystemExit(f"scale benchmark contract self-test: {label} drift was not detected")
require("docs/developer/scale-regression-benchmark.md", "conclut en succès sans mesurer")

# SECURITY.md keeps the alert handling procedure, including test fixtures of the redaction policy.
for needle in (
    "## Traitement des alertes de sécurité",
    "### Délai de prise en charge",
    "### Alertes Dependabot",
    "### Alertes de secret scanning",
    "`used_in_tests`",
    "core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java",
):
    require("SECURITY.md", needle)
if not (root / "core/src/test/java/com/nexus/security/SensitiveContentRedactorTest.java").is_file():
    raise SystemExit("security contract drift: redaction fixture path cited by SECURITY.md is missing")

# --- Sprint 10 : repository governance (AUD-DEP-16, AUD-DEP-17) ---
# The merge-commit-only policy is documented with the GitHub settings that enforce it.
for needle in (
    "## Méthode d'intégration : merge commit uniquement",
    'allowed_merge_methods = ["merge"]',
    "allow_squash_merge = false",
    "allow_rebase_merge = false",
    "rulesets/20503835",
    ".github/CODEOWNERS",
):
    require("docs/developer/branch-governance.md", needle)
for needle in ('allowed_merge_methods = ["merge"]', "jamais squash/rebase", ".github/CODEOWNERS", "SECURITY.md"):
    require(".claude/skills/nexus-pr-workflow/SKILL.md", needle)

# SECURITY.md opens a private reporting channel, with supported versions and a response delay.
for needle in (
    "## Signaler une vulnérabilité",
    "security/advisories/new",
    "pas d'issue publique",
    "### Délai de réponse",
    "## Versions prises en charge",
):
    require("SECURITY.md", needle)

# CODEOWNERS declares an owner for every sensitive path (design D6).
codeowners = {}
for line in text(".github/CODEOWNERS").splitlines():
    fields = line.split("#", 1)[0].split()
    if len(fields) >= 2:
        codeowners[fields[0]] = fields[1:]
for pattern in (
    "/.github/workflows/",
    "/scripts/release/",
    "/config/tool-integrity.properties",
    "/mvnw",
    "/mvnw.cmd",
    "/.mvn/",
    "/packaging/",
):
    owners = codeowners.get(pattern, [])
    if not owners or not all(owner.startswith("@") for owner in owners):
        raise SystemExit(f"CODEOWNERS contract drift: {pattern} must have a declared owner")

# Active PowerShell scripts call Maven only through scripts/lib/NexusScript.psm1 (AUD-QUA-05).
import re

SHARED_SCRIPT_FUNCTION = re.compile(
    r"^\s*function\s+(Invoke-Maven|Resolve-AbsolutePath|Invoke-NexusJson|Select-MavenCommand)\b",
    re.IGNORECASE,
)
MAVEN_TOKEN = r"(?:\.\.?[\\/])?(?:[\w$:.\\/-]*[\\/])?mvnw?(?:\.cmd)?"
DIRECT_MAVEN_CALL = (
    re.compile(r"&\s*['\"]?" + MAVEN_TOKEN + r"['\"]?(?:\s|$)", re.IGNORECASE),
    re.compile(r"^\s*" + MAVEN_TOKEN + r"\s", re.IGNORECASE),
    re.compile(r"Get-Command\s+(?:-Name\s+)?['\"]?mvn", re.IGNORECASE),
    re.compile(r"Start-Process\b.*\bmvnw?(?:\.cmd)?\b", re.IGNORECASE),
    re.compile(r"Join-Path\b.*['\"](?:\.\.[\\/])*mvnw?(?:\.cmd)?['\"]", re.IGNORECASE),
    # Indirection (AUD-QUA-21): a variable holding exactly a Maven command name,
    # cmd /c, and Invoke-Expression/iex of a string that names Maven. A message
    # that quotes the wrapper inside a sentence is not a whole-string literal.
    re.compile(r"\$[\w:]+\s*=\s*(['\"])" + MAVEN_TOKEN + r"\1", re.IGNORECASE),
    re.compile(r"\bcmd(?:\.exe)?['\"]?\s+/c\b.*\bmvnw?(?:\.cmd)?\b", re.IGNORECASE),
    re.compile(r"(?:\bInvoke-Expression\b|(?<![\w-])iex\b).*\bmvnw?(?:\.cmd)?\b", re.IGNORECASE),
)
# Explicit exceptions: (script path, exact trimmed line). Each entry must still
# match a line of its script, so a stale exception fails the contract.
MAVEN_CALL_EXCEPTIONS: set[tuple[str, str]] = set()


def powershell_maven_violations(relative: str, source: str) -> list[str]:
    violations = []
    for number, line in enumerate(source.splitlines(), 1):
        code = line.split("#", 1)[0] if line.lstrip().startswith("#") else line
        if not code.strip():
            continue
        if SHARED_SCRIPT_FUNCTION.search(code):
            violations.append(f"{relative}:{number}: redefines a NexusScript.psm1 function")
        if (relative, code.strip()) in MAVEN_CALL_EXCEPTIONS:
            continue
        if any(pattern.search(code) for pattern in DIRECT_MAVEN_CALL):
            violations.append(f"{relative}:{number}: calls Maven directly instead of Invoke-Maven")
    return violations


def active_powershell_scripts() -> list[Path]:
    scripts_root = root / "scripts"
    excluded = (scripts_root / "lib", scripts_root / "archive")
    return sorted(
        path
        for pattern in ("*.ps1", "*.psm1")
        for path in scripts_root.rglob(pattern)
        if not any(parent in excluded for parent in path.parents)
    )


maven_violations = []
for script in active_powershell_scripts():
    maven_violations.extend(
        powershell_maven_violations(script.relative_to(root).as_posix(), script.read_text(encoding="utf-8"))
    )
if maven_violations:
    raise SystemExit("powershell maven contract drift:\n" + "\n".join(maven_violations))
for excepted_script, excepted_line in sorted(MAVEN_CALL_EXCEPTIONS):
    lines = [line.strip() for line in text(excepted_script).splitlines()]
    if excepted_line not in lines:
        raise SystemExit(f"powershell maven contract drift: stale exception {excepted_script}: {excepted_line}")
for label, fixture in (
    ("installed mvn call", "Push-Location $root\n& mvn @arguments\n"),
    ("installed mvn.cmd call", "    & mvn.cmd -q package\n"),
    ("direct wrapper call", "    & .\\mvnw.cmd -B test\n"),
    ("quoted wrapper call", "    & '.\\mvnw.cmd' @mavenArgs\n"),
    ("bare command", "mvn clean install\n"),
    ("wrapper path variable", "$w = (Resolve-Path (Join-Path $PSScriptRoot '..\\mvnw.cmd')).Path\n"),
    ("installed lookup", "$m = Get-Command mvn -ErrorAction SilentlyContinue\n"),
    ("local Invoke-Maven", "function Invoke-Maven {\n}\n"),
    ("local Resolve-AbsolutePath", "function Resolve-AbsolutePath {\n}\n"),
    ("command in a variable", '$m = "mvn"; & $m clean install\n'),
    ("wrapper in a variable", "    $wrapper = '.\\mvnw.cmd'\n"),
    ("cmd /c", "cmd /c mvn clean install\n"),
    ("cmd.exe /c", "& cmd.exe /c \"mvnw.cmd -B verify\"\n"),
    ("Invoke-Expression", 'Invoke-Expression "mvn clean install"\n'),
    ("iex", "iex \"$PSScriptRoot\\..\\mvnw.cmd test\"\n"),
):
    if not powershell_maven_violations(f"fixture/{label}.ps1", fixture):
        raise SystemExit(f"powershell maven contract self-test: {label} was not detected")
for label, fixture in (
    ("module call", "Invoke-Maven -Arguments @('-q', 'package')\n"),
    ("comment", "# & mvn clean install\n"),
    ("message", 'Write-Error "Lancez d\'abord : .\\\\mvnw.cmd clean package"\n'),
    ("message in a variable", '$hint = "Lancez d\'abord : .\\\\mvnw.cmd clean package"\n'),
    ("test fixture name", "Write-FakeMaven -Directory $root -BaseName 'mvnw' -VersionExitCode 0\n"),
):
    if powershell_maven_violations(f"fixture/{label}.ps1", fixture):
        raise SystemExit(f"powershell maven contract self-test: {label} was rejected")
require(
    "scripts/lib/NexusScript.psm1",
    "Export-ModuleMember -Function Invoke-Maven, Resolve-AbsolutePath, Invoke-NexusJson",
)
require("scripts/lib/NexusScript.psm1", "exige le Maven Wrapper verifie")
require(".github/workflows/ci.yml", "scripts/tests/Test-NexusScript.ps1")
for retired in range(9, 18):
    if retired == 15:
        continue
    if (root / f"scripts/validate-iteration-{retired}.ps1").exists():
        raise SystemExit(f"script archive drift: scripts/validate-iteration-{retired}.ps1 must live in scripts/archive/")
require("scripts/archive/README.md", "non maintenus")
require("docs/developer/ci-and-supply-chain.md", "Les seuls points d'entrée de validation sont `scripts/validate-phase-6.ps1`")

print("operational-documentation-contracts=PASS")
PY

# Workflow hygiene: no input interpolated in run: blocks, a timeout on every
# non-reusable job, versioned runner images for benchmarks. Each check must
# also reject its negative fixture.
HYGIENE="scripts/release/workflow_hygiene.py"
HYGIENE_FIXTURES="scripts/release/fixtures/workflow-hygiene"
for check in forms inputs timeouts runners; do
  python3 "$HYGIENE" "$check" .github/workflows .github/actions
done
# A fixture counts as rejected only with exit code 1 and the message of its rule.
for case in \
  "forms|forms-flow-mapping-job.yml|job 'b' is not a block mapping" \
  "forms|forms-quoted-key.yml|quoted key 'runs-on'" \
  "forms|forms-run-plain-next-line.yml|run: followed by a plain scalar" \
  "inputs|inputs-in-run.yml|interpolated inside a run" \
  "inputs|inputs-inline-run.yml|interpolated inside a run" \
  "inputs|inputs-event-inputs.yml|interpolated inside a run" \
  "inputs|inputs-github-script.yml|inputs-github-script.yml:17: an input is interpolated" \
  "inputs|inputs-composite-action|inputs-composite-action/action.yml:14: an input is interpolated" \
  "timeouts|timeout-missing.yml|no timeout-minutes" \
  "runners|runners/scale-benchmark.yml|unpinned image"; do
  IFS='|' read -r check fixture message <<< "$case"
  set +e
  output="$(python3 "$HYGIENE" "$check" "$HYGIENE_FIXTURES/$fixture" 2>&1)"
  status=$?
  set -e
  if [[ "$status" -ne 1 ]] || ! grep -q "$message" <<< "$output"; then
    echo "workflow hygiene fixture $fixture was not rejected by the $check check for '$message' (exit $status)" >&2
    exit 1
  fi
done

# Coverage floors (coverage-gates): every JaCoCo floor has a published reference
# measure and sits at most 3 points under it. The build reports are checked by
# NEXUS CI after the Linux and Windows reactor builds; here only the pom.xml files and the
# page are compared, and each negative fixture must be rejected by its own rule.
COVERAGE_FLOORS="scripts/release/coverage_floors.py"
COVERAGE_FIXTURES="scripts/release/fixtures/coverage-floors"
python3 "$COVERAGE_FLOORS" --skip-reports
for case in \
  "floor-too-low|points under the reference measure" \
  "floor-without-measure|has no row in the reference measure table" \
  "rule-typo|com.nexus.api.NexusRestTransportPolicyTypo: the rule matches no class"; do
  IFS='|' read -r fixture message <<< "$case"
  set +e
  output="$(python3 "$COVERAGE_FLOORS" --root "$COVERAGE_FIXTURES/$fixture" --modules adapters/rest-quarkus \
    --doc coverage.md --platform linux --reports-dir "$COVERAGE_FIXTURES/reports" 2>&1)"
  status=$?
  set -e
  if [[ "$status" -ne 1 ]] || ! grep -qF "$message" <<< "$output"; then
    echo "coverage floors fixture $fixture was not rejected for '$message' (exit $status)" >&2
    exit 1
  fi
done

# --- Sprint 9 : attest-prerelease (AUD-DEP-09) ---
# The consumer verification of published assets stays documented in both release pages.
for doc in docs/developer/immutable-release-publishing.md docs/developer/release-and-recovery.md; do
  if ! grep -Fq 'gh attestation verify <asset> --repo FTurleque/nexus-context-engine' "$doc"; then
    echo "documentation contract drift: $doc must document consumer attestation verification" >&2
    exit 1
  fi
done
if ! grep -Fq '## Vérification consommateur' docs/developer/immutable-release-publishing.md; then
  echo "documentation contract drift: immutable-release-publishing.md must keep its consumer verification section" >&2
  exit 1
fi

# --- Sprint 10 : conteneur (AUD-DEP-04, AUD-SEC-08, AUD-DEP-08, AUD-DEP-18) ---
python3 - <<'PY'
import re
from pathlib import Path

root = Path.cwd()


def text(path: str) -> str:
    target = root / path
    if not target.is_file():
        raise SystemExit(f"docker contract drift: {path} is missing")
    return target.read_text(encoding="utf-8")


def fail(message: str) -> None:
    raise SystemExit(f"docker contract drift: {message}")


# Root owns the image code: no COPY --chown towards /opt/nexus or /usr/local/bin, and the
# runtime UID only owns its data directories.
chown_copy = re.compile(r"^\s*COPY\b[^\n]*--chown[^\n]*\s/(?:opt/nexus|usr/local/bin)\b", re.MULTILINE)
for dockerfile in ("packaging/docker/Dockerfile", "packaging/docker/Dockerfile.runtime"):
    source = text(dockerfile)
    if chown_copy.search(source):
        fail(f"{dockerfile} copies into /opt/nexus or /usr/local/bin with --chown")
    if re.search(r"chown[^\n]*/opt/nexus", source):
        fail(f"{dockerfile} gives /opt/nexus to the runtime UID")
    for needle in (
        "chown 10001:10001 /data/nexus /workspace",
        "chmod -R 0644 /opt/nexus",
        "chmod -R a+X /opt/nexus",
        "/opt/nexus/LICENSE",
        "/opt/nexus/THIRD_PARTY_NOTICES.txt",
        'org.opencontainers.image.source="https://github.com/FTurleque/nexus-context-engine"',
        'org.opencontainers.image.licenses="LicenseRef-NEXUS-Proprietary"',
        'org.opencontainers.image.revision="${NEXUS_IMAGE_REVISION}"',
        'org.opencontainers.image.version="${NEXUS_IMAGE_VERSION}"',
        "HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=6",
        'CMD ["/usr/local/bin/nexus-healthcheck"]',
        "USER 10001:10001",
    ):
        if needle not in source:
            fail(f"{dockerfile} must contain {needle!r}")

# The negative fixture proves the --chown check is not vacuous.
if not chown_copy.search("COPY --chown=10001:10001 lib/nexus-cli.jar /opt/nexus/lib/nexus-cli.jar\n"):
    fail("the --chown check no longer rejects its negative fixture")

# One ignore file per Dockerfile: a denylist at the root, an allowlist for the runtime image.
root_ignore = [line.strip() for line in text(".dockerignore").splitlines()]
for entry in (".git", "**/target", "**/.env", "**/.env.*", "**/nexus-data", "workspace",
              "docs/project/audits", ".claude/worktrees"):
    if entry not in root_ignore:
        fail(f".dockerignore must exclude {entry}")
for entry in ("pom.xml", "mvnw", ".mvn", "config", "core", "adapters", "distribution", "LICENSE"):
    if entry in root_ignore:
        fail(f".dockerignore must not exclude {entry}, read by ./mvnw -B -DskipTests package")
runtime_ignore = [
    line.strip()
    for line in text("packaging/docker/Dockerfile.runtime.dockerignore").splitlines()
    if line.strip() and not line.lstrip().startswith("#")
]
if not runtime_ignore or runtime_ignore[0] != "*":
    fail("Dockerfile.runtime.dockerignore must start with * (allowlist)")
if any(line.startswith("!docker/") and not line.endswith(".sh") for line in runtime_ignore) or "!docker/" in runtime_ignore:
    fail("Dockerfile.runtime.dockerignore must only re-include the two docker/ scripts")
for entry in ("!lib/nexus-cli.jar", "!rest/", "!LICENSE", "!THIRD_PARTY_NOTICES.txt",
              "!docker/nexus-container-entrypoint.sh", "!docker/nexus-container-healthcheck.sh"):
    if entry not in runtime_ignore:
        fail(f"Dockerfile.runtime.dockerignore must re-include {entry}")
for script in ("scripts/release/build-windows-distribution.ps1", "scripts/release/build-windows-installer.ps1",
               ".github/workflows/docker-distribution.yml"):
    if "Dockerfile.runtime.dockerignore" not in text(script):
        fail(f"{script} must ship Dockerfile.runtime.dockerignore with the runtime payload")

# CI passes the OCI revision and version labels.
workflow = text(".github/workflows/docker-distribution.yml")
for needle in ('--build-arg NEXUS_IMAGE_REVISION="$NEXUS_HEAD_SHA"', '--build-arg NEXUS_IMAGE_VERSION="$NEXUS_IMAGE_VERSION"',
               "{{.State.Health.Status}}", "- '.dockerignore'", "name: Smoke hardened Compose runtime",
               "Dockerfile.probe.dockerignore"):
    if needle not in workflow:
        fail(f"docker-distribution.yml must contain {needle!r}")

# Compose runtime hardening.
compose = text("packaging/docker/docker-compose.yml.template")
for pattern, label in (
    (r"^\s{4}read_only:\s*true\s*$", "read_only: true"),
    # exec: SQLite and JNA load their extracted native libraries from /tmp.
    (r"^\s{4}tmpfs:\s*\n\s+-\s*\"?/tmp:exec\b", "tmpfs: [/tmp:exec]"),
    (r"^\s{4}cap_drop:\s*\n\s+-\s*ALL\s*$", "cap_drop: [ALL]"),
    (r"^\s{4}security_opt:\s*\n\s+-\s*\"?no-new-privileges:true\"?\s*$", "security_opt: [no-new-privileges:true]"),
):
    if not re.search(pattern, compose, re.MULTILINE):
        fail(f"docker-compose.yml.template must declare {label}")

# Current Docker documentation describes the image and runtime hardening.
doc = text("docs/user/docker-installation.md")
for needle in ("Dockerfile.runtime.dockerignore", ".dockerignore", "read_only: true", "cap_drop",
               "no-new-privileges:true", "HEALTHCHECK", "/opt/nexus/THIRD_PARTY_NOTICES.txt",
               "org.opencontainers.image.revision", "appartiennent à root"):
    if needle not in doc:
        fail(f"docs/user/docker-installation.md must mention {needle!r}")

print("docker-image-hardening-contracts=PASS")
PY
# --- Sprint 11 : documentation SQLite et instruction primaire (AUD-QUA-13, AUD-QUA-03, AUD-PRF-10) ---
# The FTS5 trigram index shipped by V008 must not be described as a future engine.
if grep -Fq 'nouveau moteur FTS/trigram' docs/developer/current-limitations.md; then
  echo "obsolete documentation contract: current-limitations.md still describes FTS/trigram as a future engine (V008 ships it)" >&2
  exit 1
fi
if ! grep -Fq 'FTS5 trigram livré par la migration V008' docs/developer/current-limitations.md; then
  echo "documentation contract drift: current-limitations.md must scope the watch item beyond the V008 FTS5 trigram index" >&2
  exit 1
fi
# AGENTS.md is the primary agent instruction: Maven only through the verified wrapper.
if grep -Eq '(^|[^A-Za-z0-9_./\-])mvn[[:space:]]' AGENTS.md; then
  echo "documentation contract drift: AGENTS.md must call Maven through ./mvnw (or .\mvnw.cmd), never mvn directly" >&2
  exit 1
fi
if ! grep -Fq '`./mvnw clean install`' AGENTS.md; then
  echo "documentation contract drift: AGENTS.md must prescribe ./mvnw clean install" >&2
  exit 1
fi
# The rollback journal decision (DATA-0088) is referenced by its measures, its benchmark rule and its test.
JOURNAL_ADR='DATA-0088-conserver-le-journal-rollback-sqlite.md'
if [[ ! -f "docs/architecture/adr/$JOURNAL_ADR" ]]; then
  echo "documentation contract drift: missing ADR docs/architecture/adr/$JOURNAL_ADR" >&2
  exit 1
fi
for doc in docs/project/history/scale-regression-results.md docs/developer/scale-regression-benchmark.md; do
  if ! grep -Fq "$JOURNAL_ADR" "$doc"; then
    echo "documentation contract drift: $doc must link the rollback journal decision $JOURNAL_ADR" >&2
    exit 1
  fi
done
if ! grep -Fq 'PRAGMA journal_mode' core/src/test/java/com/nexus/persistence/sqlite/SqliteDatabaseHardeningTest.java \
  || ! grep -Fq 'DATA-0088' core/src/test/java/com/nexus/persistence/sqlite/SqliteDatabaseHardeningTest.java; then
  echo "documentation contract drift: SqliteDatabaseHardeningTest must assert PRAGMA journal_mode and cite DATA-0088" >&2
  exit 1
fi
