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
  # Two reads are served: repos/{owner}/{repo}/releases/latest --jq .tag_name and
  # repos/{owner}/{repo}/releases/tags/<tag> --jq .immutable
  path="${2:-}"
  if [[ "$path" == 'repos/{owner}/{repo}/releases/latest' ]]; then
    [[ "${3:-}" == "--jq" && "${4:-}" == ".tag_name" ]] || {
      echo "unsupported fake gh api call: $*" >&2
      exit 2
    }
    # GitHub answers 404 when no release is marked latest; gh then exits non-zero.
    [[ -s "$STATE/latest" ]] || {
      echo "gh: Not Found (HTTP 404)" >&2
      exit 1
    }
    cat "$STATE/latest"
    exit 0
  fi
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
    publish_requested=false
    latest_requested=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --draft=false)
          publish_requested=true
          ;;
        --latest=false)
          latest_requested=false
          ;;
        --latest|--latest=true)
          latest_requested=true
          ;;
        *)
          echo "unsupported fake gh release edit option: $1" >&2
          exit 2
          ;;
      esac
      shift
    done
    is_prerelease="$(cat "$release_dir/prerelease")"
    if [[ "$publish_requested" == "true" ]]; then
      printf 'false\n' > "$release_dir/draft"
      # GitHub marks a release immutable when it is published while the repository setting is on.
      cat "$STATE/immutable-releases" > "$release_dir/immutable"
      # API default: make_latest is true for a newly published release unless false is sent;
      # a pre-release is never latest.
      if [[ "$latest_requested" != "false" && "$is_prerelease" == "false" ]]; then
        printf '%s\n' "$tag" > "$STATE/latest"
      fi
    elif [[ "$latest_requested" == "true" ]]; then
      if [[ "$(cat "$release_dir/draft")" != "false" || "$is_prerelease" != "false" ]]; then
        echo "drafts and pre-releases cannot be marked as the latest release" >&2
        exit 5
      fi
      # latest-edit-mode simulates a failed marking: "fail" exits non-zero, "ignore" leaves latest unchanged.
      case "$(cat "$STATE/latest-edit-mode" 2>/dev/null || true)" in
        fail)
          echo "HTTP 500: marking latest failed" >&2
          exit 1
          ;;
        ignore)
          ;;
        *)
          printf '%s\n' "$tag" > "$STATE/latest"
          ;;
      esac
    else
      echo "fake gh only supports publishing a draft or marking the latest release" >&2
      exit 2
    fi
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
# A previous final release is marked latest before this qualification starts.
PREVIOUS_TAG="v0.1.0"
printf '%s\n' "$PREVIOUS_TAG" > "$FAKE_GH_STATE/latest"

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

current_latest() {
  cat "$FAKE_GH_STATE/latest" 2>/dev/null || true
}

count_log() {
  grep -c -x -F -- "$1" "$FAKE_GH_LOG" || true
}

# Line number of the last occurrence of an exact log entry (empty when absent).
last_log_line() {
  { grep -n -x -F -- "$1" "$FAKE_GH_LOG" || true; } | tail -n 1 | cut -d: -f1
}

# First publication must stay draft until every asset has been uploaded and verified.
bash "$SCRIPT" "$TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe" > "$TEMP/first.out"
release_dir="$FAKE_GH_STATE/releases/$TAG"
[[ "$(cat "$release_dir/draft")" == "false" ]] || fail "release was not published after successful verification"
cmp -s "$asset_dir/nexus-windows.zip" "$release_dir/assets/nexus-windows.zip" || fail "ZIP bytes differ after publication"
cmp -s "$asset_dir/nexus-setup.exe" "$release_dir/assets/nexus-setup.exe" || fail "setup bytes differ after publication"
grep -Fq "release create $TAG --verify-tag --draft --latest=false" "$FAKE_GH_LOG" \
  || fail "final release was not created as a draft excluded from latest"
grep -Fxq "release edit $TAG --draft=false --latest=false" "$FAKE_GH_LOG" \
  || fail "release was not published outside latest after verification"
[[ "$(cat "$release_dir/prerelease")" == "false" ]] || fail "final release was published as a pre-release"
if grep -E "^release create $TAG .*--prerelease" "$FAKE_GH_LOG" >/dev/null; then
  fail "final release must not be created with --prerelease"
