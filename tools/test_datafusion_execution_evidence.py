import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'vendor/data/datafusion-spice/round10'

class ExecutionEvidenceTest(unittest.TestCase):
    def test_lowerings_have_the_expected_values_not_only_parser_acceptance(self):
        expected = {'WHERE':[['2'],['2']], 'SELECT':[['2'],['3'],['3']],
                    'EXTEND':[['1','2'],['2','3'],['2','3']], 'AS':[['1'],['2'],['2']],
                    'AGGREGATE':[['3']], 'DISTINCT':[['1'],['2']], 'ORDER BY':[['2'],['2'],['1']],
                    'LIMIT':[['1'],['2']], 'OFFSET':[['2'],['2']], 'WINDOW':[['1','1'],['2','2'],['2','3']]}
        for product in ['datafusion','spice']:
            capture = json.loads((DATA / f'{product}-execution.json').read_text())
            self.assertEqual('d3beee43c613c457789825343368f652d570d76c08799dad38a43a10e569b57f',capture['driver']['sha256'])
            for case in capture['observations']['pipes']:
                self.assertEqual('accepted',case['lowered']['status'])
                # Only a final ORDER BY asserts output order; retain duplicate rows in the other comparisons.
                normalize = (lambda rows: rows) if case['stage']=='ORDER BY' else sorted
                self.assertEqual(normalize(expected[case['stage']]),normalize(case['lowered']['value']['rows']),(product,case['stage']))
                if case['native']['status']=='accepted':
                    self.assertEqual(normalize(case['lowered']['value']['rows']),normalize(case['native']['value']['rows']))

    def test_spice_binding_and_protocol_limits_stay_explicit(self):
        obs=json.loads((DATA/'spice-execution.json').read_text())['observations']
        for name,expected in [('jdbc_question','8'),('jdbc_numbered','8'),('jdbc_repeated','14')]:
            self.assertEqual('accepted',obs[name]['status'])
            self.assertEqual([[expected]],obs[name]['value']['result']['rows'])
        self.assertEqual('failed',obs['execute_same']['status'])
        self.assertEqual([["O'Reilly 😀",'9']],obs['jdbc_values']['value']['result']['rows'])
        self.assertEqual([[['8']],[['10']]],[r['rows'] for r in obs['jdbc_rebind']['value']])
        self.assertEqual('failed',obs['jdbc_null']['status'])
        self.assertEqual('failed',obs['jdbc_partial_null']['status'])
        self.assertIn('Memory was leaked',obs['partial_binding_close']['message'])
        self.assertEqual('failed',obs['set']['status'])
        self.assertIn('Statements are not allowed',obs['set']['message'])
        self.assertEqual({'update_count':0},obs['plan_error_generic']['value'])
        self.assertEqual('failed',obs['parse_error']['status'])
        self.assertEqual('accepted',obs['explain']['status'])
        self.assertEqual('accepted',obs['explain_analyze']['status'])
        cancel=obs['cancellation']
        self.assertTrue(cancel['running_before_cancel'])
        self.assertEqual('accepted',cancel['cancel']['status'])
        self.assertEqual('failed',cancel['completion']['value']['status'])
        self.assertEqual([['1']],cancel['connection_after_cancel']['value']['rows'])

    def test_execution_fixture_is_separately_pinned_and_named_prepare_is_session_local(self):
        capture=json.loads((DATA/'datafusion-execution.json').read_text())
        source=ROOT/'tools/datafusion-reference/src/flight_execution.rs'
        self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(),capture['runtime']['execution_source_sha256'])
        obs=capture['observations']
        self.assertEqual('accepted',obs['prepare']['status'])
        self.assertEqual('failed',obs['jdbc_question']['status'])
        self.assertEqual([['8']],obs['jdbc_numbered']['value']['result']['rows'])
        self.assertEqual([[None]],obs['jdbc_null']['value']['rows'])
        self.assertEqual('accepted',obs['partial_binding_close']['status'])
        self.assertEqual([['8']],obs['execute_same']['value']['rows'])
        self.assertEqual('failed',obs['execute_other']['status'])
        self.assertEqual('accepted',obs['deallocate']['status'])
        self.assertEqual('failed',obs['execute_after_deallocate']['status'])
        self.assertEqual('accepted',obs['set']['status'])
        self.assertEqual([['false']],obs['own_setting']['value']['rows'])
        self.assertEqual([['true']],obs['other_setting']['value']['rows'])
        self.assertEqual([['1']],obs['cancellation']['connection_after_cancel']['value']['rows'])
        self.assertTrue((DATA/'datafusion-legacy-embedding-execution.json').exists())

if __name__=='__main__': unittest.main()
