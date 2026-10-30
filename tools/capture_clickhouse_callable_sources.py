#!/usr/bin/env python3
"""Capture tagged C++ declarations supporting CHSEM-002, not inferred overloads.

Use --source-dir for an offline tree laid out as <version>/<upstream path>, or
fetch the exact release tags. Only selected excerpts and whole-file hashes are
stored; runtime facts remain hand-reviewed in ClickhouseCallableFacts.kt.
"""
import argparse
import hashlib
import json
import pathlib
import urllib.request

VERSIONS = {"26.8.34.13": "v26.8.34.13-lts", "26.10.2.103": "v26.10.2.103-stable"}

# The optional third marker scopes a selection to its owning class. End markers
# are inclusive; an empty end captures the marker's line only. Fail if any marker
# disappears instead of silently certifying a changed declaration.
SELECTIONS = [
    ("CHSEM-002-S01", "src/Functions/FunctionStringToString.h", [
        ("template <typename Impl, typename Name", "\n};", "")]),
    ("CHSEM-002-S02", "src/Functions/lower.cpp", [
        ("struct NameLower", "using FunctionLower =", ""),
        ("    factory.registerFunction<FunctionLower>", "    factory.registerAlias", "")]),
    ("CHSEM-002-S03", "src/Functions/upper.cpp", [
        ("struct NameUpper", "using FunctionUpper =", ""),
        ("    factory.registerFunction<FunctionUpper>", "    factory.registerAlias", "")]),
    ("CHSEM-002-S04", "src/Functions/URL/domainWithoutWWW.cpp", [
        ("struct NameDomainWithoutWWW ", "using FunctionDomainWithoutWWW =", "")]),
    ("CHSEM-002-S05", "src/Functions/URL/firstSignificantSubdomain.cpp", [
        ("struct NameFirstSignificantSubdomain ", "using FunctionFirstSignificantSubdomain =", "")]),
    ("CHSEM-002-S06", "src/Functions/trim.cpp", [
        ("class FunctionTrim final", "    ColumnPtr executeImpl", ""),
        ('    factory.registerAlias("trim",', "", "")]),
    ("CHSEM-002-S07", "src/Functions/concat.cpp", [
        ("class ConcatOverloadResolver final", "\n};", ""),
        ("    DataTypePtr getReturnTypeImpl", "    ColumnPtr executeImpl", "class ConcatImpl final"),
        ("    ColumnPtr executeImpl", "private:", "class ConcatImpl final"),
        ("    factory.registerFunction<ConcatOverloadResolver>", "", "")]),
    ("CHSEM-002-S08", "src/Functions/multiply.cpp", [
        ("template <typename A, typename B>", "using FunctionMultiply =", "")]),
    ("CHSEM-002-S09", "src/Functions/FunctionBinaryArithmetic.h", [
        ("class BinaryArithmeticOverloadResolver final", "    bool isVariadic() const override", "")]),
    ("CHSEM-002-S10", "src/DataTypes/NumberTraits.h", [
        ("constexpr size_t nextSize", "\n};", ""),
        ("template <> struct Construct<false, false, 1>", "template <> struct Construct<true, true, 8>", ""),
        ("template <typename A, typename B> struct ResultOfAdditionMultiplication", "\n};", "")]),
    ("CHSEM-002-S11", "src/Functions/FunctionsConversion.h", [
        ("class FunctionConvert final", "    bool isInjective(", ""),
        ("    DataTypePtr getReturnTypeImpl(const ColumnsWithTypeAndName & arguments)", "    DataTypePtr getReturnTypeImplRemovedNullable", "class FunctionConvert final"),
        ("    DataTypePtr getReturnTypeImplRemovedNullable", "        if constexpr (std::is_same_v<ToDataType, DataTypeInterval>)", ""),
        ("                return std::make_shared<ToDataType>();", "", "class FunctionConvert final"),
        ("using FunctionToString =", "", "")]),
    ("CHSEM-002-S12", "src/Functions/IFunction.h", [
        ("    virtual bool isVariadic() const", "", "class IFunction\n")]),
    ("CHSEM-002-S14", "src/Functions/StringHelpers.h", [
        ("    static void vectorFixed(", "\n    }", "struct ExtractSubstringImpl")]),
]

COMPOSITION_SELECTION = (
    "CHSEM-002-S13", "src/Analyzer/Resolve/resolveFunction.cpp", [
        ("        auto resolve_identifier_operand =", "        /// A composition in any argument position", "")])


def excerpt(source, start_marker, end_marker, scope_marker):
    scope = source.index(scope_marker) if scope_marker else 0
    start = source.index(start_marker, scope)
    end = source.index(end_marker, start + len(start_marker)) if end_marker else start
    line_start = source.rfind("\n", 0, start) + 1
    line_end = source.find("\n", end + len(end_marker))
    if line_end < 0:
        line_end = len(source)
    return {"start_line": source.count("\n", 0, line_start) + 1,
            "end_line": source.count("\n", 0, line_end) + 1,
            "text": source[line_start:line_end]}


def capture(source_dir=None):
    snapshots = []
    for version, tag in VERSIONS.items():
        selections = SELECTIONS + ([COMPOSITION_SELECTION] if version == "26.10.2.103" else [])
        sources = []
        for id, path, markers in selections:
            url = f"https://raw.githubusercontent.com/ClickHouse/ClickHouse/{tag}/{path}"
            raw = (source_dir / version / path).read_bytes() if source_dir else urllib.request.urlopen(url, timeout=30).read()
            source = raw.decode("utf-8")
            sources.append({"id": id, "path": path, "url": url,
                            "sha256": hashlib.sha256(raw).hexdigest(),
                            "excerpts": [excerpt(source, *selection) for selection in markers]})
        snapshots.append({"version": version, "tag": tag, "sources": sources})
    return {"license": "Apache-2.0", "snapshots": snapshots}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(capture(args.source_dir), indent=2) + "\n")


if __name__ == "__main__":
    main()