fi
immutable_read="api repos/{owner}/{repo}/releases/tags/$TAG --jq .immutable"
grep -Fxq "$immutable_read" "$FAKE_GH_LOG" || fail "published release immutability was not checked"
# An immutable final release is marked latest, and only after the immutable read.
[[ "$(current_latest)" == "$TAG" ]] || fail "immutable final release was not marked latest"
mark_line="$(last_log_line "release edit $TAG --latest")"
[[ -n "$mark_line" ]] || fail "immutable final release was not marked latest by gh release edit --latest"
(( $(last_log_line "$immutable_read") < mark_line )) || fail "latest was marked before the immutable read"
grep -Fq "GitHub Release $TAG is now the latest release." "$TEMP/first.out" || fail "latest marking was not reported"

# Re-running against an already published, already latest, byte-identical release must be read-only.
upload_count_before="$(grep -c '^release upload ' "$FAKE_GH_LOG" || true)"
edit_count_before="$(grep -c '^release edit ' "$FAKE_GH_LOG" || true)"
bash "$SCRIPT" "$TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe" > "$TEMP/resume.out"
upload_count_after="$(grep -c '^release upload ' "$FAKE_GH_LOG" || true)"
edit_count_after="$(grep -c '^release edit ' "$FAKE_GH_LOG" || true)"
[[ "$upload_count_after" == "$upload_count_before" ]] || fail "published release was mutated during idempotent resume"
[[ "$edit_count_after" == "$edit_count_before" ]] || fail "published release state was edited during idempotent resume"
[[ "$(current_latest)" == "$TAG" ]] || fail "latest moved during idempotent resume"
grep -Fq "GitHub Release $TAG is already the latest release." "$TEMP/resume.out" \
  || fail "idempotent resume did not report the existing latest release"

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
bash "$SCRIPT" "$RC_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe" > "$TEMP/rc.out"
rc_release_dir="$FAKE_GH_STATE/releases/$RC_TAG"
[[ "$(cat "$rc_release_dir/draft")" == "false" ]] || fail "pre-release was not published after successful verification"
[[ "$(cat "$rc_release_dir/prerelease")" == "true" ]] || fail "pre-release tag was not published as a GitHub pre-release"
grep -Fq "release create $RC_TAG --verify-tag --draft --prerelease --latest=false" "$FAKE_GH_LOG" \
  || fail "pre-release was not created as a draft pre-release excluded from latest"
grep -Fxq "release edit $RC_TAG --draft=false --latest=false" "$FAKE_GH_LOG" \
  || fail "pre-release was not published outside latest"
[[ "$(count_log "release edit $RC_TAG --latest")" == "0" ]] || fail "pre-release was marked latest"
[[ "$(current_latest)" == "$TAG" ]] || fail "publishing a pre-release moved latest"
grep -Fq "GitHub pre-release $RC_TAG is never marked as the latest release." "$TEMP/rc.out" \
  || fail "pre-release outcome was not reported"

# Resuming a pre-release is idempotent and keeps its pre-release state.
bash "$SCRIPT" "$RC_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
[[ "$(cat "$rc_release_dir/prerelease")" == "true" ]] || fail "pre-release state changed during idempotent resume"
[[ "$(current_latest)" == "$TAG" ]] || fail "resuming a pre-release moved latest"

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

# With immutable releases disabled, the published release is not immutable: exit 79, and the
# previous latest release stays latest because the publication sends make_latest=false.
printf 'false\n' > "$FAKE_GH_STATE/immutable-releases"
MUTABLE_TAG="v0.5.0"
expect_failure 79 bash "$SCRIPT" "$MUTABLE_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
grep -Fq "GitHub Release $MUTABLE_TAG is published but not immutable" "$TEMP/expected-failure.err" \
  || fail "non-immutable release failure does not name the tag"
