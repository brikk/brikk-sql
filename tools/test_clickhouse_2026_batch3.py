import json
import re
import unittest

from generate_clickhouse_functions import DATA, ROOT, rows


class Clickhouse2026Batch3Test(unittest.TestCase):
    def test_higher_order_binding_contexts_match_the_pinned_registry(self):
        native = {r['name'] for r in rows(DATA / 'clickhouse-functions-26.10.2.103.tsv')
                  if r['higher_order'] == '1'}
        code = (ROOT / 'brikk-sql/src/dev.brikk.house.sql/dialects/ClickhouseHigherOrder.kt').read_text()
        declaration = code.split('internal val clickhouseHigherOrderNames = setOf(', 1)[1].split('\n)', 1)[0]
        self.assertEqual(native, set(re.findall(r'"([^"]+)"', declaration)))

    def test_ddl_and_executing_explain_cases_never_enter_execution_fixtures(self):
        fixture = json.loads((DATA / 'clickhouse-2026-audit/batch3-cases.json').read_text())
        all_cases = fixture['cases'] + fixture['execution']
        self.assertEqual(len(all_cases), len({r['id'] for r in all_cases}))
        ddl = {r['sql'] for r in fixture['cases'] if r['task'] == 'CH26-006'}
        for case in fixture['execution']:
            self.assertTrue(case['sql'].startswith('SELECT '), case['id'])
            self.assertNotIn('setup', case)
            self.assertNotIn(case['sql'], ddl)
        self.assertTrue(any(r['sql'].startswith('CREATE TOKEN') for r in fixture['cases']))
        self.assertTrue(any(r['sql'].startswith('EXPLAIN ANALYZE') for r in fixture['cases']))


if __name__ == '__main__':
    unittest.main()
