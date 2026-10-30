#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "final-audit-contracts: $*" >&2
  exit 1
}

REST_SECURITY="adapters/rest-quarkus/src/main/java/com/nexus/api/NexusRestSecurity.java"
REST_GUARD="adapters/rest-quarkus/src/main/java/com/nexus/api/NexusRestExposureGuard.java"
REST_DOC="docs/developer/rest-api.md"

for stale in \
  'isStrongRemoteToken' \
  'MIN_REMOTE_TOKEN_ESTIMATED_ENTROPY_BITS' \
  'estimatedShannonEntropyBits'; do
  if grep -R -n --fixed-strings "$stale" adapters/rest-quarkus/src; then
    fail "obsolete REST token entropy identifier remains: $stale"
  fi
done

grep -q 'meetsRemoteTokenPolicy' "$REST_SECURITY" \
  || fail 'structural REST token policy is missing'
grep -q 'cryptographic entropy estimate' "$REST_SECURITY" \
  || fail 'REST token policy must explicitly reject entropy overclaiming'
grep -q 'CSPRNG' "$REST_GUARD" \
  || fail 'REST exposure guard must require CSPRNG generation guidance'
grep -q "ne mesure pas l'entropie cryptographique" "$REST_DOC" \
  || fail 'REST documentation must distinguish structural filtering from entropy'

# Post-audit 2026-09-07: JDT LS must never start on an implicitly trusted repository.
JDT_PROCESS="core/src/main/java/com/nexus/index/jdt/ProcessBuilder.java"
grep -q 'NEXUS_JDTLS_TRUSTED_PROJECT_ROOTS' "$JDT_PROCESS" \
  || fail 'JDT LS trusted-project allowlist is missing'
grep -q 'requireTrustedProjectDirectory' "$JDT_PROCESS" \
  || fail 'JDT LS must validate canonical project trust immediately before start'
grep -q 'NEXUS_JDTLS_TRUSTED_PROJECT_ROOTS' docs/developer/jdt-language-server.md \
  || fail 'JDT LS trust boundary must remain documented'
grep -q 'NEXUS_JDTLS_TRUSTED_PROJECT_ROOTS' scripts/compare-jdt.ps1 \
  || fail 'real JDT qualification must exercise the trusted-project boundary'

# Post-audit 2026-09-07: decoded code-intelligence metadata is bounded independently
# from transport size, and SCIP transport configuration may only tighten the ceilings.
METADATA_POLICY="core/src/main/java/com/nexus/index/CodeIntelligenceMetadataPolicy.java"
SNAPSHOT="core/src/main/java/com/nexus/index/CodeIntelligenceSnapshot.java"
SCIP_LIMITS="core/src/main/java/com/nexus/index/scip/ScipIndexLimits.java"
for needle in \
  'MAX_SNAPSHOT_SYMBOLS = 100_000' \
  'MAX_SNAPSHOT_RELATIONS = 250_000' \
  'MAX_SNAPSHOT_METADATA_UTF8_BYTES = 64L * 1024L * 1024L'; do
  grep -q --fixed-strings "$needle" "$METADATA_POLICY" \
    || fail "decoded code-intelligence metadata contract drift: missing $needle"
done
grep -q 'validateSnapshotInput' "$SNAPSHOT" \
  || fail 'snapshot must validate decoded metadata before canonicalization'
grep -q 'MAX_CONFIGURABLE_INDEX_BYTES = DEFAULT_MAX_INDEX_BYTES' "$SCIP_LIMITS" \
  || fail 'SCIP index transport ceiling must not be expandable through environment'
grep -q 'MAX_CONFIGURABLE_MESSAGE_BYTES = DEFAULT_MAX_MESSAGE_BYTES' "$SCIP_LIMITS" \
  || fail 'SCIP message transport ceiling must not be expandable through environment'
grep -q 'métadonnées UTF-8 cumulées/snapshot' docs/developer/code-intelligence.md \
  || fail 'decoded metadata ceiling must remain documented'

# Post-audit 2026-09-07: Windows ACL inspection covers every sensitive path,
# system principals are resolved from well-known SIDs and compared exactly, and
# operators can require fail-closed privacy without relying on translated names.
NEXUS_PATHS="core/src/main/java/com/nexus/config/NexusPaths.java"
WINDOWS_PRINCIPALS="core/src/main/java/com/nexus/config/WindowsStoragePrincipals.java"
grep -q 'canonicalCurrentUserPrincipal' "$NEXUS_PATHS" \
  || fail 'Windows ACL inspection must resolve the current principal canonically'
