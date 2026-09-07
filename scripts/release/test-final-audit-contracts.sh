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

# Post-audit 2026-09-07: Windows ACL inspection covers every sensitive path and
# principal matching must not fall back to suffix-based DOMAIN\\user heuristics.
NEXUS_PATHS="core/src/main/java/com/nexus/config/NexusPaths.java"
grep -q 'canonicalCurrentUserPrincipal' "$NEXUS_PATHS" \
  || fail 'Windows ACL inspection must resolve the current principal canonically'
grep -q 'principal.equals("BUILTIN\\\\ADMINISTRATORS")' "$NEXUS_PATHS" \
  || fail 'Windows built-in Administrators principal must be matched exactly'
if grep -q 'endsWith("\\\\ADMINISTRATORS")' "$NEXUS_PATHS"; then
  fail 'suffix-based Administrators trust must not return'
fi
if grep -q 'endsWith("\\\\" + currentUser)' "$NEXUS_PATHS"; then
  fail 'suffix-based current-user ACL trust must not return'
fi

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

governance_docs=(
  docs/roadmap.md
  docs/architecture.md
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
done

grep -q 'strict_required_status_checks_policy=false' docs/developer/branch-governance.md \
  || fail 'branch governance must expose the remaining strict-mode hardening'
grep -q 'NXA3-14 / #130 est satisfait' docs/architecture/risks/register.md \
  || fail 'risk register must record the effective develop protection state'

echo 'final-audit-contracts=PASS'
