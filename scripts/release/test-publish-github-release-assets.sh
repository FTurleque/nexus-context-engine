#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/scripts/release/publish-github-release-assets.sh"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT

mkdir -p "$TEMP/bin" "$TEMP/state"
cat > "$TEMP/bin/gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail

STATE="${FAKE_GH_STATE:?}"
LOG="${FAKE_GH_LOG:?}"
printf '%s\n' "$*" >> "$LOG"

if [[ "${1:-}" == "api" ]]; then
  # Only the read of a release by tag is served: repos/{owner}/{repo}/releases/tags/<tag> --jq .immutable
  path="${2:-}"
  prefix='repos/{owner}/{repo}/releases/tags/'
  [[ "$path" == "$prefix"* && "${3:-}" == "--jq" && "${4:-}" == ".immutable" ]] || {
    echo "unsupported fake gh api call: $*" >&2
    exit 2
  }
  release_dir="$STATE/releases/${path#"$prefix"}"
  [[ -f "$release_dir/draft" ]] || exit 1
  if [[ -f "$release_dir/immutable" ]]; then
    cat "$release_dir/immutable"
  else
    printf 'false\n'
  fi
  exit 0
fi

if [[ "${1:-}" != "release" ]]; then
  echo "unsupported fake gh command: $*" >&2
  exit 2
fi
shift
subcommand="${1:-}"
shift || true

case "$subcommand" in
  view)
    tag="${1:-}"
    shift || true
    release_dir="$STATE/releases/$tag"
    [[ -f "$release_dir/draft" ]] || exit 1

    json_field=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --json)
          json_field="${2:-}"
          shift 2
          ;;
        --jq)
          shift 2
          ;;
        *)
          shift
          ;;
      esac
    done

    case "$json_field" in
      isDraft)
        cat "$release_dir/draft"
        ;;
      isPrerelease)
        cat "$release_dir/prerelease"
        ;;
      assets)
        if [[ -d "$release_dir/assets" ]]; then
          find "$release_dir/assets" -maxdepth 1 -type f -printf '%f\n' | sort
        fi
        ;;
      *)
        echo "unsupported fake gh release view field: $json_field" >&2
        exit 2
        ;;
    esac
    ;;
  create)
    tag="${1:-}"
    shift || true
    release_dir="$STATE/releases/$tag"
    [[ ! -e "$release_dir" ]] || exit 1

    draft_requested=false
    prerelease_requested=false
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --draft)
          draft_requested=true
          shift
          ;;
        --prerelease)
          prerelease_requested=true
          shift
          ;;
        --latest=false)
          shift
          ;;
        --title|--notes)
          shift 2
          ;;
        --verify-tag)
          shift
          ;;
        *)
          shift
          ;;
      esac
    done
    [[ "$draft_requested" == "true" ]] || {
      echo "release must be created as draft" >&2
      exit 3
    }
    mkdir -p "$release_dir/assets"
    printf 'true\n' > "$release_dir/draft"
    printf '%s\n' "$prerelease_requested" > "$release_dir/prerelease"
    ;;
  upload)
    tag="${1:-}"
    asset="${2:-}"
    release_dir="$STATE/releases/$tag"
    [[ -f "$release_dir/draft" ]] || exit 1
    name="$(basename "$asset")"
    [[ ! -e "$release_dir/assets/$name" ]] || exit 4
    cp "$asset" "$release_dir/assets/$name"
    ;;
  download)
    tag="${1:-}"
    shift || true
    pattern=""
    directory=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --pattern)
          pattern="${2:-}"
          shift 2
          ;;
        --dir)
          directory="${2:-}"
          shift 2
          ;;
        *)
          shift
          ;;
      esac
    done
    release_dir="$STATE/releases/$tag"
    [[ -f "$release_dir/assets/$pattern" ]] || exit 1
    mkdir -p "$directory"
    cp "$release_dir/assets/$pattern" "$directory/$pattern"
    ;;
  edit)
    tag="${1:-}"
    shift || true
    release_dir="$STATE/releases/$tag"
    [[ -f "$release_dir/draft" ]] || exit 1
    [[ " $* " == *" --draft=false "* ]] || {
      echo "fake gh only supports publishing a draft" >&2
      exit 2
    }
    printf 'false\n' > "$release_dir/draft"
    # GitHub marks a release immutable when it is published while the repository setting is on.
    cat "$STATE/immutable-releases" > "$release_dir/immutable"
    ;;
  *)
    echo "unsupported fake gh release command: $subcommand" >&2
    exit 2
    ;;
