import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'vendor/data/datafusion-spice/round9'


class FlightEvidenceTest(unittest.TestCase):
    data = DATA
    def test_both_production_harvesters_ran_over_the_pinned_jdbc_driver(self):
        source = (ROOT / 'brikk-sql-intellij-support/src/dev.brikk.house.sql.intellij.catalog/DatafusionCatalogHarvester.kt').read_text()
        queries = re.findall(r'const val SQL_\w+: String = "([^"]+)"', source)
        self.assertEqual(8, len(queries))
        for product in ['datafusion', 'spice']:
            capture = json.loads((self.data / f'{product}-jdbc.json').read_text())
            self.assertEqual('org.apache.arrow:flight-sql-jdbc-driver:19.0.0', capture['driver']['coordinates'])
            self.assertEqual('d3beee43c613c457789825343368f652d570d76c08799dad38a43a10e569b57f', capture['driver']['sha256'])
            observations = capture['observations']
            for sql in queries:
                self.assertEqual('accepted', observations['queries'][sql]['status'], (product, sql))
            self.assertEqual('accepted', observations['production_harvest']['status'])
            self.assertEqual(product, observations['production_harvest']['value']['product'])
            self.assertEqual('unknown', observations['production_harvest']['value']['signature_policy'])
            self.assertEqual([], observations['functions']['value']['rows'])

    def test_fixture_is_pinned_and_sessions_retain_their_own_facts(self):
        capture = json.loads((self.data / 'datafusion-jdbc.json').read_text())
        for field, path in [('source_sha256', 'src/flight.rs'), ('lock_sha256', 'flight/Cargo.lock')]:
            self.assertEqual(hashlib.sha256((ROOT / 'tools/datafusion-reference' / path).read_bytes()).hexdigest(), capture['runtime'][field])
        sessions = capture['observations']['sessions']
        self.assertEqual('accepted', sessions['create']['status'])
        self.assertEqual([['42']], sessions['own_view']['value']['rows'])
        self.assertEqual('unsupported-or-failed', sessions['other_view']['status'])
        self.assertEqual([['false']], sessions['own_setting']['value']['rows'])
        self.assertEqual([['true']], sessions['other_setting']['value']['rows'])
        # The JDBC SET update reports failure despite its effect. Keep that limitation visible.
        self.assertEqual('unsupported-or-failed', sessions['set']['status'])


if __name__ == '__main__':
    unittest.main()