grep -q 'trustedSystemNames.contains(principal)' "$NEXUS_PATHS" \
  || fail 'Windows trusted system principals must be compared exactly after SID resolution'
for sid in 'S-1-5-18' 'S-1-5-32-544' 'S-1-3-0'; do
  grep -q --fixed-strings "$sid" "$WINDOWS_PRINCIPALS" \
    || fail "Windows well-known principal SID must remain pinned: $sid"
done
grep -q 'getUserPrincipalLookupService' "$WINDOWS_PRINCIPALS" \
  || fail 'Windows well-known SIDs must resolve through the filesystem principal lookup service'
grep -q 'NEXUS_REQUIRE_PRIVATE_STORAGE' "$NEXUS_PATHS" \
  || fail 'private storage fail-closed opt-in is missing'
grep -q 'enforceAclPrivacy' "$NEXUS_PATHS" \
  || fail 'private storage ACL enforcement must remain centralized'
if grep -q 'endsWith("\\\\ADMINISTRATORS")' "$NEXUS_PATHS" "$WINDOWS_PRINCIPALS"; then
  fail 'suffix-based Administrators trust must not return'
fi
if grep -q 'endsWith("\\\\" + currentUser)' "$NEXUS_PATHS" "$WINDOWS_PRINCIPALS"; then
  fail 'suffix-based current-user ACL trust must not return'
fi

# On filesystems without SecureDirectoryStream, fallback path identity is captured
# before open and revalidated after open so visible component replacement fails closed.
SAFE_FILE_IO="core/src/main/java/com/nexus/security/SafeFileIO.java"
grep -q 'captureFallbackPathSnapshot' "$SAFE_FILE_IO" \
  || fail 'fallback filesystem identity snapshot is missing'
grep -q 'revalidateFallbackPathSnapshot' "$SAFE_FILE_IO" \
  || fail 'fallback filesystem identity must be revalidated after open'
grep -q 'attributes.fileKey()' "$SAFE_FILE_IO" \
  || fail 'fallback filesystem revalidation must use provider identity when available'

# Post-audit 2026-09-10: task-context materialization must be bounded by physical
# operations and elapsed time in addition to cumulative bytes. A corpus of empty
# files must never bypass the work budget.
MATERIALIZATION_LIMITS="core/src/main/java/com/nexus/context/ContextMaterializationLimits.java"
MATERIALIZATION_BUDGET="core/src/main/java/com/nexus/context/ContextMaterializationBudget.java"
for needle in \
  'NEXUS_MAX_CONTEXT_MATERIALIZATION_BYTES' \
  'NEXUS_MAX_CONTEXT_MATERIALIZATION_FILES' \
  'NEXUS_MAX_CONTEXT_MATERIALIZATION_MILLIS' \
  'DEFAULT_MAX_OPENED_FILES = 10_000' \
  'DEFAULT_MAX_DURATION_MILLIS = 30_000L'; do
  grep -q --fixed-strings "$needle" "$MATERIALIZATION_LIMITS" \
    || fail "context materialization hardening contract drift: missing $needle"
done
grep -q 'consumeOpen(path)' "$MATERIALIZATION_BUDGET" \
  || fail 'context materialization must charge every physical file-open attempt'
grep -q 'checkpoint(path)' "$MATERIALIZATION_BUDGET" \
  || fail 'context materialization must enforce an elapsed-time deadline'
grep -q 'NEXUS_MAX_CONTEXT_MATERIALIZATION_FILES' docs/developer/context-building.md \
  || fail 'materialization file-open ceiling must remain documented'
grep -q 'NEXUS_MAX_CONTEXT_MATERIALIZATION_MILLIS' docs/developer/context-building.md \
  || fail 'materialization deadline must remain documented'

# Post-audit 2026-09-07: the provider circuit breaker check/start transition is
# linearized against timeout quarantine; the historical stale-check race must not return.
EXTERNAL_RUNNER="core/src/main/java/com/nexus/index/ExternalTaskRunner.java"
grep -q 'ReentrantReadWriteLock' "$EXTERNAL_RUNNER" \
  || fail 'external provider circuit breaker must use a linearization lock'