esac
FAKE_GH
chmod +x "$TEMP/bin/gh"

export PATH="$TEMP/bin:$PATH"
export GH_TOKEN="test-token"
export FAKE_GH_STATE="$TEMP/state"
export FAKE_GH_LOG="$TEMP/gh.log"
: > "$FAKE_GH_LOG"
printf 'true\n' > "$FAKE_GH_STATE/immutable-releases"

asset_dir="$TEMP/local"
mkdir -p "$asset_dir"
printf 'portable-zip\n' > "$asset_dir/nexus-windows.zip"
printf 'setup-exe\n' > "$asset_dir/nexus-setup.exe"
TAG="v0.2.0"

fail() {
  echo "TEST FAILURE: $*" >&2
  exit 1
}

expect_failure() {
  local expected="$1"
  shift
  set +e
  "$@" >"$TEMP/expected-failure.out" 2>"$TEMP/expected-failure.err"
  local status=$?
  set -e
  if [[ "$status" -ne "$expected" ]]; then
    cat "$TEMP/expected-failure.out" >&2 || true
    cat "$TEMP/expected-failure.err" >&2 || true
    fail "expected exit $expected, got $status for: $*"
  fi
}

# First publication must stay draft until every asset has been uploaded and verified.
bash "$SCRIPT" "$TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
release_dir="$FAKE_GH_STATE/releases/$TAG"
[[ "$(cat "$release_dir/draft")" == "false" ]] || fail "release was not published after successful verification"
cmp -s "$asset_dir/nexus-windows.zip" "$release_dir/assets/nexus-windows.zip" || fail "ZIP bytes differ after publication"
cmp -s "$asset_dir/nexus-setup.exe" "$release_dir/assets/nexus-setup.exe" || fail "setup bytes differ after publication"
grep -Fq "release create $TAG --verify-tag --draft" "$FAKE_GH_LOG" || fail "release was not created as draft"
grep -Fq "release edit $TAG --draft=false" "$FAKE_GH_LOG" || fail "release was not published after verification"
[[ "$(cat "$release_dir/prerelease")" == "false" ]] || fail "final release was published as a pre-release"
if grep -E "^release create $TAG .*--prerelease" "$FAKE_GH_LOG" >/dev/null; then
  fail "final release must not be created with --prerelease"
fi
grep -Fq "api repos/{owner}/{repo}/releases/tags/$TAG --jq .immutable" "$FAKE_GH_LOG" \
  || fail "published release immutability was not checked"

# Re-running against an already published, byte-identical release must be read-only and idempotent.
upload_count_before="$(grep -c '^release upload ' "$FAKE_GH_LOG" || true)"
edit_count_before="$(grep -c '^release edit ' "$FAKE_GH_LOG" || true)"
bash "$SCRIPT" "$TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
upload_count_after="$(grep -c '^release upload ' "$FAKE_GH_LOG" || true)"
edit_count_after="$(grep -c '^release edit ' "$FAKE_GH_LOG" || true)"
[[ "$upload_count_after" == "$upload_count_before" ]] || fail "published release was mutated during idempotent resume"
[[ "$edit_count_after" == "$edit_count_before" ]] || fail "published release state was edited during idempotent resume"

# A published release missing an expected asset must fail closed instead of being mutated.
rm "$release_dir/assets/nexus-setup.exe"
expect_failure 75 bash "$SCRIPT" "$TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
[[ ! -e "$release_dir/assets/nexus-setup.exe" ]] || fail "missing public asset was unexpectedly uploaded"
cp "$asset_dir/nexus-setup.exe" "$release_dir/assets/nexus-setup.exe"

# Existing public assets are immutable: byte divergence must be rejected.
printf 'tampered\n' > "$release_dir/assets/nexus-windows.zip"
expect_failure 73 bash "$SCRIPT" "$TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
cp "$asset_dir/nexus-windows.zip" "$release_dir/assets/nexus-windows.zip"

