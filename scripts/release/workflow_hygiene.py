#!/usr/bin/env python3
"""Hygiene checks for GitHub Actions workflows (ci-workflow-tooling).

Usage: workflow_hygiene.py <check> <workflow-or-directory>...

Checks:
  pins      every external `uses:` is pinned by a full SHA with a `# vX.Y.Z`
            comment; one SHA carries one comment; one major version per action;
            runs `forms` first
  forms     rejects the YAML forms the line parser cannot read: a job with an
            inline value (flow mapping), a flow collection as a sequence item
            or as the value of a key the checks read, an anchor, alias or merge
            key, a quoted `jobs`, `steps`, `runs-on`, `timeout-minutes`, `run`,
            `uses`, `with` or `script` key, and a `run:`/`script:` scalar that
            is not a `|`/`>` block yet continues on the next lines
  inputs    no expression reading `inputs` (or `github.event.inputs`) inside a
            `run:` block or a `script:` parameter; runs `forms` first
  timeouts  every job with `runs-on:` declares `timeout-minutes`; runs `forms`
            first
  runners   benchmark and performance qualification jobs use versioned images
  upstream  (network) every external pin is the commit of the upstream tag
            named by its version comment, annotated tags dereferenced

A directory argument covers its `*.yml`/`*.yaml` files and the composite
actions `action.yml` in its subdirectories, at any depth (`.github/actions`).

The parser is line based on purpose: it needs no third-party YAML library and
only reads the subset of YAML the repository workflows use (block mappings, one
key per line). The forms it cannot read fail the `forms` check instead of
passing silently; a runner value followed by a comment is rejected by the
runners check.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

USES = re.compile(r"^\s*(?:-\s+)?uses:\s*['\"]?(?P<ref>[^'\"\s#]+)['\"]?\s*(?:#\s*(?P<comment>.*?))?\s*$")
PINNED = re.compile(r"^(?P<action>[\w.-]+/[\w.-]+(?:/[^@\s]+)?)@(?P<sha>[0-9a-f]{40})$")
VERSION_COMMENT = re.compile(r"^v(?P<major>\d+)(?:\.\d+){0,2}$")
RUN = re.compile(r"^(?P<indent>\s*)(?P<dash>-\s+)?(?P<key>run|script):\s*(?P<value>.*)$")
QUOTED_KEY = re.compile(
    r"^\s*(?:-\s+)?(['\"])(?P<key>jobs|steps|runs-on|timeout-minutes|run|uses|with|script)\1\s*:"
)
EMPTY_VALUE = re.compile(r"^\s*(?:#.*)?$")
EXPRESSION = re.compile(r"\$\{\{(?P<body>.*?)(?:\}\}|$)")
INPUTS_WORD = re.compile(r"\binputs\b")
# Anchor, alias or merge key: the value is defined elsewhere in the file.
ALIAS = re.compile(r"^\s*(?:-\s+(?:[\w\"'-]+:\s+)?|[\w\"'-]+:\s+)[&*][\w-]+|^\s*(?:-\s+)?<<\s*:")
# Flow collection as a sequence item, or as the value of a key the checks read.
FLOW = re.compile(
    r"^\s*-\s+[\[{]|^\s*(?:-\s+)?(?:jobs|steps|with|env|run|uses|script|runs-on|timeout-minutes):\s*[\[{]"
)
TOP_LEVEL_KEY = re.compile(r"^[A-Za-z_][\w-]*:")
KEY = re.compile(r"^(?P<indent> *)(?P<key>[A-Za-z_][\w-]*):(?:\s|$)")
MAPPING_KEY = re.compile(r"^\s*[A-Za-z_][\w-]*:(?:\s|$)")

# Benchmark and performance qualification jobs measured against budgets.
PINNED_RUNNER_JOBS = {
    "scale-benchmark.yml": ("benchmark",),
    "lucene-lifecycle-benchmark.yml": ("benchmark",),
    "scanner-corpus-benchmark.yml": ("benchmark",),
    "git-context-cache-qualification.yml": ("benchmark",),
    "runtime-flags-qualification.yml": ("scale-comparison",),
    "measurement-tools.yml": ("minos-replay", "index-baselines"),
    "semantic-search-qualification.yml": ("semantic-diagnostics",),
}
VERSIONED_RUNNER = re.compile(r"^(?:ubuntu-\d{2}\.\d{2}|windows-\d{4}|macos-\d+)$")


def workflow_files(arguments: list[str]) -> list[Path]:
    files: list[Path] = []
    for argument in arguments:
        path = Path(argument)
        if not path.exists():
            raise SystemExit(f"workflow hygiene: {argument} does not exist")
        if path.is_dir():
            files.extend(sorted(p for p in path.iterdir() if p.is_file() and p.suffix in (".yml", ".yaml")))
            files.extend(
                sorted(p for p in path.rglob("action.y*ml") if p.parent != path and p.suffix in (".yml", ".yaml"))
            )
        else:
            files.append(path)
    return files


def lines_of(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


def external_pins(files: list[Path]):
    """Yield (location, action, sha, comment) for each external `uses:`, or an error string."""
    for path in files:
        for number, line in enumerate(lines_of(path), 1):
            match = USES.match(line)
            if not match:
                continue
            ref = match.group("ref")
            if ref.startswith("./") or ref.startswith("docker://"):
                continue
            where = f"{path.as_posix()}:{number}"
            pinned = PINNED.match(ref)
            comment = (match.group("comment") or "").strip()
            if not pinned:
                yield f"{where}: {ref} is not pinned by a full commit SHA"
            elif not VERSION_COMMENT.match(comment):
                yield f"{where}: {ref} must carry a '# vX.Y.Z' version comment, found {comment!r}"
            else:
                yield (where, pinned.group("action"), pinned.group("sha"), comment)


def first_conflict(seen: dict, key, value: str, where: str):
    """Record value for key; return the earlier (value, where) when it differs."""
    previous = seen.setdefault(key, (value, where))
    return previous if previous[0] != value else None


def check_pins(files: list[Path]) -> list[str]:
    errors: list[str] = check_forms(files)
    comments_by_sha: dict = {}
    shas_by_version: dict = {}
    majors_by_action: dict[str, dict[str, str]] = {}
    for pin in external_pins(files):
        if isinstance(pin, str):
            errors.append(pin)
            continue
        where, action, sha, comment = pin
        repository = "/".join(action.split("/")[:2])
        conflict = first_conflict(comments_by_sha, (repository, sha), comment, where)
        if conflict:
            errors.append(f"{where}: {repository}@{sha} is commented {comment!r} but {conflict[1]} says {conflict[0]!r}")
        conflict = first_conflict(shas_by_version, (action, comment), sha, where)
        if conflict:
            errors.append(f"{where}: {action} {comment} is pinned on {sha} but {conflict[1]} uses {conflict[0]}")
        major = VERSION_COMMENT.match(comment).group("major")
        majors_by_action.setdefault(repository, {}).setdefault(major, where)
    for repository, majors in sorted(majors_by_action.items()):
        if len(majors) > 1:
            listed = ", ".join(f"v{major} ({where})" for major, where in sorted(majors.items()))
            errors.append(f"{repository} is pinned on several major versions: {listed}")
    return errors


def run_blocks(lines: list[str]):
    """Yield the (line number, text) lines of each `run:` or `script:` value.

    Block scalars and a plain scalar continued on the next lines are included."""
    index = 0
    while index < len(lines):
        match = RUN.match(lines[index])
        index += 1
        if not match:
            continue
        block = [(index, match.group("value"))]
        value = match.group("value").strip()
        scalar = value[:1] in ("|", ">") or (EMPTY_VALUE.match(value) and not opens_mapping(lines, index))
        if scalar:
            column = key_column(match)
            while index < len(lines) and not ending_block(lines[index], column):
                block.append((index + 1, lines[index]))
                index += 1
        yield block


def ending_block(line: str, key_indent: int) -> bool:
    return bool(line.strip()) and len(line) - len(line.lstrip()) <= key_indent


def key_column(match: re.Match) -> int:
    """Column of the `run`/`script` key itself, after a sequence dash."""
    return len(match.group("indent")) + len(match.group("dash") or "")


def next_content(lines: list[str], start: int) -> str | None:
    """First non-blank, non-comment line from index `start`, or None at the end of the file."""
    return next((text for text in lines[start:] if text.strip() and not text.lstrip().startswith("#")), None)


def opens_mapping(lines: list[str], start: int) -> bool:
    """True when the line after an empty `run:` is a mapping key (`defaults: run: shell:`)."""
    following = next_content(lines, start)
    return following is not None and bool(MAPPING_KEY.match(following))


def has_input_expression(text: str) -> bool:
    """True when an expression `${{ ... }}` of the line reads `inputs` (braces inside it included)."""
    return any(INPUTS_WORD.search(match.group("body")) for match in EXPRESSION.finditer(text))


def unclosed_quote(value: str) -> bool:
    """True for a quoted scalar that does not end on its own line."""
    text = value.strip()
    if text[:1] not in ("'", '"'):
        return False
    quote = text[0]
    rest = text[1:].replace("\\\\", "").replace("\\" + quote, "") if quote == '"' else text[1:].replace("''", "")
    return quote not in rest


def scalar_form(lines: list[str], number: int, run: re.Match) -> str | None:
    """Unsupported form of a `run:`/`script:` value starting on line `number`."""
    key = run.group("key")
    value = run.group("value").strip()
    if value[:1] in ("|", ">"):
        return None
    if unclosed_quote(value):
        return f"{key}: quoted scalar continued on the next line, use a | block"
    following = next_content(lines, number)
    if following is None or ending_block(following, key_column(run)):
        return None
    if EMPTY_VALUE.match(value):
        if MAPPING_KEY.match(following):
            return None
        return f"{key}: followed by a plain scalar, use a | block"
    return f"{key}: plain scalar continued on the next line, use a | block"


def unread_line_form(lines: list[str], number: int) -> str | None:
    """Describe the unsupported form of line `number` (1-based), or None."""
    line = lines[number - 1]
    if line.lstrip().startswith("#"):
        return None
    quoted = QUOTED_KEY.match(line)
    if quoted:
        return f"quoted key {quoted.group('key')!r}"
    if ALIAS.match(line):
        return "anchor, alias or merge key"
    if FLOW.match(line):
        return "flow collection"
    run = RUN.match(line)
    return scalar_form(lines, number, run) if run else None


def check_forms(files: list[Path]) -> list[str]:
    """Reject the YAML forms the other checks cannot read (fail closed)."""
    errors: list[str] = []
    for path in files:
        lines = lines_of(path)
        where = path.as_posix()
        errors.extend(
            f"{where}:{number}: unsupported YAML form: job '{job}' is not a block mapping"
            for job, number, keys in jobs_of(lines)
            if keys.get("__inline__")
        )
        for number in range(1, len(lines) + 1):
            form = unread_line_form(lines, number)
            if form:
                errors.append(f"{where}:{number}: unsupported YAML form: {form}")
    return errors


def check_inputs(files: list[Path]) -> list[str]:
    errors: list[str] = check_forms(files)
    for path in files:
        for block in run_blocks(lines_of(path)):
            errors.extend(
                f"{path.as_posix()}:{number}: an input is interpolated inside a run: or script: block"
                for number, text in block
                if has_input_expression(text)
            )
    return errors


def jobs_of(lines: list[str]) -> list[tuple[str, int, dict[str, int]]]:
    """Return (job id, line number, job-level keys) for each job of a workflow."""
    jobs: list[tuple[str, int, dict[str, int]]] = []
    in_jobs = False
    job_indent = None
    current = None
    for number, line in enumerate(lines, 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if TOP_LEVEL_KEY.match(line):
            in_jobs = line.startswith("jobs:")
            current = None
            continue
        if not in_jobs:
            continue
        key = KEY.match(line)
        if not key:
            continue
        indent = len(key.group("indent"))
        if job_indent is None:
            job_indent = indent
        if indent == job_indent:
            current = (key.group("key"), number, {})
            if not EMPTY_VALUE.match(line[key.end():]):
                # A job is a block mapping; any inline value (flow mapping, alias...) is unread.
                current[2]["__inline__"] = number
            jobs.append(current)
        elif current is not None and indent == job_indent + 2:
            current[2].setdefault(key.group("key"), number)
    return jobs


def check_timeouts(files: list[Path]) -> list[str]:
    errors: list[str] = check_forms(files)
    for path in files:
        for job, number, keys in jobs_of(lines_of(path)):
            if "uses" in keys:
                continue
            if "runs-on" in keys and "timeout-minutes" not in keys:
                errors.append(f"{path.as_posix()}:{number}: job '{job}' has runs-on but no timeout-minutes")
    return errors


def declared_runners(lines: list[str], start: int, end: int) -> list[tuple[int, str]]:
    """Return (line number, image) for each literal runner declared between start and end."""
    runners = []
    for number in range(start, end):
        text = lines[number - 1].strip()
        prefix = next((p for p in ("runs-on:", "- os:", "os:") if text.startswith(p)), None)
        if prefix is None:
            continue
        value = text[len(prefix):].strip().strip("'\"")
        if not value.startswith("${{"):
            runners.append((number, value))
    return runners


def check_runners(files: list[Path]) -> list[str]:
    errors: list[str] = []
    for path in files:
        expected_jobs = PINNED_RUNNER_JOBS.get(path.name, ())
        lines = lines_of(path) if expected_jobs else []
        starts = {job: number for job, number, _ in jobs_of(lines)}
        for job in expected_jobs:
            if job not in starts:
                errors.append(f"{path.as_posix()}: benchmark job '{job}' not found")
                continue
            start = starts[job]
            end = min((n for n in starts.values() if n > start), default=len(lines) + 1)
            runners = declared_runners(lines, start, end)
            if not runners:
                errors.append(f"{path.as_posix()}:{start}: benchmark job '{job}' declares no runner image")
            errors.extend(
                f"{path.as_posix()}:{number}: benchmark job '{job}' runs on unpinned image {value!r}"
                for number, value in runners
                if not VERSIONED_RUNNER.match(value)
            )
    return errors


def parse_ls_remote(output: str) -> dict[str, str]:
    """Map each ref name of a `git ls-remote` output to its object id."""
    refs: dict[str, str] = {}
    for line in output.splitlines():
        fields = line.strip().split()
        if len(fields) == 2 and re.fullmatch(r"[0-9a-f]{40}", fields[0]):
            refs[fields[1]] = fields[0]
    return refs


def tag_commit(refs: dict[str, str], tag: str) -> str | None:
    """Commit named by a tag: the peeled `^{}` entry of an annotated tag, else the tag itself."""
    return refs.get(f"refs/tags/{tag}^{{}}") or refs.get(f"refs/tags/{tag}")


def compare_upstream(where: str, action: str, sha: str, tag: str, refs: dict[str, str]) -> str | None:
    commit = tag_commit(refs, tag)
    if commit is None:
        return f"{where}: {action} tag {tag} not found upstream"
    if commit != sha:
        return f"{where}: {action}@{sha} is not the commit of upstream tag {tag} ({commit})"
    return None


def live_ls_remote(repository: str, tag: str) -> str:
    # Isolated from the local git configuration (insteadOf rewrites, credential
    # helpers) and never prompting: a missing repository fails instead of waiting.
    environment = {
        **os.environ,
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_CONFIG_NOSYSTEM": "1",
    }
    result = subprocess.run(
        ["git", "-c", "credential.helper=", "ls-remote", "--tags", f"https://github.com/{repository}.git",
         f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}"],
        capture_output=True, text=True, timeout=60, check=False, env=environment,
    )
    if result.returncode != 0:
        raise RuntimeError(f"git ls-remote {repository} {tag} failed: {result.stderr.strip()}")
    return result.stdout


def check_upstream(files: list[Path], recorded: Path | None = None) -> list[str]:
    """Compare each external pin with its upstream tag (network, or recorded outputs)."""
    errors: list[str] = []
    # None marks a lookup that failed: reported once, not repeated as "tag not found".
    cache: dict[tuple[str, str], dict[str, str] | None] = {}
    for pin in external_pins(files):
        if isinstance(pin, str):
            errors.append(pin)
            continue
        where, action, sha, tag = pin
        repository = "/".join(action.split("/")[:2])
        if (repository, tag) not in cache:
            if recorded is not None:
                record = recorded / f"{repository.replace('/', '__')}.txt"
                cache[(repository, tag)] = parse_ls_remote(
                    record.read_text(encoding="utf-8") if record.is_file() else ""
                )
            else:
                try:
                    cache[(repository, tag)] = parse_ls_remote(live_ls_remote(repository, tag))
                except (RuntimeError, OSError, subprocess.TimeoutExpired) as failure:
                    errors.append(f"{where}: {failure}")
                    cache[(repository, tag)] = None
        refs = cache[(repository, tag)]
        if refs is None:
            continue
        error = compare_upstream(where, action, sha, tag, refs)
        if error:
            errors.append(error)
    return errors


CHECKS = {
    "pins": check_pins,
    "forms": check_forms,
    "inputs": check_inputs,
    "timeouts": check_timeouts,
    "runners": check_runners,
}


def main(arguments: list[str]) -> int:
    if len(arguments) >= 2 and arguments[0] == "upstream":
        # upstream [--recorded <dir>] <paths...>: <dir>/<owner>__<repo>.txt holds a
        # recorded `git ls-remote --tags` output, for offline tests.
        recorded = None
        paths = arguments[1:]
        if paths[0] == "--recorded" and len(paths) >= 3:
            recorded, paths = Path(paths[1]), paths[2:]
        errors = check_upstream(workflow_files(paths), recorded)
    elif len(arguments) < 2 or arguments[0] not in CHECKS:
        print(__doc__, file=sys.stderr)
        return 2
    else:
        errors = CHECKS[arguments[0]](workflow_files(arguments[1:]))
    for error in errors:
        print(f"workflow hygiene ({arguments[0]}): {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"workflow-hygiene-{arguments[0]}=PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
