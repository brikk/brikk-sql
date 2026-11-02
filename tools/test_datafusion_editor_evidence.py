import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'vendor/data/datafusion-spice/round8'


class EditorEvidenceTest(unittest.TestCase):
    def test_native_diagnostic_controls_keep_rejected_unknown_signatures(self):
        data = json.loads((DATA / 'diagnostics-native.json').read_text())
        expected = ['rejected', 'rejected', 'accepted', 'rejected', 'rejected', 'rejected', 'accepted']
        for request, status in zip(data['requests'], expected, strict=True):
            row = data['responses'][request['id']]
            self.assertEqual(status, row['status'])
            self.assertEqual('datafusion-55.2.0', row['profile']['id'])
            self.assertNotIn('ai_', request['sql'])
            self.assertNotIn('search', request['sql'])

    def test_editor_source_contracts_use_the_pinned_release(self):
        data = json.loads((DATA / 'editor-source-evidence.json').read_text())
        pin = json.loads((ROOT / 'vendor/data/spice/2.4.0/dependency-provenance.json').read_text())['source_commit']
        self.assertEqual(pin, data['source_commit'])
        for row in data['sources']:
            self.assertIn('/' + pin + '/', row['url'])
            self.assertEqual(64, len(row['sha256']))
            for excerpt in row['excerpts']:
                self.assertEqual(excerpt['end'] - excerpt['start'] + 1, len(excerpt['text'].split('\n')))


if __name__ == '__main__':
    unittest.main()