# Local assets must have unique basenames so GitHub Release names cannot collide.
mkdir -p "$TEMP/duplicate-a" "$TEMP/duplicate-b"
printf 'a\n' > "$TEMP/duplicate-a/duplicate.bin"
printf 'b\n' > "$TEMP/duplicate-b/duplicate.bin"
expect_failure 65 bash "$SCRIPT" "$TAG" "$TEMP/duplicate-a/duplicate.bin" "$TEMP/duplicate-b/duplicate.bin"

# A pre-release tag publishes a GitHub pre-release that is never marked latest.
RC_TAG="v0.3.0-rc.1"
bash "$SCRIPT" "$RC_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
rc_release_dir="$FAKE_GH_STATE/releases/$RC_TAG"
[[ "$(cat "$rc_release_dir/draft")" == "false" ]] || fail "pre-release was not published after successful verification"
[[ "$(cat "$rc_release_dir/prerelease")" == "true" ]] || fail "pre-release tag was not published as a GitHub pre-release"
grep -Fq "release create $RC_TAG --verify-tag --draft --prerelease --latest=false" "$FAKE_GH_LOG" \
  || fail "pre-release was not created as a draft pre-release excluded from latest"

# Resuming a pre-release is idempotent and keeps its pre-release state.
bash "$SCRIPT" "$RC_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
[[ "$(cat "$rc_release_dir/prerelease")" == "true" ]] || fail "pre-release state changed during idempotent resume"

# An existing release whose pre-release state contradicts its tag must fail closed.
MISMATCH_TAG="v0.4.0-rc.1"
mkdir -p "$FAKE_GH_STATE/releases/$MISMATCH_TAG/assets"
printf 'true\n' > "$FAKE_GH_STATE/releases/$MISMATCH_TAG/draft"
printf 'false\n' > "$FAKE_GH_STATE/releases/$MISMATCH_TAG/prerelease"
expect_failure 78 bash "$SCRIPT" "$MISMATCH_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
[[ -z "$(ls -A "$FAKE_GH_STATE/releases/$MISMATCH_TAG/assets")" ]] || fail "mismatched pre-release state still received assets"
printf 'true\n' > "$release_dir/prerelease"
expect_failure 78 bash "$SCRIPT" "$TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
printf 'false\n' > "$release_dir/prerelease"

# With immutable releases disabled, the published release is not immutable: exit 79 before latest.
printf 'false\n' > "$FAKE_GH_STATE/immutable-releases"
MUTABLE_TAG="v0.5.0"
expect_failure 79 bash "$SCRIPT" "$MUTABLE_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
grep -Fq "GitHub Release $MUTABLE_TAG is published but not immutable" "$TEMP/expected-failure.err" \
  || fail "non-immutable release failure does not name the tag"
[[ "$(cat "$FAKE_GH_STATE/releases/$MUTABLE_TAG/draft")" == "false" ]] || fail "non-immutable release was not published"
# Resuming a published release that is not immutable fails the same way, without mutating it.
edit_count_before="$(grep -c '^release edit ' "$FAKE_GH_LOG" || true)"
expect_failure 79 bash "$SCRIPT" "$MUTABLE_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
[[ "$(grep -c '^release edit ' "$FAKE_GH_LOG" || true)" == "$edit_count_before" ]] \
  || fail "non-immutable published release was edited during resume"
printf 'true\n' > "$FAKE_GH_STATE/immutable-releases"

# An immutable published release passes the check with exit 0 and no mutation.
upload_count_before="$(grep -c '^release upload ' "$FAKE_GH_LOG" || true)"
edit_count_before="$(grep -c '^release edit ' "$FAKE_GH_LOG" || true)"
bash "$SCRIPT" "$TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe" > "$TEMP/immutable.out"
grep -Fq "GitHub Release $TAG is immutable." "$TEMP/immutable.out" || fail "immutable release check was not reported"
[[ "$(grep -c '^release upload ' "$FAKE_GH_LOG" || true)" == "$upload_count_before" ]] || fail "immutable release received an upload"
[[ "$(grep -c '^release edit ' "$FAKE_GH_LOG" || true)" == "$edit_count_before" ]] || fail "immutable release was edited"

echo "GitHub Release asset publication qualification passed."
