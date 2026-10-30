import json
from pathlib import Path
import unittest

from generate_datafusion_metadata import ROOT, generate, exact_arguments, model


class DatafusionMetadataGenerationTest(unittest.TestCase):
    def test_non_finite_signatures_do_not_become_fixed_arities(self):
        for signature in [{"kind": "UserDefined"}, {"kind": "VariadicAny"},
                          {"kind": "OneOf", "variants": [{"kind": "Exact", "types": ["Int64"]}, {"kind": "Any", "arity": 2}]}]:
            self.assertIsNone(exact_arguments(signature))
        self.assertEqual(exact_arguments({"kind": "Nullary"}), [[]])

    def test_same_named_kinds_and_missing_signatures_survive_capture(self):
        capture = json.loads((ROOT / "vendor/data/spice/2.4.0/metadata-capture.json").read_text())
        defs = model(capture)
        search = [d for d in defs if d["name"] == "vector_search"]
        self.assertEqual({d["kind"] for d in search}, {"SCALAR", "TABLE_VALUED"})
        self.assertTrue(all(not d["overloads"] for d in search))
        self.assertTrue(all(d["canonical"] is None for d in search))

    def test_generation_is_deterministic_and_current(self):
        for product, version in [("datafusion", "55.2.0"), ("spice", "2.4.0")]:
            path = ROOT / f"vendor/data/{product}/{version}/metadata-capture.json"
            title, text = generate(path, product, version)
            expected = ROOT / f"brikk-sql-metadata/src/dev.brikk.house.sql.metadata/Generated{title}FunctionCatalog.kt"
            self.assertEqual(text, expected.read_text())
            self.assertEqual(text, generate(path, product, version)[1])


if __name__ == "__main__":
    unittest.main()
