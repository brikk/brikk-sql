import hashlib
import json
import unittest

import generate_clickhouse_functions as generator
import generate_keywords


class ClickhouseCaptureTest(unittest.TestCase):
    versions = ("26.5.1.1", "26.8.34.13", "26.10.2.103")

    def test_generated_catalog_is_deterministic_and_current(self):
        first = generator.render(generator.DATA, self.versions)
        self.assertEqual(first, generator.render(generator.DATA, self.versions))
        self.assertEqual(generator.OUT.read_text(), first)
        self.assertIn("val CLICKHOUSE_FUNCTION_CATALOG: FunctionCatalog get() = CLICKHOUSE_26_10_2_103_FUNCTION_CATALOG", first)
        self.assertNotIn("overloads =", first)
        self.assertNotIn("sinceVersion =", first)
        self.assertIn('aliases = listOf("sparkBar")', first)

    def test_native_capture_hashes_and_version_queries_match_pins(self):
        for version in self.versions[1:]:
            manifest = json.loads((generator.DATA / f"clickhouse-capture-{version}.json").read_text())
            self.assertEqual(version, manifest["version_query"]["stdout"].strip())
            self.assertIn("@sha256:", manifest["engine"]["image"])
            for row in manifest["captures"].values():
                if "file" in row:
                    self.assertEqual(row["sha256"], hashlib.sha256((generator.DATA / row["file"]).read_bytes()).hexdigest())
            self.assertEqual(version.startswith("26.8"), "unavailable" in manifest["captures"]["statements"])

    def test_each_capture_preserves_case_flags_and_new_types(self):
        for version in self.versions[1:]:
            functions = generator.rows(generator.DATA / f"clickhouse-functions-{version}.tsv")
            self.assertTrue(any(r["name"] == "SUM" or r["name"] == "sum" for r in functions))
            self.assertTrue(any(r["name"] == "arrayTopK" and r["case_insensitive"] == "0" for r in functions))
            types = generator.rows(generator.DATA / f"clickhouse-types-{version}.tsv")
            self.assertEqual({"QBit", "MultiPoint"}, {r["name"] for r in types if r["name"] in ("QBit", "MultiPoint")})

    def test_keyword_generation_can_select_exact_captures(self):
        name = "clickhouse-keywords-26.10.2.103.tsv"
        output = generate_keywords.render(generator.DATA, [name])
        self.assertIn("CLICKHOUSE_26_10_2_103_KEYWORDS", output)
        self.assertNotIn("CLICKHOUSE_26_5_1_1_KEYWORDS", output)
        self.assertNotIn("TRINO_", output)

    def test_tsv_and_kotlin_escaping_keep_documentation_text(self):
        self.assertEqual("a\nb\t'c\\d", generator.unescape(r"a\nb\t\'c\\d"))
        self.assertEqual('"a\\n\\t\\\"\\$"', generator.kstr('a\n\t"$'))


if __name__ == "__main__":
    unittest.main()
