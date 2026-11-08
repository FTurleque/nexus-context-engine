#!/usr/bin/env bash
set -euo pipefail

TAG="${1:-}"
VERSION="${2:-}"
RELEASE_SHA="${3:-}"
MAIN_SHA="${4:-}"

# Final release: v<major>.<minor>.<patch>. Pre-release: v<major>.<minor>.<patch>-rc.<n>,
# n >= 1 without leading zero. Any other suffix is refused.
if [[ ! "$TAG" =~ ^v([0-9]+\.[0-9]+\.[0-9]+(-rc\.[1-9][0-9]*)?)$ ]]; then
  echo "Invalid release tag '$TAG': expected v<major>.<minor>.<patch> or v<major>.<minor>.<patch>-rc.<n>" >&2
  exit 1
fi

TAG_VERSION="${BASH_REMATCH[1]}"
PRERELEASE=false
if [[ -n "${BASH_REMATCH[2]:-}" ]]; then
  PRERELEASE=true
fi
if [[ "$TAG_VERSION" != "$VERSION" ]]; then
  echo "Release tag version '$TAG_VERSION' does not match pom.xml version '$VERSION'" >&2
  exit 1
fi

if [[ ! "$RELEASE_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Invalid release SHA '$RELEASE_SHA': expected a full lowercase Git commit SHA" >&2
  exit 1
fi

if [[ ! "$MAIN_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Invalid main SHA '$MAIN_SHA': expected a full lowercase Git commit SHA" >&2
  exit 1
fi

if [[ "$RELEASE_SHA" != "$MAIN_SHA" ]]; then
  echo "Release tag must point to the exact current main HEAD: release=$RELEASE_SHA main=$MAIN_SHA" >&2
  exit 1
fi

printf 'release-policy=PASS tag=%s version=%s sha=%s prerelease=%s\n' "$TAG" "$VERSION" "$RELEASE_SHA" "$PRERELEASE"