grep -q 'CIRCUIT_LOCK_STRIPES = 128' "$EXTERNAL_RUNNER" \
  || fail 'external provider circuit breaker lock striping contract drift'
grep -q 'startLock.lock()' "$EXTERNAL_RUNNER" \
  || fail 'external provider start must participate in the circuit lock'
grep -q 'timeoutLock.lock()' "$EXTERNAL_RUNNER" \
  || fail 'timeout quarantine must participate in the circuit lock'

# Real semantic quality is expensive, so it runs on semantic-surface PRs plus a
# periodic/manual cadence. Every run must qualify the exact head with pinned runtime
# and a stable expected model manifest prefix.
SEMANTIC_WORKFLOW=".github/workflows/semantic-search-qualification.yml"
test -f "$SEMANTIC_WORKFLOW" || fail 'real semantic qualification workflow is missing'
grep -q '^  pull_request:$' "$SEMANTIC_WORKFLOW" \
  || fail 'semantic changes must trigger real qualification on pull request'
grep -q 'NEXUS_HEAD_SHA:' "$SEMANTIC_WORKFLOW" \
  || fail 'semantic qualification must resolve an exact head SHA'
grep -q 'Verify exact checkout' "$SEMANTIC_WORKFLOW" \
  || fail 'semantic qualification must verify its exact checkout'
grep -q "OLLAMA_VERSION: '0.33.3'" "$SEMANTIC_WORKFLOW" \
  || fail 'real semantic qualification must use the repository-pinned Ollama version'
grep -q "OLLAMA_MODEL_ID_PREFIX: 'ac6da0dfba84'" "$SEMANTIC_WORKFLOW" \
  || fail 'real semantic qualification must lock the expected model manifest prefix'
grep -q 'RealSemanticSearchBenchmarkTest' "$SEMANTIC_WORKFLOW" \
  || fail 'real semantic qualification must execute the real retrieval benchmark'
grep -q 'Enforce semantic quality floor' "$SEMANTIC_WORKFLOW" \
  || fail 'real semantic qualification must enforce a regression floor'
grep -q 'Summarize embedding throughput' "$SEMANTIC_WORKFLOW" \
  || fail 'real semantic qualification must retain measured embedding throughput'
# GEN-0059: reduced `pr` corpus bounded at 45 minutes on pull requests, `full` corpus
# bounded at 180 minutes on develop after merge, never cancelled outside pull requests,
# and not required by release.yml.
grep -qF "timeout-minutes: \${{ github.event_name == 'pull_request' && 45 || 180 }}" "$SEMANTIC_WORKFLOW" \
  && grep -q 'budget de \*\*45 minutes\*\* pour le corpus `pr`' docs/developer/ci-and-supply-chain.md \
  && grep -q 'budget de \*\*180 minutes\*\* pour le corpus `full`' docs/developer/ci-and-supply-chain.md \
  || fail 'real semantic qualification time budget per corpus must stay documented with its measurements'
grep -qF "SEMANTIC_CORPUS: \${{ github.event_name == 'pull_request' && 'pr' || inputs.corpus || 'full' }}" "$SEMANTIC_WORKFLOW" \
  || fail 'pull requests must run the pr corpus and other events the full corpus by default'
grep -qF -- '-Dnexus.semantic.realBenchmark.corpus="$SEMANTIC_CORPUS"' "$SEMANTIC_WORKFLOW" \
  || fail 'the selected corpus must reach RealSemanticSearchBenchmarkTest'
python3 - "$SEMANTIC_WORKFLOW" .github/workflows/release.yml <<'PY'
import re
import sys
from pathlib import Path


def block(text: str, key: str) -> str:
    match = re.search(rf"^  {key}:\n((?:(?:    |\s*\n).*\n?)*)", text, re.MULTILINE)
    return match.group(1) if match else ""


workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
push = block(workflow, "push")
pull = block(workflow, "pull_request")
if "      - develop\n" not in push or "    paths:\n" not in push:
    raise SystemExit("final-audit-contracts: the full corpus must run on push to develop, filtered by the watched paths")
