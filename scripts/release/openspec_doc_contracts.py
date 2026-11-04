#!/usr/bin/env python3
"""Contrats documentaires OpenSpec de NEXUS (structure, liens, traçabilité, Mermaid).

Contrôle STATIQUE : il ne remplace ni `openspec validate --all --strict` (validation structurelle
OpenSpec, qui exige la CLI) ni le contrôle syntaxique complet des diagrammes
(`scripts/openspec/check-mermaid.mjs`), ni la revue sémantique de complétude.
Sans dépendance externe : bibliothèque standard uniquement.
"""
from __future__ import annotations

import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA_DIR = ROOT / "openspec" / "schemas" / "detailed-uml"
CONFIG = ROOT / "openspec" / "config.yaml"
SPECS_DIR = ROOT / "openspec" / "specs"
CHANGES_DIR = ROOT / "openspec" / "changes"
MATRIX = ROOT / "docs" / "developer" / "openspec-traceability.md"
GUIDE = ROOT / "docs" / "developer" / "openspec.md"

EXPECTED_ARTIFACTS = ["proposal", "specs", "design", "tasks"]
STATUSES = {"couvert", "partiel", "non couvert", "preuve CI seulement"}
PROVEN_STATUSES = {"couvert", "partiel", "preuve CI seulement"}
CAPABILITY_NAME = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)+")
MERMAID_TYPES = (
    "flowchart", "graph", "sequenceDiagram", "classDiagram", "stateDiagram-v2", "stateDiagram",
    "erDiagram", "C4Context", "C4Container", "C4Component", "C4Dynamic", "C4Deployment",
    "journey", "gantt", "pie", "mindmap", "timeline", "gitGraph", "requirementDiagram",
)
PATH_RE = re.compile(
    r"((?:core|adapters|scripts|\.github|\.claude|docs|distribution|packaging|openspec|config)/[^\s`<>#:|),;]+)"
)
METHOD_RE = re.compile(r"((?:core|adapters)/[^\s`<>#|]+\.java)#(\w+)")
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")

errors: list[str] = []


def fail(msg: str) -> None:
    errors.append(msg)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def norm(text: str) -> str:
    return re.sub(r"\s+", " ", text.replace("`", "")).strip()


def slugify(heading: str) -> str:
    text = unicodedata.normalize("NFC", heading.strip().lower())
    text = re.sub(r"[^\w\- ]", "", text, flags=re.UNICODE)
    return text.replace(" ", "-")


def anchors(path: Path) -> set[str]:
    found: set[str] = set()
    in_fence = False
    for line in read(path).splitlines():
        if line.startswith("```"):
            in_fence = not in_fence
        if not in_fence:
            m = re.match(r"#{1,6}\s+(.*?)\s*#*\s*$", line)
            if m:
                found.add(slugify(m.group(1)))
    return found


def check_links(path: Path, text: str | None = None) -> None:
    body = read(path) if text is None else text
    rel = path.relative_to(ROOT)
    for target in LINK_RE.findall(body):
        if re.match(r"^[a-z]+:", target):
            continue
        file_part, _, anchor = target.partition("#")
        dest = path if not file_part else (path.parent / file_part).resolve()
        if not dest.exists():
            fail(f"{rel}: lien cassé {target}")
        elif anchor and dest.is_file() and dest.suffix == ".md" and anchor not in anchors(dest):
            fail(f"{rel}: ancre introuvable {target}")


def mermaid_first_line(block: str) -> str:
    """Première ligne significative d'un diagramme, hors frontmatter YAML et directives `%%`."""
    lines = block.splitlines()
    index = 0
    while index < len(lines) and not lines[index].strip():
        index += 1
    if index < len(lines) and lines[index].strip() == "---":
        index += 1
        while index < len(lines) and lines[index].strip() != "---":
            index += 1
        index += 1
    for line in lines[index:]:
        stripped = line.strip()
        if stripped and not stripped.startswith("%%"):
            return stripped
    return ""


def check_mermaid(path: Path, text: str | None = None) -> None:
    body = read(path) if text is None else text
    rel = path.relative_to(ROOT)
    for block in re.finditer(r"```mermaid[ \t]*\r?\n(.*?)```", body, re.S):
        first = mermaid_first_line(block.group(1))
        if not first.startswith(MERMAID_TYPES):
            fail(f"{rel}: diagramme Mermaid sans type reconnu (« {first[:40]} »)")
    if body.count("```") % 2:
        fail(f"{rel}: clôture ``` non appariée")


