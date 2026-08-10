#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VALIDATOR="$ROOT/scripts/release/validate-release-tag.sh"
VALID_SHA="0123456789abcdef0123456789abcdef01234567"
OTHER_SHA="89abcdef0123456789abcdef0123456789abcdef"

expect_success() {
  if ! "$VALIDATOR" "$@" >/tmp/nexus-release-policy.out 2>/tmp/nexus-release-policy.err; then
    cat /tmp/nexus-release-policy.err >&2
    echo "Expected release policy success for: $*" >&2
    exit 1
  fi
}

expect_failure() {
  if "$VALIDATOR" "$@" >/tmp/nexus-release-policy.out 2>/tmp/nexus-release-policy.err; then
    echo "Expected release policy failure for: $*" >&2
    exit 1
  fi
}

expect_success "v0.2.0" "0.2.0" "$VALID_SHA" "$VALID_SHA"
expect_failure "0.2.0" "0.2.0" "$VALID_SHA" "$VALID_SHA"
expect_failure "v0.2" "0.2.0" "$VALID_SHA" "$VALID_SHA"
expect_failure "v0.2.1" "0.2.0" "$VALID_SHA" "$VALID_SHA"
expect_failure "v0.2.0" "0.2.0" "$VALID_SHA" "$OTHER_SHA"
expect_failure "v0.2.0-rc1" "0.2.0" "$VALID_SHA" "$VALID_SHA"
expect_failure "v0.2.0" "0.2.0" "deadbeef" "$VALID_SHA"

printf 'release-tag-policy-tests=PASS\n'