def watched(section: str) -> list[str]:
    paths = re.search(r"^    paths:\n((?:      - .*\n?)+)", section, re.MULTILINE)
    return paths.group(1).split() if paths else []


if not watched(push) or watched(push) != watched(pull):
    raise SystemExit("final-audit-contracts: push and pull_request must watch the same paths")
concurrency = workflow.split("\nconcurrency:\n", 1)[-1].split("\n\n", 1)[0]
if "|| github.sha }}" not in concurrency or "cancel-in-progress: ${{ github.event_name == 'pull_request' }}" not in concurrency:
    raise SystemExit("final-audit-contracts: runs outside pull requests must be grouped by SHA and never cancelled")

release = Path(sys.argv[2]).read_text(encoding="utf-8")
publish = release.split("\n  publish:\n", 1)
if len(publish) != 2:
    raise SystemExit("final-audit-contracts: release.yml publish job not found")
job = re.split(r"\n  [a-z0-9_-]+:\n", publish[1], maxsplit=1)[0]
needs = re.search(r"^    needs:(.*(?:\n      - .*)*)", job, re.MULTILINE)
if needs is None:
    raise SystemExit("final-audit-contracts: release.yml publish job has no needs")
needed = set(re.findall(r"[A-Za-z0-9_-]+", needs.group(1)))
semantic_jobs = set(re.findall(
    r"^  ([A-Za-z0-9_-]+):\n(?:    .*\n)*?    uses: \./\.github/workflows/semantic-search-qualification\.yml",
    release,
    re.MULTILINE))
if needed & (semantic_jobs | {"semantic-search", "semantic-search-qualification", "real-ollama"}):
    raise SystemExit("final-audit-contracts: GEN-0059 keeps the real semantic qualification out of publish needs")
PY
grep -q 'GEN-0059' docs/developer/semantic-search.md \
  && grep -q "La release n'exige pas la qualification sémantique réelle" docs/developer/semantic-search.md \
  || fail 'docs/developer/semantic-search.md must state the release rule of GEN-0059'
grep -Eq '^ollama\.0\.33\.3\.linux-amd64\.sha256=[0-9a-f]{64}$' config/tool-integrity.properties \
  || fail 'Ollama qualification checksum anchor is missing'

# Vector API remains a measured non-adoption decision. It must not silently enter
# production launchers until a new same-runner qualification changes that decision.
grep -q -- '--add-modules=jdk.incubator.vector' docs/developer/runtime-flags-qualification.md \
  || fail 'Vector API comparison contract must remain documented'
grep -q "n'est pas activé par défaut" docs/developer/runtime-flags-qualification.md \
  || fail 'Vector API non-adoption decision must remain explicit'
if grep -q -- '--add-modules=jdk.incubator.vector' distribution/bin/nexus distribution/bin/nexus.cmd; then
  fail 'Vector API must not be enabled in production launchers without a new measured decision'
fi

governance_docs=(
  docs/project/governance/ROADMAP.md
  docs/architecture/overview.md
  docs/architecture/risks/register.md
  docs/architecture/arc42/07-vue-deploiement.md
  docs/architecture/arc42/10-exigences-qualite.md
  docs/architecture/arc42/11-risques-dette.md
  docs/developer/README.md
  docs/developer/architecture-implementation.md
  docs/developer/branch-governance.md
  docs/developer/ci-and-supply-chain.md
  docs/developer/current-limitations.md
)

for path in "${governance_docs[@]}"; do
  if grep -Eq 'protected=false|#130 (reste|demeure).*(ouvert|non satisfait)|NXA3-14.*reste.*ouvert' "$path"; then
    fail "stale develop governance state remains in $path"
  fi
  if grep -Eq '#199|#202|required_approving_review_count|strict_required_status_checks_policy=false' "$path"; then
    fail "obsolete solo-maintainer governance finding remains in $path"
  fi
done

grep -q 'un seul mainteneur' docs/developer/branch-governance.md \
  || fail 'branch governance must document the current solo-maintainer model'
grep -q 'ne doivent donc pas remonter' docs/developer/branch-governance.md \
  || fail 'branch governance must prevent obsolete approval findings from resurfacing'
grep -q 'NXA3-14 / #130 est satisfait' docs/architecture/risks/register.md \
  || fail 'risk register must record the effective develop protection state'

echo 'final-audit-contracts=PASS'
