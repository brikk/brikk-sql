#!/usr/bin/env python3
"""Generate conservative, versioned catalogs from pinned DataFusion/Spice captures.

Only complete finite registry signatures matched to all observed return types become
FunctionOverloads. SHOW FUNCTIONS samples and missing signatures are separate evidence.
Spice's SQL inventory does not expose canonical alias identities or complete signatures.
Its names remain individual definitions, with representative samples available separately.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "brikk-sql-metadata/src/dev.brikk.house.sql.metadata"
KINDS = {"SCALAR": "SCALAR", "AGGREGATE": "AGGREGATE", "WINDOW": "WINDOW", "TABLE": "TABLE_VALUED"}


def kstr(value):
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$") if value is not None else "null"


def klist(values):
    return "listOf(" + ", ".join(kstr(v) for v in values) + ")"


def exact_arguments(signature):
    if not signature:
        return None
    kind = signature["kind"]
    if kind == "Exact":
        return [signature["types"]]
    if kind == "Nullary":
        return [[]]
    if kind == "Uniform":
        return [[t] * signature["arity"] for t in signature["types"]]
    if kind == "OneOf":
        variants = [exact_arguments(v) for v in signature["variants"]]
        return None if any(v is None for v in variants) else [args for v in variants for args in v]
    return None


def model(capture, overrides=()):
    routines = capture["queries"]["routines"]
    if routines["status"] != "accepted":
        raise ValueError("A complete routine inventory is required")
    groups = {}
    for row in routines["rows"]:
        kind = row["function_type"]
        if kind not in KINDS:
            raise ValueError(f"Unmapped routine kind {kind}")
        groups.setdefault((row["routine_name"], kind), []).append(row)
    registry = {(r["name"], r["kind"]): r for r in capture.get("registry") or []}
    # The upstream information_schema version may omit table routines; supplement
    # only from the actual registered table functions, never a parser name list.
    for key in registry:
        groups.setdefault(key, [])
    show = capture["queries"]["show_functions"]
    samples = {}
    if show["status"] == "accepted":
        for row in show["rows"]:
            key = row["function_name"], row["function_type"]
            if key not in groups:
                raise ValueError(f"SHOW FUNCTIONS references missing routine {key}")
            samples.setdefault(key, []).append(row)
    defs = []
    for key, rows in sorted(groups.items()):
        name, kind = key
        reg = registry.get(key)
        observed = samples.get(key, [])
        exact = exact_arguments(reg["signature"]) if reg else None
        overloads = []
        if exact is not None:
            for args in exact:
                matches = [s for s in observed if s.get("parameter_types") == args and s.get("return_type") not in [None, "TABLE"]]
                returns = {s["return_type"] for s in matches}
                if len(returns) != 1:
                    overloads = []
                    break
                sample = matches[0]
                names = sample.get("parameters")
                if names is None or len(names) != len(args) or any(n is None for n in names):
                    names = None
                overloads.append({"args": args, "return": sample["return_type"], "names": names})
        descriptions = sorted({r["description"] for r in rows if r.get("description")})
        syntax = sorted({r["syntax_example"] for r in rows if r.get("syntax_example")})
        defs.append({"name": name, "kind": KINDS[kind], "native_kind": kind,
                     "canonical": reg["canonical_name"] if reg else None,
                     "signature": reg["signature"]["native"] if reg and reg["signature"] else None,
                     "volatility": reg["volatility"] if reg else ("Immutable" if rows and all(r.get("is_deterministic") is True for r in rows) else None),
                     "overloads": overloads, "descriptions": descriptions, "syntax": syntax,
                     "samples": observed,
                     "aliases": sorted({r["name"] for r in registry.values()
                                        if r["canonical_name"] == name and r["name"] != name and r["kind"] == kind})})
    by_key = {(d["name"], d["native_kind"]): d for d in defs}
    for override in overrides:
        key = override["name"], override["kind"]
        if key not in by_key:
            raise ValueError(f"Source override absent from runtime inventory: {key}")
        by_key[key]["native_kind"] = override["native_kind"]
    return defs


def chunks(lines, prefix, type_name, entries, budget=30):
    names = []
    for start in range(0, len(entries), budget):
        name = f"{prefix}{start // budget}"
        names.append(name + "()")
        lines += [f"private fun {name}(): List<{type_name}> = listOf(", *entries[start:start + budget], ")", ""]
    return " + ".join(names) if names else "emptyList()"


def generate(path, product, version):
    raw = path.read_bytes()
    capture = json.loads(raw)
    override_path = path.with_name("function-overrides.json")
    overrides = json.loads(override_path.read_text())["entries"] if override_path.exists() else []
    definitions = model(capture, overrides)
    upper = product.upper()
    title = "Datafusion" if product == "datafusion" else "Spice"
    lines = ["// GENERATED by tools/generate_datafusion_metadata.py; do not edit.",
             f"// {product} {version}, capture SHA-256 {hashlib.sha256(raw).hexdigest()}.",
             "// Overloads contain only fully enumerated registry arguments with observed return types.",
             "// Other signatures remain unknown; introspection examples live in separate evidence.",
             "package dev.brikk.house.sql.metadata", ""]
    if override_path.exists():
        lines.insert(2, f"// Source override SHA-256 {hashlib.sha256(override_path.read_bytes()).hexdigest()}.")
    entries, details = [], []
    for d in definitions:
        # Canonical definitions own aliases when identity is source-proven. Spice
        # has no canonical registry export yet, so every registered name is retained.
        if d["canonical"] in [None, d["name"]]:
            overloads = []
            for o in d["overloads"]:
                names = ", argNames = " + klist(o["names"]) if o["names"] is not None else ""
                overloads.append(f"FunctionOverload({klist(o['args'])}, {kstr(o['return'])}{names})")
            notes = "Finite registry arguments and observed returns." if overloads else "Signature unknown or non-finite; do not validate arity from SHOW FUNCTIONS samples."
            if d["native_kind"] == "SCALAR_STUB":
                notes += " Scalar stub for nested table-function syntax, not an executable scalar call."
            native = ", nativeKind = " + kstr(d["native_kind"]) if d["native_kind"] == "SCALAR_STUB" else ""
            entries.append(f"    FunctionDef({kstr(d['name'])}, FunctionKind.{d['kind']}, aliases = {klist(d['aliases'])}, overloads = listOf({', '.join(overloads)}), profile = SemanticProfile(notes = {kstr(notes)}){native}),")
        samples = []
        for s in d["samples"]:
            args = klist(s["parameter_types"]) if s.get("parameter_types") is not None and d["kind"] != "TABLE_VALUED" else "null"
            names = klist(s["parameters"]) if s.get("parameters") is not None and d["kind"] != "TABLE_VALUED" else "null"
            samples.append(f"DatafusionSignatureSample({args}, {kstr(s.get('return_type'))}, {names})")
        details.append(f"    DatafusionFunctionEvidence({kstr(d['name'])}, FunctionKind.{d['kind']}, {kstr(d['canonical'])}, {kstr(d['signature'])}, {kstr(d['volatility'])}, {klist(d['descriptions'])}, {klist(d['syntax'])}, listOf({', '.join(samples)})),")
    catalog_expr = chunks(lines, product + "Functions", "FunctionDef", entries)
    detail_expr = chunks(lines, product + "Evidence", "DatafusionFunctionEvidence", details, 15)
    # Keyword presence is known; reservation changes by dialect and SQL position.
    words = sorted(set(capture["keywords"]))
    keyword_expr = chunks(lines, product + "Keywords", "KeywordDef", [f"    KeywordDef({kstr(w)})," for w in words], 100)
    settings = capture["queries"]["settings"].get("rows", [])
    setting_expr = chunks(lines, product + "Settings", "SettingDef", [f"    SettingDef({kstr(s['name'])}, description = {kstr(s.get('description'))})," for s in settings], 40)
    defaults_expr = ",\n".join(f"    {kstr(s['name'])} to {kstr(s.get('value', s.get('setting')))}" for s in settings)
    types = []
    for name, probe in capture["type_probes"].items():
        if probe["status"] != "accepted" or not re.fullmatch("[A-Z][A-Z0-9_]*", name):
            continue
        rows = probe["rows"]
        native = rows[0][0] if product == "datafusion" else rows[0]["native_type"]
        types.append(f"    TypeDef({kstr(name)}, {kstr(native)}, caseInsensitive = true),")
    types_expr = chunks(lines, product + "Types", "TypeDef", types)
    table_names = sorted(d["name"] for d in definitions if d["kind"] == "TABLE_VALUED")
    lines += [f"val {upper}_FUNCTION_CATALOG: FunctionCatalog = FunctionCatalog(",
              f"    {catalog_expr}, grammarBuiltins = DATAFUSION_GRAMMAR_BUILTINS,", ")", "",
              f"val {upper}_PROFILE_EVIDENCE: DatafusionProfileEvidence = DatafusionProfileEvidence(",
              f"    {kstr(product + '-' + version)}, {kstr(capture['availability'])}, {kstr(hashlib.sha256(raw).hexdigest())},",
              f"    {detail_expr}, mapOf(\n{defaults_expr}\n    ),", ")", "",
              f"internal fun {product}Snapshot(): MetadataSnapshot = MetadataSnapshot(",
              f"    {kstr(version)}, {upper}_FUNCTION_CATALOG, KeywordCatalog({keyword_expr}),",
              f"    settings = {setting_expr if capture['queries']['settings']['status'] == 'accepted' else 'null'},",
              f"    tableFunctions = {klist(table_names)}.map {{ TableFunctionInfo(it) }},",
              f"    grammarForms = DATAFUSION_GRAMMAR_FORMS, types = {types_expr},", ")", ""]
    return title, "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, default=ROOT / "vendor/data")
    parser.add_argument("--output", type=Path, default=OUT)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for product, version in [("datafusion", "55.2.0"), ("spice", "2.4.0")]:
        title, text = generate(args.data_root / product / version / "metadata-capture.json", product, version)
        output = args.output / f"Generated{title}FunctionCatalog.kt"
        output.write_text(text)
        print(output)
