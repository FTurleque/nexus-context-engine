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
