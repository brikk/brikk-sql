import hashlib
import json
import pathlib
import unittest

import capture_clickhouse_semantic_contract_sources as sources
import probe_clickhouse_decimal_division as matrix

DATA = pathlib.Path(__file__).resolve().parents[1] / "vendor/data/clickhouse-semantic"


class ClickhouseDecimalDivisionEvidenceTest(unittest.TestCase):
    def test_all_1368_independent_native_types_match_kotlin(self):
        folder = DATA / "decimal-division-types"
        raw = (folder / "input.json").read_bytes()
        self.assertEqual(matrix.matrix_input(), json.loads(raw))
        observed = json.loads((folder / "26.10.2.103.json").read_text())
        self.assertEqual("26.10.2.103\n", observed["version_query"]["stdout"])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), observed["input_sha256"])
        typed = json.loads((folder / "brikk.json").read_text())
        self.assertEqual(1368, sum(len(b["types"]) for b in typed))
        for input, native, brikk in zip(json.loads(raw)["batches"], observed["batches"], typed, strict=True):
            self.assertEqual(input["id"], brikk["id"])
            self.assertEqual(native["id"], brikk["id"])
            self.assertEqual(0, native["raw"]["returncode"])
            self.assertEqual(native["types"], json.loads(native["raw"]["stdout"]))
            self.assertEqual(native["types"], brikk["types"])

    def test_tagged_guards_cover_declared_precision_storage_scale_and_nullable_dispatch(self):
        captured = json.loads((DATA / "decimal-division-sources.json").read_text())
        snapshot = captured["snapshots"][0]
        self.assertEqual("26.10.2.103", snapshot["version"])
        self.assertEqual("Apache-2.0", captured["license"])
        self.assertEqual(len(sources.DECIMAL_DIVISION), len(snapshot["sources"]))
        for spec, record in zip(sources.DECIMAL_DIVISION, snapshot["sources"], strict=True):
            self.assertEqual(spec[0], record["path"])
            self.assertRegex(record["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(sources.VERSIONS[snapshot["version"]], record["url"])
            self.assertIn(spec[1], record["excerpts"][0]["text"])
        text = "\n".join(r["excerpts"][0]["text"] for r in snapshot["sources"])
        for guard in ("scale = tx.getScale()", "left.getPrecision(), left.getScale()", "right.getPrecision(), right.getScale()",
                      "decimalCheckArithmeticOverflow", "scale < x.scale", "makeNullableSafe"):
            self.assertIn(guard, text)

    def test_named_refinement_retains_unknown_historical_capture(self):
        old = {r["id"]: r for r in json.loads((DATA / "division-brikk.json").read_text())}
        new = {r["id"]: r for r in json.loads((DATA / "decimal-division-refinement-brikk.json").read_text())}
        refinement = json.loads((DATA / "decimal-division-refinements.json").read_text())
        self.assertEqual({"CHSEM-012-E19": "Decimal(18, 3)"}, refinement)
        for id, type in refinement.items():
            self.assertEqual("UNKNOWN", old[id]["result_type"])
            self.assertEqual(type, new[id]["result_type"])
