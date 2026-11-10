#!/usr/bin/env python3
"""Coverage floor checks (coverage-gates).

Usage: coverage_floors.py [--root DIR] [--modules M[,M...]] [--doc PATH]
                          [--platform linux|windows]
                          [--reports-dir DIR | --skip-reports]

Inputs:
  floors     the JaCoCo `check` rules of each reactor module: the executions
             inherited from the root pom.xml plus those of the module pom.xml;
             the effective floor of a target is the strictest rule covering it
  page       the '### Mesure de référence' table of the coverage page, which
             publishes, for each target, the Linux reactor measure and the
             Windows reactor measure, with the commit they were taken on; the
             reference measure of a target is the lower of the two, since its
             floor gates both builds
  reports    each module's JaCoCo CSV report, `<module>/target/site/jacoco/
             jacoco.csv` (or `<reports-dir>/<module>/jacoco.csv`)

The check fails when:
  - a floor has no row in the table, or a row has no floor;
  - the section names no commit, or a cell's percentage does not match its
    covered/total counts;
  - a floor is above its reference measure or more than 3 points under it;
  - a CLASS or PACKAGE rule includes nothing present in the module report
    (a misnamed rule, which JaCoCo leaves empty and green);
  - the build measure is under its floor, or further from the measure
    published for the build platform than 3 points or one counter unit,
    whichever is larger (the table is stale: update it, and raise the floor
    when the coverage improved durably).

With --skip-reports only the pom.xml files and the page are compared. Rule
forms the check does not read fail too: include wildcards, excludes, a counter
other than LINE or BRANCH, a value other than COVEREDRATIO, a limit without
minimum, an unresolved property, a module execution that redefines the rules
of an inherited one, rules at plugin level, and JaCoCo executions or
configuration outside `build/plugins` (pluginManagement, profiles).
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
import xml.etree.ElementTree as ET
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from fractions import Fraction
from pathlib import Path

DEFAULT_MODULES = ("core", "adapters/rest-quarkus", "adapters/mcp-java", "adapters/assistant-clients")
DEFAULT_DOC = "docs/developer/adapter-coverage-gates.md"
TABLE_HEADING = "### Mesure de référence"
TABLE_COLUMNS = ("Module", "Élément", "Cible", "Lignes Linux", "Branches Linux", "Lignes Windows", "Branches Windows")
CELL_LAYOUT = (("LINE", "linux"), ("BRANCH", "linux"), ("LINE", "windows"), ("BRANCH", "windows"))
PLATFORMS = ("linux", "windows")
MAX_GAP = Fraction(3)
COUNTERS = ("LINE", "BRANCH")
ELEMENTS = ("BUNDLE", "PACKAGE", "CLASS")
BUNDLE_TARGET = "—"
JACOCO = "jacoco-maven-plugin"
PROPERTY = re.compile(r"\$\{([^}]+)\}")
COMMIT = re.compile(r"commit `([0-9a-f]{7,40})`")
MEASURE_CELL = re.compile(r"([\d\s]+)/([\d\s]+)\(\s*(\d+(?:,\d+)?)\s*%\s*\)")

Key = tuple[str, str, str]  # (element, target, counter)


class FormError(Exception):
    """A pom.xml, report or page form the check does not read."""


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def child(node: ET.Element | None, name: str) -> ET.Element | None:
    return None if node is None else next((c for c in node if local(c.tag) == name), None)


def children(node: ET.Element | None, name: str) -> list[ET.Element]:
    return [] if node is None else [c for c in node if local(c.tag) == name]


def text(node: ET.Element | None) -> str:
    return "" if node is None or node.text is None else node.text.strip()


def percent_text(value: Fraction | Decimal) -> str:
    return f"{float(value):.2f} %"


# --- floors -----------------------------------------------------------------

def jacoco_plugins(root: ET.Element) -> list[ET.Element]:
    return [p for p in root.iter() if local(p.tag) == "plugin" and text(child(p, "artifactId")) == JACOCO]


def jacoco_executions(pom: Path) -> tuple[dict[str, str], list[ET.Element]]:
    """Return the properties and the JaCoCo executions of build/plugins, refusing other forms."""
    root = ET.parse(pom).getroot()
    declared = child(root, "properties")
    properties = {} if declared is None else {local(p.tag): text(p) for p in declared}
    read = children(child(child(root, "build"), "plugins"), "plugin")
    executions: list[ET.Element] = []
    for plugin in jacoco_plugins(root):
        if not any(plugin is r for r in read):
            if child(plugin, "executions") is not None or child(plugin, "configuration") is not None:
                raise FormError(f"{pom.as_posix()}: JaCoCo executions or configuration outside build/plugins")
            continue
        if child(child(plugin, "configuration"), "rules") is not None:
            raise FormError(f"{pom.as_posix()}: JaCoCo rules at plugin level are not read by this check")
        executions.extend(children(child(plugin, "executions"), "execution"))
    return properties, executions


def rules_of(execution: ET.Element) -> list[ET.Element]:
    return children(child(child(execution, "configuration"), "rules"), "rule")


def resolve(value: str, properties: dict[str, str], where: str) -> Decimal:
    def substitute(match: re.Match) -> str:
        if match.group(1) not in properties:
            raise FormError(f"{where}: unresolved property ${{{match.group(1)}}}")
        return properties[match.group(1)]

    try:
        return Decimal(PROPERTY.sub(substitute, value))
    except InvalidOperation as error:
        raise FormError(f"{where}: unreadable minimum {value!r}") from error


def module_rules(root: Path, module: str) -> tuple[dict[str, str], list[tuple[str, ET.Element]]]:
    """Return the properties and the (location, rule) pairs that gate one module."""
    root_properties, root_executions = jacoco_executions(root / "pom.xml")
    module_properties, module_executions = jacoco_executions(root / module / "pom.xml")
    inherited_ids = {text(child(e, "id")) for e in root_executions}
    rules = [(f"pom.xml ({text(child(e, 'id'))})", rule) for e in root_executions for rule in rules_of(e)]
    for execution in module_executions:
        identifier = text(child(execution, "id"))
        found = rules_of(execution)
        if found and identifier in inherited_ids:
            raise FormError(f"{module}/pom.xml ({identifier}): redefines the rules of an inherited execution")
        rules.extend((f"{module}/pom.xml ({identifier})", rule) for rule in found)
    return {**root_properties, **module_properties}, rules


def rule_targets(rule: ET.Element, where: str) -> tuple[str, list[str]]:
    element = text(child(rule, "element")) or "BUNDLE"
    if element not in ELEMENTS:
        raise FormError(f"{where}: element {element} is not read by this check")
    if child(rule, "excludes") is not None:
        raise FormError(f"{where}: {element} rule with excludes is not read by this check")
    includes = [text(i) for i in children(child(rule, "includes"), "include")]
    if element == "BUNDLE" and includes:
        raise FormError(f"{where}: BUNDLE rule with includes is not read by this check")
    if element != "BUNDLE" and not includes:
        raise FormError(f"{where}: {element} rule without includes is not read by this check")
    wildcards = [i for i in includes if "*" in i or "?" in i]
    if wildcards:
        raise FormError(f"{where}: wildcard include {wildcards[0]} is not read by this check")
    return element, includes or [BUNDLE_TARGET]


def rule_limits(rule: ET.Element, properties: dict[str, str], where: str) -> list[tuple[str, Decimal]]:
    limits = []
    for limit in children(child(rule, "limits"), "limit"):
        counter = text(child(limit, "counter")) or "INSTRUCTION"
        value = text(child(limit, "value")) or "COVEREDRATIO"
        if counter not in COUNTERS or value != "COVEREDRATIO" or child(limit, "minimum") is None:
            raise FormError(f"{where}: limit {counter}/{value} without a readable minimum is not read by this check")
        limits.append((counter, resolve(text(child(limit, "minimum")), properties, where)))
    return limits


def floors_of(root: Path, module: str) -> dict[Key, Decimal]:
    """Return {(element, target, counter): effective minimum} for one module."""
    properties, rules = module_rules(root, module)
    floors: dict[Key, Decimal] = {}
    for where, rule in rules:
        element, targets = rule_targets(rule, where)
        for counter, minimum in rule_limits(rule, properties, where):
            for target in targets:
                key = (element, target, counter)
                floors[key] = max(minimum, floors.get(key, minimum))
    return floors


# --- page -------------------------------------------------------------------

def measure_cell(cell: str, where: str) -> tuple[Decimal, str | None]:
    """Return (published percent, error) for a 'covered / total (xx,xx %)' cell."""
    match = MEASURE_CELL.fullmatch(cell)
    if not match:
        raise FormError(f"{where}: unreadable measure {cell!r}, expected 'covered / total (xx,xx %)'")
    covered, total = (int(re.sub(r"\D", "", part)) for part in match.groups()[:2])
    percent = Decimal(match.group(3).replace(",", "."))
    if total == 0 or covered > total:
        return percent, f"{where}: {cell!r} has no measurable total"
    expected = (Decimal(covered) * 100 / Decimal(total)).quantize(Decimal("0.01"), ROUND_HALF_UP)
    if expected != percent:
        return percent, f"{where}: {cell!r} does not match its counts ({expected} %)"
    return percent, None


def reference_section(doc: Path) -> list[str]:
    lines = doc.read_text(encoding="utf-8").splitlines()
    if TABLE_HEADING not in lines:
        raise FormError(f"{doc.as_posix()}: no '{TABLE_HEADING}' section")
    start = lines.index(TABLE_HEADING)
    end = next((n for n in range(start + 1, len(lines)) if lines[n].startswith("#")), len(lines))
    return lines[start + 1:end]


def table_rows(section: list[str], doc: Path) -> list[list[str]]:
    rows = [[c.strip() for c in line.strip().strip("|").split("|")] for line in section if line.startswith("|")]
    if len(rows) < 3 or tuple(rows[0]) != TABLE_COLUMNS:
        raise FormError(f"{doc.as_posix()}: the reference measure table must have the columns {' | '.join(TABLE_COLUMNS)}")
    return rows[2:]


def published_measures(doc: Path):
    """Return (commit, {(module, element, target, counter): {platform: percent}}, errors)."""
    section = reference_section(doc)
    commit = next((m.group(1) for line in section for m in [COMMIT.search(line)] if m), None)
    measures: dict[tuple[str, str, str, str], dict[str, Decimal]] = {}
    errors: list[str] = []
    for number, cells in enumerate(table_rows(section, doc), 1):
        where = f"{doc.as_posix()}: reference measure row {number}"
        if len(cells) != len(TABLE_COLUMNS):
            raise FormError(f"{where}: expected {len(TABLE_COLUMNS)} columns, found {len(cells)}")
        module, element, target = (c.strip("`") for c in cells[:3])
        for (counter, platform), cell in zip(CELL_LAYOUT, cells[3:]):
            percent, error = measure_cell(cell, where)
            entry = measures.setdefault((module, element, target, counter), {})
            if platform in entry:
                error = f"{where}: {module} {element} {target} is published twice"
            entry[platform] = percent
            errors.extend([error] if error else [])
    return commit, measures, errors


# --- reports ----------------------------------------------------------------

def report_rows(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        raise FormError(f"{path.as_posix()}: JaCoCo CSV report not found (run ./mvnw clean install first)")
    with path.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    if not rows:
        raise FormError(f"{path.as_posix()}: the JaCoCo CSV report has no class")
    return rows


def selected_rows(rows: list[dict[str, str]], element: str, target: str) -> list[dict[str, str]]:
    if element == "BUNDLE":
        return rows
    if element == "PACKAGE":
        return [r for r in rows if r["PACKAGE"] == target]
    # JaCoCo names a CLASS element package.Outer.Inner, as the CSV does.
    return [r for r in rows if f"{r['PACKAGE']}.{r['CLASS']}" == target]


def counts(rows: list[dict[str, str]], counter: str) -> tuple[int, int]:
    covered = sum(int(r[f"{counter}_COVERED"]) for r in rows)
    return covered, covered + sum(int(r[f"{counter}_MISSED"]) for r in rows)


# --- check ------------------------------------------------------------------

def floor_error(label: str, floor: Decimal, reference: Fraction) -> str | None:
    floor_percent = Fraction(floor) * 100
    if floor_percent > reference:
        return f"{label}: floor {percent_text(floor_percent)} is above the reference measure {percent_text(reference)}"
    if reference - floor_percent > MAX_GAP:
        return (f"{label}: floor {percent_text(floor_percent)} is {float(reference - floor_percent):.2f} points "
                f"under the reference measure {percent_text(reference)} (at most {MAX_GAP} points allowed)")
    return None


def build_errors(label: str, floor: Decimal, covered: int, total: int,
                 published: Decimal | None, platform: str) -> list[str]:
    build = Fraction(covered * 100, total)
    floor_percent = Fraction(floor) * 100
    errors = []
    if build < floor_percent:
        errors.append(f"{label}: build measure {percent_text(build)} is under the floor {percent_text(floor_percent)}")
    tolerance = max(MAX_GAP, Fraction(100, total))
    if published is not None and abs(build - Fraction(published)) > tolerance:
        direction = "above" if build > Fraction(published) else "under"
        errors.append(f"{label}: build measure {percent_text(build)} is more than {float(tolerance):.2f} points "
                      f"{direction} the published {platform} measure {percent_text(published)}; update the reference "
                      "measure table" + (" and raise the floor" if direction == "above" else ""))
    return errors


def report_errors(name: str, floors: dict[Key, Decimal], rows: list[dict[str, str]],
                  published, platform: str) -> list[str]:
    errors: list[str] = []
    for element, target in sorted({(e, t) for e, t, _ in floors}):
        chosen = selected_rows(rows, element, target)
        if not chosen:
            kind = "package" if element == "PACKAGE" else "class"
            errors.append(f"{name} {element} {target}: the rule matches no {kind} of the module report "
                          "(misnamed include: JaCoCo leaves the rule empty)")
            continue
        for counter in COUNTERS:
            if (element, target, counter) not in floors:
                continue
            label = f"{name} {element} {target} {counter}"
            covered, total = counts(chosen, counter)
            if total == 0:
                errors.append(f"{label}: the build measures no {counter.lower()} for this target")
                continue
            measure = published.get((name, element, target, counter), {}).get(platform)
            errors.extend(build_errors(label, floors[(element, target, counter)], covered, total, measure, platform))
    return errors


def page_errors(name: str, floors: dict[Key, Decimal], published, doc: Path) -> list[str]:
    errors: list[str] = []
    for (element, target, counter), floor in sorted(floors.items()):
        label = f"{name} {element} {target} {counter}"
        measures = published.get((name, element, target, counter), {})
        if set(measures) != set(PLATFORMS):
            errors.append(f"{label}: floor {percent_text(Fraction(floor) * 100)} has no row in the reference "
                          f"measure table of {doc.as_posix()}")
            continue
        error = floor_error(label, floor, Fraction(min(measures.values())))
        errors.extend([error] if error else [])
    return errors


def check(arguments: argparse.Namespace) -> list[str]:
    root = Path(arguments.root)
    doc = root / arguments.doc
    commit, published, errors = published_measures(doc)
    if commit is None:
        errors.append(f"{doc.as_posix()}: the reference measure section names no commit")
    modules = [m for m in arguments.modules.split(",") if m]
    seen: set[tuple[str, str, str, str]] = set()
    for module in modules:
        name = Path(module).name
        floors = floors_of(root, module)
        seen.update((name, *key) for key in floors)
        errors.extend(page_errors(name, floors, published, doc))
        if arguments.skip_reports:
            continue
        base = Path(arguments.reports_dir) / module if arguments.reports_dir else root / module / "target/site/jacoco"
        errors.extend(report_errors(name, floors, report_rows(base / "jacoco.csv"), published, arguments.platform))
    names = {Path(m).name for m in modules}
    errors.extend(
        f"{doc.as_posix()}: published measure {' '.join(key)} has no floor"
        for key in sorted(published) if key[0] in names and key not in seen
    )
    return errors


def parse_arguments(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--modules", default=",".join(DEFAULT_MODULES))
    parser.add_argument("--doc", default=DEFAULT_DOC)
    parser.add_argument("--platform", choices=PLATFORMS,
                        default="windows" if sys.platform.startswith("win") else "linux")
    reports = parser.add_mutually_exclusive_group()
    reports.add_argument("--reports-dir")
    reports.add_argument("--skip-reports", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    arguments = parse_arguments(argv)
    try:
        errors = check(arguments)
    except (FormError, ET.ParseError, OSError, KeyError, ValueError) as error:
        errors = [f"unreadable input: {error}"]
    for error in errors:
        print(f"coverage floors: {error}", file=sys.stderr)
    if errors:
        return 1
    scope = "reports skipped" if arguments.skip_reports else f"{arguments.platform} build"
    print(f"coverage-floors=PASS ({scope})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
