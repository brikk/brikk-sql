import json
import unittest
from probe_datafusion_analysis import OUT, cases
from probe_datafusion_spice import ROOT, SPICE_SHA256


class AnalysisEvidenceTest(unittest.TestCase):
    def test_inputs_and_profiles_are_pinned(self):
        inputs = json.loads((OUT / 'cases.json').read_text())
        self.assertEqual(inputs, cases())
        upstream = json.loads((OUT / 'upstream.json').read_text())
        spice = json.loads((OUT / 'spice.json').read_text())
        self.assertEqual(spice['profile']['binary_sha256'], SPICE_SHA256)
        self.assertEqual(list(upstream), [c['id'] for c in inputs if not c['spice_only']])
        self.assertEqual(list(spice['cases']), [c['id'] for c in inputs])
        for row in upstream.values():
            self.assertEqual(row['profile']['id'], 'datafusion-55.2.0')
        self.assertTrue(all('vector_search(' not in c['sql'] and 'ai_decide(' not in c['sql'] for c in inputs))

    def test_source_evidence_is_separate_from_execution(self):
        capture = json.loads((OUT / 'source-evidence.json').read_text())
        pin = json.loads((ROOT / 'vendor/data/spice/2.4.0/dependency-provenance.json').read_text())['source_commit']
        self.assertEqual(capture['source_commit'], pin)
        for source in capture['sources']:
            self.assertIn('/' + pin + '/', source['url'])
            self.assertEqual(len(source['sha256']), 64)
            for excerpt in source['excerpts']:
                self.assertEqual(len(excerpt['text'].splitlines()), excerpt['end'] - excerpt['start'] + 1)


if __name__ == '__main__':
    unittest.main()
