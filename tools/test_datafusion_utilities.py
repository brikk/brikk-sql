import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'vendor/data/datafusion-spice/utilities'


class UtilityEvidenceTest(unittest.TestCase):
    def test_structured_utilities_keep_native_ast_semantics(self):
        capture = json.loads((DATA / 'native.json').read_text())
        cases = json.loads((DATA / 'cases.json').read_text())['cases']
        exported = json.loads((DATA / 'brikk.json').read_text())['cases']
        native = capture['profiles']['datafusion-55.2.0']['cases']
        for key, name in [('fixtures_sha256', 'cases.json'), ('brikk_observations_sha256', 'brikk.json')]:
            self.assertEqual(hashlib.sha256((DATA / name).read_bytes()).hexdigest(), capture[key])
        self.assertEqual([c['id'] for c in cases], [c['id'] for c in exported])
        self.assertEqual([c['id'] for c in cases], [c['id'] for c in native])

        def semantic_ast(result):
            text = '\n'.join(s['ast_debug'] for s in result['statements'])
            # Only syntax/source differences: native spans, equivalent identifier delimiters,
            # and ARRAY[...] versus [...]. Never drop options, arguments, node kinds or values.
            text = re.sub(r'Span\(Location\(\d+,\d+\)\.\.Location\(\d+,\d+\)\)', 'Span', text)
            text = text.replace("quote_style: Some('`')", "quote_style: Some('\"')")
            text = text.replace("quote_style: Some('\\\'')", "quote_style: Some('\"')")
            return text.replace('named: false', 'named: true')

        for case, brikk, row in zip(cases, exported, native):
            self.assertFalse(case['execute'])
            expected = 'rejected' if case['area'] == 'negative' else 'accepted'
            self.assertEqual(expected, brikk['status'], case['id'])
            for variant in ['original', 'generated']:
                self.assertNotIn('execute', row[variant])
                if 'parse' in row[variant]:
                    self.assertEqual('parse-only', row[variant]['parse']['evidence'])
                    self.assertEqual('500744d5c25499055e3b2ca46b022bc425989203', row[variant]['parse']['profile']['revision'])
            self.assertEqual(expected, row['original']['parse']['status'], case['id'])
            if expected == 'accepted':
                self.assertTrue(all('Command' not in s['nodes'] for s in brikk['statements']), case['id'])
                self.assertEqual('accepted', row['generated']['parse']['status'], case['id'])
                self.assertEqual(semantic_ast(row['original']['parse']), semantic_ast(row['generated']['parse']), case['id'])


if __name__ == '__main__':
    unittest.main()
