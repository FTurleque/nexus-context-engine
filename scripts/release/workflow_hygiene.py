#!/usr/bin/env python3
"""Hygiene checks for GitHub Actions workflows (ci-workflow-tooling).

Usage: workflow_hygiene.py <check> <workflow-or-directory>...

Checks:
  pins      every external `uses:` is pinned by a full SHA with a `# vX.Y.Z`
            comment; one SHA carries one comment; one major version per action
  inputs    no expression reading `inputs` (or `github.event.inputs`) inside a
            `run:` block
  timeouts  every job with `runs-on:` declares `timeout-minutes`
  runners   benchmark and performance qualification jobs use versioned images

The parser is line based on purpose: it needs no third-party YAML library and
only reads the subset of YAML the repository workflows use (block mappings, one
key per line). A job written as a flow mapping is not seen by the timeouts
check; a runner value followed by a comment is rejected by the runners check.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

USES = re.compile(r"^\s*(?:-\s+)?uses:\s*['\"]?(?P<ref>[^'\"\s#]+)['\"]?\s*(?:#\s*(?P<comment>.*?))?\s*$")
PINNED = re.compile(r"^(?P<action>[\w.-]+/[\w.-]+(?:/[^@\s]+)?)@(?P<sha>[0-9a-f]{40})$")
VERSION_COMMENT = re.compile(r"^v(?P<major>\d+)(?:\.\d+){0,2}$")
RUN = re.compile(r"^(?P<indent>\s*)(?:-\s+)?run:\s*(?P<value>.*)$")
INPUT_EXPRESSION = re.compile(r"\$\{\{[^}]*\b(?:github\.event\.)?inputs\b")
TOP_LEVEL_KEY = re.compile(r"^[A-Za-z_][\w-]*:")
KEY = re.compile(r"^(?P<indent> *)(?P<key>[A-Za-z_][\w-]*):(?:\s|$)")

# Benchmark and performance qualification jobs measured against budgets.
PINNED_RUNNER_JOBS = {
    "scale-benchmark.yml": ("benchmark",),
    "lucene-lifecycle-benchmark.yml": ("benchmark",),
    "scanner-corpus-benchmark.yml": ("benchmark",),
    "git-context-cache-qualification.yml": ("benchmark",),
    "runtime-flags-qualification.yml": ("scale-comparison",),
}
VERSIONED_RUNNER = re.compile(r"^(?:ubuntu-\d{2}\.\d{2}|windows-\d{4}|macos-\d+)$")


def workflow_files(arguments: list[str]) -> list[Path]:
    files: list[Path] = []
    for argument in arguments:
        path = Path(argument)
        if path.is_dir():
            files.extend(sorted(p for p in path.iterdir() if p.suffix in (".yml", ".yaml")))
        else:
            files.append(path)
    return files


def lines_of(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


def check_pins(files: list[Path]) -> list[str]:
    errors: list[str] = []
    comments_by_sha: dict[tuple[str, str], tuple[str, str]] = {}
    shas_by_version: dict[tuple[str, str], tuple[str, str]] = {}
    majors_by_action: dict[str, dict[str, str]] = {}
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
            if not pinned:
                errors.append(f"{where}: {ref} is not pinned by a full commit SHA")
                continue
            comment = (match.group("comment") or "").strip()
            version = VERSION_COMMENT.match(comment)
            if not version:
                errors.append(f"{where}: {ref} must carry a '# vX.Y.Z' version comment, found {comment!r}")
                continue
            repository = "/".join(pinned.group("action").split("/")[:2])
            key = (repository, pinned.group("sha"))
            previous = comments_by_sha.get(key)
            if previous and previous[0] != comment:
                errors.append(
                    f"{where}: {repository}@{key[1]} is commented {comment!r} but {previous[1]} says {previous[0]!r}"
                )
            else:
                comments_by_sha.setdefault(key, (comment, where))
            version_key = (pinned.group("action"), comment)
            known = shas_by_version.get(version_key)
            if known and known[0] != key[1]:
                errors.append(
                    f"{where}: {version_key[0]} {comment} is pinned on {key[1]} but {known[1]} uses {known[0]}"
                )
            else:
                shas_by_version.setdefault(version_key, (key[1], where))
            majors_by_action.setdefault(repository, {}).setdefault(version.group("major"), where)
    for repository, majors in sorted(majors_by_action.items()):
        if len(majors) > 1:
            listed = ", ".join(f"v{major} ({where})" for major, where in sorted(majors.items()))
            errors.append(f"{repository} is pinned on several major versions: {listed}")
    return errors


def check_inputs(files: list[Path]) -> list[str]:
    errors: list[str] = []
    for path in files:
        lines = lines_of(path)
        index = 0
        while index < len(lines):
            match = RUN.match(lines[index])
            if not match:
                index += 1
                continue
            block = [(index + 1, match.group("value"))]
            key_indent = len(match.group("indent"))
            if match.group("value").strip()[:1] in ("|", ">"):
                index += 1
                while index < len(lines):
                    line = lines[index]
                    if line.strip() and len(line) - len(line.lstrip()) <= key_indent:
                        break
                    block.append((index + 1, line))
                    index += 1
            else:
                index += 1
            for number, text in block:
                if INPUT_EXPRESSION.search(text):
                    errors.append(f"{path.as_posix()}:{number}: an input is interpolated inside a run: block")
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
            jobs.append(current)
        elif current is not None and indent == job_indent + 2:
            current[2].setdefault(key.group("key"), number)
    return jobs


def check_timeouts(files: list[Path]) -> list[str]:
    errors: list[str] = []
    for path in files:
        for job, number, keys in jobs_of(lines_of(path)):
            if "uses" in keys:
                continue
            if "runs-on" in keys and "timeout-minutes" not in keys:
                errors.append(f"{path.as_posix()}:{number}: job '{job}' has runs-on but no timeout-minutes")
    return errors


def check_runners(files: list[Path]) -> list[str]:
    errors: list[str] = []
    for path in files:
        expected_jobs = PINNED_RUNNER_JOBS.get(path.name)
        if not expected_jobs:
            continue
        lines = lines_of(path)
        jobs = {job: (number, keys) for job, number, keys in jobs_of(lines)}
        for job in expected_jobs:
            if job not in jobs:
                errors.append(f"{path.as_posix()}: benchmark job '{job}' not found")
                continue
            start, keys = jobs[job]
            end = min((n for _, n, _ in jobs_of(lines) if n > start), default=len(lines) + 1)
            runners = []
            for number in range(start, end):
                text = lines[number - 1].strip()
                for prefix in ("runs-on:", "- os:", "os:"):
                    if text.startswith(prefix):
                        value = text[len(prefix):].strip().strip("'\"")
                        if not value.startswith("${{"):
                            runners.append((number, value))
            if not runners:
                errors.append(f"{path.as_posix()}:{start}: benchmark job '{job}' declares no runner image")
            for number, value in runners:
                if not VERSIONED_RUNNER.match(value):
                    errors.append(f"{path.as_posix()}:{number}: benchmark job '{job}' runs on unpinned image {value!r}")
    return errors


CHECKS = {
    "pins": check_pins,
    "inputs": check_inputs,
    "timeouts": check_timeouts,
    "runners": check_runners,
}


def main(arguments: list[str]) -> int:
    if len(arguments) < 2 or arguments[0] not in CHECKS:
        print(__doc__, file=sys.stderr)
        return 2
    errors = CHECKS[arguments[0]](workflow_files(arguments[1:]))
    for error in errors:
        print(f"workflow hygiene ({arguments[0]}): {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"workflow-hygiene-{arguments[0]}=PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