def openspec_sections(path: Path) -> str:
    """Texte des sections `## OpenSpec <capacité>` d'une page durable."""
    kept: list[str] = []
    keep = False
    in_fence = False
    for line in read(path).splitlines():
        if line.startswith("```"):
            in_fence = not in_fence
        if not in_fence and line.startswith("## "):
            keep = line.startswith("## OpenSpec ")
        if keep:
            kept.append(line)
    return "\n".join(kept) + "\n"


def durable_reference(purpose: str) -> str | None:
    match = re.search(r"Documentation durable\s*:\s*(\S+)", purpose)
    return match.group(1) if match else None


def parse_spec(path: Path) -> tuple[str, list[tuple[str, str]]]:
    purpose = ""
    pairs: list[tuple[str, str]] = []
    text = read(path)
    m = re.search(r"^## Purpose\s*\n(.*?)(?=^## )", text, re.S | re.M)
    if m:
        purpose = m.group(1)
    req = ""
    for line in text.splitlines():
        if line.startswith("### Requirement:"):
            req = line.split(":", 1)[1].strip()
        elif line.startswith("#### Scenario:"):
            pairs.append((req, line.split(":", 1)[1].strip()))
    return purpose, pairs


def schema_requires(text: str) -> dict[str, list[str]]:
    requires: dict[str, list[str]] = {}
    current = None
    in_requires = False
    for line in text.splitlines():
        m = re.match(r"^  - id: (\S+)", line)
        if m:
            current = m.group(1)
            requires[current] = []
            in_requires = False
        elif re.match(r"^    requires:", line):
            in_requires = True
        elif in_requires and current and re.match(r"^      - (\S+)\s*$", line):
            requires[current].append(line.split("- ", 1)[1].strip())
        elif re.match(r"^    \S", line):
            in_requires = False
    return requires


def check_schema() -> None:
    schema = SCHEMA_DIR / "schema.yaml"
    if not schema.is_file():
        fail("openspec/schemas/detailed-uml/schema.yaml est absent")
        return
    text = read(schema)
    ids = re.findall(r"^  - id: (\S+)", text, re.M)
    if ids != EXPECTED_ARTIFACTS:
        fail(f"schema detailed-uml: artefacts {ids}, attendu {EXPECTED_ARTIFACTS}")
    for tpl in re.findall(r"^    template: (\S+)", text, re.M):
        if not (SCHEMA_DIR / "templates" / tpl).is_file():
            fail(f"schema detailed-uml: modèle manquant templates/{tpl}")
    requires = schema_requires(text)
    if sorted(requires.get("tasks", [])) != ["design", "specs"]:
        fail("schema detailed-uml: tasks doit dépendre de specs et de design")
    if sorted(requires.get("design", [])) != ["proposal", "specs"]:
        fail("schema detailed-uml: design doit dépendre de proposal et de specs")


def check_config_and_changes() -> None:
    if not re.search(r"^schema: detailed-uml\s*$", read(CONFIG), re.M):
        fail("openspec/config.yaml doit sélectionner le schéma detailed-uml")
    for change in sorted(p for p in CHANGES_DIR.iterdir() if p.is_dir() and p.name != "archive"):
        meta = change / ".openspec.yaml"
        if not meta.is_file() or not re.search(r"^schema: \S+", read(meta), re.M):
            fail(f"{change.relative_to(ROOT)}: .openspec.yaml sans schéma (le schéma suivrait config.yaml)")


def check_schema_and_config() -> None:
    check_schema()
    check_config_and_changes()


def parse_matrix(text: str) -> dict[str, list[list[str]]]:
    """Lignes de tableau de chaque section `## <titre>` de la matrice."""
    rows_by_title: dict[str, list[list[str]]] = {}
    for sec in re.split(r"^## ", text, flags=re.M)[1:]:
        title, _, body = sec.partition("\n")
        rows = []
        for line in body.splitlines():
            if not line.startswith("|") or set(line.replace("|", "").strip()) <= set("-: "):
                continue
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if not cells[0].lower().startswith(("exigence", "capacité", "statut")):
                rows.append(cells)
        rows_by_title[title.strip().strip("`")] = rows
    return rows_by_title


def check_durable_page(cap: str, spec: Path, purpose: str) -> None:
    reference = durable_reference(purpose)
    if reference is None:
        fail(f"{spec.relative_to(ROOT)}: Purpose sans ligne « Documentation durable : <chemin> »")
        return
    file_part, _, anchor = reference.partition("#")
    page = ROOT / file_part
    if not page.is_file():
        fail(f"{cap}: page durable absente {file_part}")
    elif anchor != f"openspec-{cap}":
        fail(f"{cap}: ancre durable attendue #openspec-{cap}, trouvée #{anchor}")
    elif anchor not in anchors(page):
        fail(f"{cap}: la page {file_part} n'a pas la section « ## OpenSpec {cap} »")


