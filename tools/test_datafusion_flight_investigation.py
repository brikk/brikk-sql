"""Recorded root-cause controls, not evidence that the defective paths are supported."""
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'vendor/data/datafusion-spice/flight-investigation'


class FlightInvestigationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.capture = json.loads((DATA / 'observations.json').read_text())
        cls.obs = cls.capture['observations']

    def value(self, result):
        self.assertEqual('returned', result['status'], result.get('message'))
        return result['value']

    def test_pins_and_actual_probe_sources_are_recorded(self):
        self.assertEqual('v2.4.0+models', self.capture['runtime']['version'])
        self.assertEqual('257b9fd8cf1f46c1f4ddf0b6280ba5b6033b79c276ec6840dd5e5d81710b1002', self.capture['runtime']['binary_sha256'])
        self.assertEqual('d3beee43c613c457789825343368f652d570d76c08799dad38a43a10e569b57f', self.capture['driver']['sha256'])
        for name, sha in self.capture['probe_sources'].items():
            self.assertEqual(sha, hashlib.sha256((ROOT / name).read_bytes()).hexdigest())
        evidence = json.loads((DATA / 'source-evidence.json').read_text())
        for source in evidence['sources']:
            self.assertIn(source['revision'], source['url'])
            self.assertEqual(64, len(source['sha256']))
            for excerpt in source['excerpts']:
                self.assertEqual(excerpt['end'] - excerpt['start'] + 1, len(excerpt['text'].splitlines()))

    def test_missing_columns_are_swallowed_but_other_errors_propagate(self):
        rows = {r['sql']: r for r in self.obs['jdbc_matrix']}
        self.assertEqual([['1']], self.value(rows['SELECT 1 AS n']['execute'])['result']['rows'])
        for sql in ['SELECT missing_column FROM sample', 'SELECT no_such_column']:
            self.assertEqual({'update_count': 0}, self.value(rows[sql]['execute']))
            self.assertEqual({'update_count': 0}, self.value(rows[sql]['update']))
            self.assertEqual('Statement did not return a result set', rows[sql]['query']['message'])
        for sql in ['SELECT * FROM no_such_table', 'SELECT no_such_function(1)', 'SELECT FROM', "SELECT CAST('not-an-integer' AS BIGINT)"]:
            self.assertEqual('threw', rows[sql]['execute']['status'], sql)
        self.assertIn('No field named missing_column', self.obs['raw_invalid_direct']['message'])
        prepared = self.value(self.obs['raw_invalid_prepared'])
        self.assertEqual([], prepared['dataset_fields'])
        self.assertEqual([], prepared['parameter_fields'])
        self.assertIn('No field named missing_column', prepared['force_query_path']['message'])

    def test_update_reply_has_a_handle_instead_of_a_record_count(self):
        for name in ['raw_wrong_update_reply', 'raw_prepare_update_reply']:
            reply = self.value(self.obs[name])
            wire = bytes.fromhex(reply['metadata_hex'])
            self.assertEqual(0x0A, wire[0])  # field 1, length-delimited; record_count expects a varint.
            self.assertEqual('INT64', reply['record_count_field_type'])
            self.assertEqual('BYTES', reply['binding_handle_field_type'])
            self.assertGreater(reply['returned_binding_handle_bytes'], 0)
            self.assertEqual(0, reply['decoded_update_count'])
            self.assertTrue(reply['update_unknown_fields'].startswith('1:'))
        self.assertIn("Prepared statement 'no_effect' does not exist", self.obs['raw_execute_after_update_prepare']['message'])

    def test_named_execution_works_when_actually_run_but_its_schema_is_empty(self):
        self.value(self.obs['raw_named_prepare'])
        execute = self.value(self.obs['raw_named_execute'])
        self.assertEqual([], execute['advertised_fields'])
        self.assertEqual([['8']], execute['rows'])
        self.assertEqual(1, len(execute['fields']))
        for name in ['raw_named_other_session', 'raw_named_after_deallocate']:
            self.assertIn('does not exist', self.obs[name]['message'])
        bridge = self.value(self.obs['jdbc_shared_session_control'])
        self.assertEqual({'update_count': 0}, self.value(bridge['jdbc_execute_prepared_elsewhere']))
        prepared = self.value(bridge['prepared_execute_query_control'])
        self.assertEqual([], prepared['dataset_fields'])
        self.assertEqual([['8']], self.value(prepared['force_query_path'])['rows'])
        self.assertEqual([['8']], self.value(bridge['jdbc_force_flight_query_control'])['rows'])
        self.assertEqual({'update_count': 0}, self.value(bridge['jdbc_deallocate']))
        self.assertEqual([['8']], self.value(bridge['raw_still_exists'])['rows'])
        self.value(bridge['raw_deallocate'])
        self.assertIn('does not exist', bridge['raw_now_missing']['message'])

    def test_null_rejection_is_local_at_execution_and_the_engine_accepts_null(self):
        for name in ['jdbc_null_only', 'jdbc_object_null', 'jdbc_null_first']:
            case = self.obs[name]
            for r in case['rounds']:
                self.value(r['setters'])
                self.assertEqual('threw', r['execute']['status'])
                self.assertIn('non-nullable', r['execute']['message'])
                self.assertTrue(any('AvaticaParameterBinder' in frame for frame in r['execute']['stack']))
                self.assertEqual(0, r['after_statement_close_bytes'])
                self.assertTrue(all(p['nullable'] == 2 for p in r['metadata']))  # JDBC reports unknown.
            self.value(case['connection_close'])
        for row in self.value(self.obs['raw_parameter_schemas']):
            self.assertTrue(all(not p['nullable'] for p in row['parameters']))
        for name, expected in [('raw_nullable_override', [[None]]), ('raw_nullable_coalesce', [['42']])]:
            case = self.value(self.obs[name])
            self.assertFalse(case['advertised_parameters'][0]['nullable'])
            self.assertTrue(case['sent_parameters'][0]['nullable'])
            self.assertEqual(expected, self.value(case['execute'])['rows'])

    def test_failed_binding_orphans_allocations_independently_of_spice(self):
        case = self.obs['jdbc_partial_null_repeated']
        retained = [r['after_statement_close_bytes'] for r in case['rounds']]
        self.assertGreater(retained[0], 0)
        self.assertEqual([retained[0] * i for i in [1, 2, 3]], retained)
        for r in case['rounds']:
            self.value(r['setters'])
            self.value(r['clear_parameters'])
            self.value(r['statement_close'])
        self.assertIn('Memory was leaked', case['connection_close']['message'])
        wrong = self.obs['jdbc_wrong_type']
        self.assertGreater(wrong['rounds'][0]['after_statement_close_bytes'], 0)
        self.assertIn('expected Arrow type', wrong['rounds'][0]['execute']['message'])
        self.assertIn('Memory was leaked', wrong['connection_close']['message'])
        for r in self.obs['jdbc_valid_control']['rounds']:
            self.assertEqual([['x', '9']], self.value(r['execute'])['rows'])
            self.assertEqual(0, r['after_statement_close_bytes'])
        local = self.obs['server_free_binder']
        self.assertEqual('threw', local['bind']['status'])
        self.assertFalse(local['ownership_transferred'])
        self.assertGreater(local['after_failure_bytes'], 0)
        self.assertEqual(local['after_failure_bytes'], local['after_owner_close_bytes'])
        self.assertEqual(0, local['after_manual_probe_cleanup_bytes'])


if __name__ == '__main__':
    unittest.main()
