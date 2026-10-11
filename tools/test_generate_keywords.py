import pathlib
import re
import tempfile
import unittest

import generate_keywords

HEADER = "word\tcategory\ttoken\n"


class GenerateKeywordsTest(unittest.TestCase):
    def test_two_versions_of_one_dialect_get_distinct_vals_and_helpers(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = pathlib.Path(tmp)
            (data / "trino-keywords-483.tsv").write_text(HEADER + "SELECT\tRESERVED\tSELECT\n")
            (data / "trino-keywords-490.tsv").write_text(HEADER + "SELECT\tRESERVED\tSELECT\nADD\tUNRESERVED\tADD\n")
            text = generate_keywords.render(data)

        declarations = re.findall(r"^(?:internal val|private fun) (\w+)", text, re.MULTILINE)
        self.assertEqual(len(declarations), len(set(declarations)), declarations)
        self.assertIn("TRINO_483_KEYWORDS", declarations)
        self.assertIn("TRINO_490_KEYWORDS", declarations)


if __name__ == "__main__":
    unittest.main()
