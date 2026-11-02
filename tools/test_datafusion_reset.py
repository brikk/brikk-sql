import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'vendor/data/datafusion-spice/reset'


class ResetEvidenceTest(unittest.TestCase):
    def test_capture_is_pinned_current_and_parser_only(self):
        capture = json.loads((DATA / 'native.json').read_text())
        for key, name in [('fixtures_sha256', 'cases.json'), ('brikk_observations_sha256', 'brikk.json')]:
            self.assertEqual(hashlib.sha256((DATA / name).read_bytes()).hexdigest(), capture[key])
        cases = json.loads((DATA / 'cases.json').read_text())['cases']
        self.assertTrue(all(case['execute'] is False for case in cases))
        rows = capture['profiles']['datafusion-55.2.0']['cases']
        self.assertEqual([case['id'] for case in cases], [row['id'] for row in rows])
        for row in rows:
            for variant in ['original', 'generated']:
                self.assertNotIn('execute', row[variant])
                if 'parse' in row[variant]:
                    verdict = row[variant]['parse']
                    self.assertEqual('parse-only', verdict['evidence'])
                    self.assertEqual('500744d5c25499055e3b2ca46b022bc425989203', verdict['profile']['revision'])


if __name__ == '__main__':
    unittest.main()
