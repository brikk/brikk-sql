#!/usr/bin/env python3
"""Capture tagged implementation guards for the bounded semantic rounds."""
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
DIVISION = [
    ("src/Functions/divide.cpp", "struct DivideFloatingImpl", "class FunctionDivideExactBase"),
    ("src/Functions/divide.cpp", "class FunctionDivideExact final", "REGISTER_FUNCTION(Divide)"),
    ("src/Functions/FunctionExactDivide.cpp", "using IntegerTypes", "ColumnPtr executeDivision"),
    ("src/Functions/FunctionExactDivide.cpp", "DataTypePtr getReturnTypeImpl", "ColumnPtr executeImpl"),
    ("src/DataTypes/NumberTraits.h", "struct ResultOfFloatingPointDivision", "struct ResultOfModulo"),
    ("src/Functions/IFunction.cpp", "if (!arguments.empty() && useDefaultImplementationForNulls())", "return getReturnTypeImpl(arguments);"),
]
SCRIPT_SETTINGS = [
    ("src/Interpreters/InterpreterSetQuery.cpp", "BlockIO InterpreterSetQuery::execute()", "void InterpreterSetQuery::executeForCurrentContext"),
    ("src/Interpreters/InterpreterSetQuery.cpp", "void InterpreterSetQuery::executeForCurrentContext", "static void applySettingsFromSelectWithUnion"),
    ("src/Parsers/ParserSetQuery.cpp", "bool ParserSetQuery::parseNameValuePairWithParameterOrDefault", "bool ParserSetQuery::parseImpl"),
    ("src/Interpreters/Context.cpp", "void Context::resetSettingsToDefaultValue", "std::shared_ptr<const SettingsConstraintsAndProfileIDs> Context::getSettingsConstraintsAndCurrentProfilesWithLock"),
]
DECIMAL_DIVISION = [
    ("src/Core/DecimalFunctions.h", "inline auto binaryOpResult", "/// Returns the current time."),
    ("src/Core/DecimalFunctions.h", "struct DataTypeDecimalTrait", "template <typename T, bool throw_on_error>"),
    ("src/Functions/FunctionBinaryArithmetic.h", "using ResultDataType = typename BinaryOperationTraits<Op, LeftDataType, RightDataType>::ResultDataType;", "else if constexpr (std::is_same_v<ResultDataType, DataTypeDateTime>)"),
    DIVISION[0], DIVISION[1], DIVISION[-1],
]
DECIMAL_DIAGNOSTICS = DECIMAL_DIVISION + [
    ("src/Functions/FunctionBinaryArithmetic.h", "struct DecimalBinaryOperation", "using namespace traits_;"),
    ("src/Functions/FunctionBinaryArithmetic.h", "NativeResultType castConstantToNative", "void helperInvokeEither"),
    ("src/Functions/FunctionBinaryArithmetic.h", "        using T0 = typename LeftDataType::FieldType;", "if (col_left && col_right)"),
    ("src/Functions/DivisionUtils.h", "inline void throwIfDivisionLeadsToFPE", "inline bool divisionLeadsToFPE"),
]
DECIMAL_RUNTIME = DECIMAL_DIAGNOSTICS + [
    ("base/base/arithmeticOverflow.h", "inline bool mulOverflow(T x, T y, T & res)", "// NOLINTEND"),
]
PROJECTION_LITERALS = [
    ("src/DataTypes/FieldToDataType.cpp", "operator() (const Null &)", "operator() (const UUID &)"),
    ("src/DataTypes/FieldToDataType.cpp", "operator() (const String &)", "operator() (const DecimalField<Decimal32>"),
    ("src/DataTypes/FieldToDataType.cpp", "operator()(const bool &)", "template class FieldToDataType"),
    ("src/Parsers/ExpressionElementParsers.cpp", "bool ParserNull::parseImpl", "bool ParserUnsignedInteger::parseImpl"),
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
    parser.add_argument("--round", choices=("higher-order", "overload", "division", "script-settings", "decimal-division", "decimal-diagnostics", "decimal-runtime", "projection-literals"), default="higher-order")
    args = parser.parse_args()
    snapshots = []
    for version, tag in VERSIONS.items():
        if args.round in ("division", "script-settings", "decimal-division", "decimal-diagnostics", "decimal-runtime", "projection-literals") and version != "26.10.2.103":
            continue
        records = []
        cache = {}
        selections = {"higher-order": HIGHER_ORDER, "overload": OVERLOAD, "division": DIVISION, "script-settings": SCRIPT_SETTINGS, "decimal-division": DECIMAL_DIVISION, "decimal-diagnostics": DECIMAL_DIAGNOSTICS, "decimal-runtime": DECIMAL_RUNTIME, "projection-literals": PROJECTION_LITERALS}[args.round]
        task = {"higher-order": "CHSEM-008", "overload": "CHSEM-009", "division": "CHSEM-012", "script-settings": "CHSEM-014", "decimal-division": "CHSEM-015", "decimal-diagnostics": "CHSEM-016", "decimal-runtime": "CHSEM-017", "projection-literals": "CHSEM-019"}[args.round]
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