[[ "$(cat "$FAKE_GH_STATE/releases/$MUTABLE_TAG/draft")" == "false" ]] || fail "non-immutable release was not published"
[[ "$(current_latest)" == "$TAG" ]] || fail "non-immutable final release became latest"
[[ "$(count_log "release edit $MUTABLE_TAG --latest")" == "0" ]] || fail "non-immutable final release was marked latest"
# Resuming a published release that is not immutable fails the same way, without mutating it.
edit_count_before="$(grep -c '^release edit ' "$FAKE_GH_LOG" || true)"
expect_failure 79 bash "$SCRIPT" "$MUTABLE_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
[[ "$(grep -c '^release edit ' "$FAKE_GH_LOG" || true)" == "$edit_count_before" ]] \
  || fail "non-immutable published release was edited during resume"
[[ "$(current_latest)" == "$TAG" ]] || fail "resuming a non-immutable release moved latest"
printf 'true\n' > "$FAKE_GH_STATE/immutable-releases"

# An immutable published final release left outside latest (failure between publication and
# marking) is resumed with exit 0 and no upload; the only change after the immutable read is
# its marking as latest.
printf '%s\n' "$PREVIOUS_TAG" > "$FAKE_GH_STATE/latest"
upload_count_before="$(grep -c '^release upload ' "$FAKE_GH_LOG" || true)"
log_lines_before="$(wc -l < "$FAKE_GH_LOG")"
bash "$SCRIPT" "$TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe" > "$TEMP/immutable.out"
grep -Fq "GitHub Release $TAG is immutable." "$TEMP/immutable.out" || fail "immutable release check was not reported"
[[ "$(grep -c '^release upload ' "$FAKE_GH_LOG" || true)" == "$upload_count_before" ]] || fail "immutable release received an upload"
edits_during_resume="$(tail -n +"$((log_lines_before + 1))" "$FAKE_GH_LOG" | grep '^release edit ' || true)"
[[ "$edits_during_resume" == "release edit $TAG --latest" ]] \
  || fail "resuming an immutable release outside latest must only mark it latest, got: $edits_during_resume"
[[ "$(current_latest)" == "$TAG" ]] || fail "immutable release outside latest was not marked latest on resume"
[[ "$(cat "$release_dir/draft")" == "false" && "$(cat "$release_dir/prerelease")" == "false" ]] \
  || fail "resume changed the draft or pre-release state"

# No release is marked latest at all (GitHub answers 404): the read is tolerated and the tag is marked.
rm -f "$FAKE_GH_STATE/latest"
bash "$SCRIPT" "$TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe" > /dev/null
[[ "$(current_latest)" == "$TAG" ]] || fail "release was not marked latest when no latest release existed"

# Re-running an older final release while a newer one is latest marks the older one again,
# without comparing versions and without uploading.
NEWER_TAG="v0.6.0"
bash "$SCRIPT" "$NEWER_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe" > /dev/null
[[ "$(current_latest)" == "$NEWER_TAG" ]] || fail "newer immutable final release was not marked latest"
upload_count_before="$(grep -c '^release upload ' "$FAKE_GH_LOG" || true)"
bash "$SCRIPT" "$TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe" > /dev/null
[[ "$(current_latest)" == "$TAG" ]] || fail "re-running an older final release did not mark it latest again"
[[ "$(grep -c '^release upload ' "$FAKE_GH_LOG" || true)" == "$upload_count_before" ]] \
  || fail "re-running an older final release uploaded an asset"

# Marking latest fails (gh error) or is not reflected by the re-read: exit 80 naming the tag,
# and latest stays on the previous release. A rerun then marks it.
BADGE_TAG="v0.7.0"
for mode in fail ignore; do
  printf '%s\n' "$mode" > "$FAKE_GH_STATE/latest-edit-mode"
  expect_failure 80 bash "$SCRIPT" "$BADGE_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe"
  grep -Fq "GitHub Release $BADGE_TAG is immutable but could not be marked as the latest release." \
    "$TEMP/expected-failure.err" || fail "latest marking failure ($mode) does not name the tag"
  [[ "$(current_latest)" == "$TAG" ]] || fail "latest moved although marking $BADGE_TAG failed ($mode)"
done
rm -f "$FAKE_GH_STATE/latest-edit-mode"
bash "$SCRIPT" "$BADGE_TAG" "$asset_dir/nexus-windows.zip" "$asset_dir/nexus-setup.exe" > /dev/null
[[ "$(current_latest)" == "$BADGE_TAG" ]] || fail "rerun after exit 80 did not mark the release latest"

echo "GitHub Release asset publication qualification passed."
