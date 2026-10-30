#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
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


# Keep operational documentation aligned with the dependency actually used by Maven.
pom = ET.parse(root / "pom.xml").getroot()
quarkus_version = next(
    (
        element.text.strip()
        for element in pom.iter()
        if element.tag.rsplit("}", 1)[-1] == "quarkus.platform.version"
        and element.text
    ),
    None,
)
if not quarkus_version:
    raise SystemExit("version contract drift: pom.xml does not declare quarkus.platform.version")


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
    "core/src/test/java/com/nexus/application/NexusApplicationSemanticConfigurationTest.java",
):
    if not (root / path).is_file():
        raise SystemExit(f"core layout drift: missing {path}")
core_pom = text("core/pom.xml")
for stale in ("<sourceDirectory>", "<testSourceDirectory>", "../src/main", "../src/test"):
    if stale in core_pom:
        raise SystemExit(f"core layout drift: core/pom.xml still contains {stale!r}")

migrations = sorted((root / "core/src/main/resources/db/migration").glob("V*.sql"))
if not migrations or migrations[-1].name != "V010__invalidate_scalar_secret_indexes.sql":
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

require("docs/developer/README.md", f"Quarkus      {quarkus_version}")
require("docs/developer/architecture-implementation.md", f"Quarkus est en {quarkus_version}")
require("docs/developer/rest-api.md", f"Quarkus     {quarkus_version}")
require("docs/developer/rest-api.md", "quarkus.http.insecure-requests")
require("docs/developer/rest-api.md", "trusted-proxies")
require("docs/developer/mcp.md", "MCP SDK     2.1.0")
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
require("core/src/main/java/com/nexus/search/semantic/SemanticIndexingService.java", "CONTENT_PROFILE_VERSION = 4")
for path in (
    "README.md",
    "docs/architecture/overview.md",
    "docs/architecture/arc42/08-concepts-transverses.md",
    "docs/architecture/arc42/10-exigences-qualite.md",
    "docs/developer/current-limitations.md",
    "docs/developer/release-and-recovery.md",
    "docs/developer/semantic-search.md",
):
    require(path, "content-v4")

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
nexus_paths = "core/src/main/java/com/nexus/config/NexusPaths.java"
require(nexus_paths, 'PosixFilePermissions.fromString("rwx------")')
require(nexus_paths, 'PosixFilePermissions.fromString("rw-------")')
require(nexus_paths, "AclFileAttributeView")
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
require(context_request, "constraints are not supported yet")
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
)


def powershell_maven_violations(relative: str, source: str) -> list[str]:
    violations = []
    for number, line in enumerate(source.splitlines(), 1):
        code = line.split("#", 1)[0] if line.lstrip().startswith("#") else line
        if not code.strip():
            continue
        if SHARED_SCRIPT_FUNCTION.search(code):
            violations.append(f"{relative}:{number}: redefines a NexusScript.psm1 function")
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
):
    if not powershell_maven_violations(f"fixture/{label}.ps1", fixture):
        raise SystemExit(f"powershell maven contract self-test: {label} was not detected")
for label, fixture in (
    ("module call", "Invoke-Maven -Arguments @('-q', 'package')\n"),
    ("comment", "# & mvn clean install\n"),
    ("message", 'Write-Error "Lancez d\'abord : .\\\\mvnw.cmd clean package"\n'),
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
