#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <tag> <asset> [asset ...]" >&2
  exit 64
fi

TAG="$1"
shift

# A pre-release tag (vX.Y.Z-rc.N, same grammar as validate-release-tag.sh) publishes
# a GitHub pre-release that is never marked as the latest release.
EXPECTED_PRERELEASE=false
if [[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-rc\.[1-9][0-9]*$ ]]; then
  EXPECTED_PRERELEASE=true
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required to publish release assets." >&2
  exit 69
fi
if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "GH_TOKEN is required to publish release assets." >&2
  exit 77
fi

declare -A LOCAL_ASSET_NAMES=()
for asset in "$@"; do
  if [[ ! -s "$asset" ]]; then
    echo "Release asset is missing or empty: $asset" >&2
    exit 66
  fi

  name="$(basename "$asset")"
  if [[ -n "${LOCAL_ASSET_NAMES[$name]:-}" ]]; then
    echo "Duplicate release asset basename is not allowed: $name" >&2
    exit 65
  fi
  LOCAL_ASSET_NAMES[$name]="$asset"
done

release_exists=false
release_is_draft=false
if gh release view "$TAG" --json isDraft >/dev/null 2>&1; then
  release_exists=true
  release_is_draft="$(gh release view "$TAG" --json isDraft --jq '.isDraft')"
  if [[ "$release_is_draft" != "true" && "$release_is_draft" != "false" ]]; then
    echo "Unable to determine GitHub Release draft state for $TAG: $release_is_draft" >&2
    exit 70
  fi

  release_is_prerelease="$(gh release view "$TAG" --json isPrerelease --jq '.isPrerelease')"
  if [[ "$release_is_prerelease" != "$EXPECTED_PRERELEASE" ]]; then
    echo "GitHub Release $TAG has pre-release state '$release_is_prerelease', expected '$EXPECTED_PRERELEASE'; refusing to resume." >&2
    exit 78
  fi

  if [[ "$release_is_draft" == "true" ]]; then
    echo "GitHub Release $TAG already exists as a draft; resuming immutable asset publication."
  else
    echo "GitHub Release $TAG is already published; verifying immutable assets without mutating it."
  fi
else
  CHANNEL_ARGS=()
  if [[ "$EXPECTED_PRERELEASE" == "true" ]]; then
    echo "Creating GitHub pre-release $TAG as a draft until every asset is verified."
    CHANNEL_ARGS=(--prerelease --latest=false)
  else
    echo "Creating GitHub Release $TAG as a draft until every asset is verified."
  fi
  gh release create "$TAG" \
    --verify-tag \
    --draft \
    ${CHANNEL_ARGS[@]+"${CHANNEL_ARGS[@]}"} \
    --title "NEXUS ${TAG#v}" \
    --notes "Immutable NEXUS release qualified by CI for tag ${TAG}."
  release_exists=true
  release_is_draft=true
fi

if [[ "$release_exists" != "true" ]]; then
  echo "GitHub Release $TAG could not be created or loaded." >&2
  exit 70
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mapfile -t REMOTE_ASSET_NAMES < <(gh release view "$TAG" --json assets --jq '.assets[].name')

remote_asset_exists() {
  local candidate="$1"
  local remote_name
  for remote_name in "${REMOTE_ASSET_NAMES[@]:-}"; do
    if [[ "$remote_name" == "$candidate" ]]; then
      return 0
    fi
  done
  return 1
}

for asset in "$@"; do
  name="$(basename "$asset")"
  destination="$WORK/$name"
  rm -f "$destination"

  if remote_asset_exists "$name"; then
    if ! gh release download "$TAG" --pattern "$name" --dir "$WORK" >/dev/null; then
      echo "Existing GitHub Release asset could not be downloaded for verification: $name" >&2
      exit 70
    fi
    if [[ ! -f "$destination" ]]; then
      echo "GitHub reported an existing asset but it was not downloaded: $name" >&2
      exit 70
    fi
    if ! cmp -s "$asset" "$destination"; then
      expected="$(sha256sum "$asset" | awk '{print $1}')"
      actual="$(sha256sum "$destination" | awk '{print $1}')"
      echo "Refusing to replace immutable GitHub Release asset $name: local=$expected remote=$actual" >&2
      exit 73
    fi
    echo "Release asset already exists with identical bytes: $name"
    continue
  fi

  if [[ "$release_is_draft" != "true" ]]; then
    echo "Published GitHub Release $TAG is missing immutable asset $name; refusing to mutate a public release." >&2
    exit 75
  fi

  gh release upload "$TAG" "$asset"
  REMOTE_ASSET_NAMES+=("$name")
  rm -f "$destination"
  gh release download "$TAG" --pattern "$name" --dir "$WORK" >/dev/null
  if [[ ! -f "$destination" ]] || ! cmp -s "$asset" "$destination"; then
    echo "Uploaded GitHub Release asset failed byte-for-byte verification: $name" >&2
    exit 74
  fi
  echo "Published immutable GitHub Release draft asset: $name"
done

if [[ "$release_is_draft" == "true" ]]; then
  echo "All release assets are present with verified bytes; publishing GitHub Release $TAG."
  gh release edit "$TAG" --draft=false >/dev/null
  final_draft_state="$(gh release view "$TAG" --json isDraft --jq '.isDraft')"
  if [[ "$final_draft_state" != "false" ]]; then
    echo "GitHub Release $TAG did not transition from draft to published state." >&2
    exit 76
  fi
  final_prerelease_state="$(gh release view "$TAG" --json isPrerelease --jq '.isPrerelease')"
  if [[ "$final_prerelease_state" != "$EXPECTED_PRERELEASE" ]]; then
    echo "GitHub Release $TAG was published with pre-release state '$final_prerelease_state', expected '$EXPECTED_PRERELEASE'." >&2
    exit 78
  fi
  echo "Published complete immutable GitHub Release: $TAG"
fi

# Published releases must be immutable (repository setting "immutable releases"): GitHub then
# locks the tag and the assets. The field is readable with contents: read; the setting itself
# requires administration access, which the release job does not hold. A published release
# cannot be unpublished here: the failure stops the job before latest moves.
if ! release_is_immutable="$(gh api "repos/{owner}/{repo}/releases/tags/$TAG" --jq '.immutable')"; then
  echo "Unable to read the immutable state of GitHub Release $TAG." >&2
  exit 70
fi
if [[ "$release_is_immutable" != "true" ]]; then
  echo "GitHub Release $TAG is published but not immutable; enable immutable releases before publishing." >&2
  exit 79
fi
echo "GitHub Release $TAG is immutable."
