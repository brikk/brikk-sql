import json
import pathlib
import unittest

import capture_clickhouse_semantic_contract_sources as sources

DATA = pathlib.Path(__file__).resolve().parents[1] / "vendor/data/clickhouse-semantic"


class ClickhouseScientificBoundaryEvidenceTest(unittest.TestCase):
    def test_compact_forms_have_separate_number_and_operator_tokens(self):
        rows = json.loads((DATA / "scientific-number-brikk.json").read_text())
        self.assertEqual(22, len(rows))
        for row in rows:
            self.assertEqual("Float64", row["result_type"])
            self.assertEqual([], row["rules"])
            for token in row["tokens"]:
                if token["kind"] == "NUMBER":
                    self.assertNotRegex(token["text"], r"[eE][+-]?[0-9]+[+-]")
                self.assertEqual(token["text"], row["input_sql"][token["start"]:token["end"] + 1])

    def test_tagged_lexer_separates_exponent_sign_digits_and_binary_tokens(self):
        captured = json.loads((DATA / "scientific-number-sources.json").read_text())
        self.assertEqual("Apache-2.0", captured["license"])
        snapshot = captured["snapshots"][0]
        self.assertEqual("26.10.2.103", snapshot["version"])
        self.assertEqual(len(sources.SCIENTIFIC_BOUNDARY), len(snapshot["sources"]))
        for spec, row in zip(sources.SCIENTIFIC_BOUNDARY, snapshot["sources"], strict=True):
            self.assertEqual(spec[0], row["path"])
            self.assertIn(sources.VERSIONS[snapshot["version"]], row["url"])
            self.assertRegex(row["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(spec[1], row["excerpts"][0]["text"])
        text = "\n".join(row["excerpts"][0]["text"] for row in snapshot["sources"])
        self.assertIn("sign of exponent", text)
        self.assertIn("isNumericASCII", text)
        self.assertIn("return try_read_float", text)

    def test_old_gap_is_preserved_but_current_witnesses_have_proved_float64(self):
        old = json.loads((DATA / "floating-add-sub-parser-gap-brikk.json").read_text())["cases"]
        current = json.loads((DATA / "scientific-number-brikk.json").read_text())
        self.assertEqual(["UNKNOWN", "UNKNOWN"], [row["type"] for row in old])
        for row in old:
            matching = next(value for value in current if value["input_sql"] == row["sql"])
            self.assertEqual("Float64", matching["result_type"])