def check_proof(cap: str, scen: str, proof: str, status: str) -> None:
    paths = [p.rstrip(".") for p in PATH_RE.findall(proof)]
    for p in paths:
        if "*" not in p and not (ROOT / p).exists():
            fail(f"matrice › {cap} › {scen}: chemin de preuve inexistant {p}")
    for source, method in METHOD_RE.findall(proof):
        target = ROOT / source
        if target.is_file() and not re.search(r"\b" + re.escape(method) + r"\b", read(target)):
            fail(f"matrice › {cap} › {scen}: méthode {method} introuvable dans {source}")
    if status in PROVEN_STATUSES and not paths:
        fail(f"matrice › {cap} › {scen}: statut « {status} » sans chemin de preuve réel")
    if status == "non couvert" and not re.search(r"test non écrit|preuve manquante", proof):
        fail(f"matrice › {cap} › {scen}: « non couvert » doit dire « test non écrit » ou « preuve manquante »")


def check_row(cap: str, cells: list[str]) -> tuple[str, str] | None:
    if len(cells) != 6:
        fail(f"matrice › {cap}: ligne à {len(cells)} colonnes au lieu de 6 ({cells[:2]})")
        return None
    req, scen, view, task, proof, raw_status = cells
    status = raw_status.split(" : ", 1)[0].strip()  # « partiel : précision » est admis
    if status not in STATUSES:
        fail(f"matrice › {cap} › {scen}: statut « {status} » hors de {sorted(STATUSES)}")
    if not (proof and task and view):
        fail(f"matrice › {cap} › {scen}: cellule vide (écrire « — », « preuve manquante » ou « test non écrit »)")
    check_proof(cap, scen, proof, status)
    return norm(req), norm(scen)


def check_capability_rows(cap: str, pairs: list[tuple[str, str]], rows: list[list[str]]) -> None:
    expected = {(norm(r), norm(s)) for r, s in pairs}
    found = {key for key in (check_row(cap, cells) for cells in rows) if key}
    for missing in sorted(expected - found):
        fail(f"matrice › {cap}: ligne absente pour {missing}")
    for stale in sorted(found - expected):
        fail(f"matrice › {cap}: ligne sans exigence/scénario correspondant dans la spec {stale}")


def check_specs_and_matrix() -> None:
    if not MATRIX.is_file():
        fail("docs/developer/openspec-traceability.md est absent")
        return
    specs = {p.parent.name: p for p in sorted(SPECS_DIR.glob("*/spec.md"))}
    rows_by_title = parse_matrix(read(MATRIX))
    for cap, spec in specs.items():
        purpose, pairs = parse_spec(spec)
        check_durable_page(cap, spec, purpose)
        if cap not in rows_by_title:
            fail(f"{cap}: section absente de la matrice de traçabilité")
        else:
            check_capability_rows(cap, pairs, rows_by_title[cap])
    for title in rows_by_title:
        if CAPABILITY_NAME.fullmatch(title) and title not in specs:
            fail(f"matrice: section « {title} » sans spec correspondante")


def check_docs() -> None:
    for p in (MATRIX, GUIDE):
        if not p.is_file():
            fail(f"{p.relative_to(ROOT)} est absent")
            continue
        check_links(p)
        check_mermaid(p)
    for design in sorted(CHANGES_DIR.glob("*/design.md")):
        if design.parent.name != "archive":
            check_links(design)
            check_mermaid(design)
    seen: set[Path] = set()
    for spec in sorted(SPECS_DIR.glob("*/spec.md")):
        purpose, _ = parse_spec(spec)
        reference = durable_reference(purpose)
        if reference is None:
            continue
        page = ROOT / reference.partition("#")[0]
        if page.is_file() and page not in seen:
            seen.add(page)
            section = openspec_sections(page)
            check_links(page, section)
            check_mermaid(page, section)


def main() -> int:
    check_schema_and_config()
    check_specs_and_matrix()
    check_docs()
    if errors:
        for e in errors:
            print(f"openspec-doc-contracts: {e}", file=sys.stderr)
        print(f"openspec-doc-contracts: {len(errors)} écart(s)", file=sys.stderr)
        return 1
    print("openspec-doc-contracts: OK (contrôle statique ; openspec validate et la syntaxe Mermaid se lancent à part)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
