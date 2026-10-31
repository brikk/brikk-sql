#!/usr/bin/env python3
"""Capture tagged implementation guards for the higher-order and overload rounds."""
import argparse
import hashlib
import json
import pathlib
import urllib.request

VERSIONS = {"26.8.34.13": "v26.8.34.13-lts", "26.10.2.103": "v26.10.2.103-stable"}
HIGHER_ORDER = [
    ("src/Functions/array/FunctionArrayMapped.h", "void getLambdaArgumentTypes", "ColumnPtr executeImplDryRun"),
    ("src/Functions/array/arrayFilter.h", None, None),
    ("src/Functions/array/arrayExists.h", None, None),
    ("src/Functions/array/arrayAll.h", None, None),
    ("src/Functions/array/arraySort.h", None, None),
    ("src/Functions/array/FunctionsMapMiscellaneous.cpp", "void getLambdaArgumentTypes", "ColumnPtr executeImpl("),
    ("src/Functions/array/FunctionsMapMiscellaneous.cpp", "struct MapAdapterBase", "/// Adapter that extracts array with keys or values"),
    ("src/Functions/FunctionsComparison.h", "DataTypePtr getReturnTypeImpl(const DataTypes & arguments)", "DataTypePtr getReturnTypeForDefaultImplementationForDynamic"),
]
OVERLOAD = [
    ("src/DataTypes/FieldToDataType.cpp", "operator() (const UInt64 & x)", "operator() (const UInt128 &)"),
    ("src/DataTypes/getLeastSupertype.cpp", "DataTypePtr getNumericType", "DataTypePtr findSmallestIntervalSuperType"),
    ("src/DataTypes/getLeastSupertype.cpp", "DataTypePtr getLeastSuperTypeForTuple", "DataTypePtr getLeastSupertype(const DataTypes & types)"),
    ("src/DataTypes/getLeastSupertype.cpp", "/// For Arrays", "/// For JSON (Object) types."),
    ("src/Core/DecimalFunctions.h", "template <> inline constexpr size_t max_precision<Decimal32>", "struct DataTypeDecimalTrait"),
    ("src/Core/DecimalFunctions.h", "inline auto binaryOpResult", "/// Returns the current time."),
    ("src/Functions/IFunction.cpp", "if (!arguments.empty() && useDefaultImplementationForNulls())", "return getReturnTypeImpl(arguments);"),
    ("src/Functions/array/FunctionArrayMapped.h", "void getLambdaArgumentTypes", "ColumnPtr executeImplDryRun"),
]


def selection(data, begin, end):
    lines = data.decode().splitlines()
    first = next((i for i, line in enumerate(lines) if begin in line), None) if begin else 0
    if first is None:
        raise ValueError(f"Missing source start: {begin}")
    last = next((i for i in range(first + 1, len(lines)) if end in lines[i]), None) if end else len(lines)
    if last is None:
        raise ValueError(f"Missing source end: {end}")
    return {"start_line": first + 1, "end_line": last, "text": "\n".join(lines[first:last])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--round", choices=("higher-order", "overload"), default="higher-order")
    args = parser.parse_args()
    snapshots = []
    for version, tag in VERSIONS.items():
        records = []
        cache = {}
        selections = HIGHER_ORDER if args.round == "higher-order" else OVERLOAD
        task = "CHSEM-008" if args.round == "higher-order" else "CHSEM-009"
        for index, (path, begin, end) in enumerate(selections, 1):
            url = f"https://raw.githubusercontent.com/ClickHouse/ClickHouse/{tag}/{path}"
            if path not in cache:
                cache[path] = urllib.request.urlopen(url, timeout=30).read()
            data = cache[path]
            records.append({"id": f"{task}-S{index:02}", "path": path, "url": url,
                            "sha256": hashlib.sha256(data).hexdigest(), "excerpts": [selection(data, begin, end)]})
        snapshots.append({"version": version, "tag": tag, "sources": records})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"license": "Apache-2.0", "snapshots": snapshots}, indent=2) + "\n")


if __name__ == "__main__":
    main()
