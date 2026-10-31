#!/usr/bin/env python3
"""DFS-006 bounded analysis controls; no search/model calls or user connections."""
import json
from pathlib import Path
from probe_datafusion_spice import ROOT, run_reference, spice_runtime, spice_execute

OUT = ROOT / 'vendor/data/datafusion-spice/round5'


def cases():
    result = []
    def add(id, sql, spice_only=False):
        result.append(dict(id=id, sql=sql, spice_only=spice_only))
    for name in ['Int8', 'Int16', 'Int32', 'Int64', 'UInt8', 'UInt16', 'UInt32', 'UInt64',
                 'Float32', 'Float64', 'Utf8', 'LargeUtf8', 'Utf8View', 'Binary', 'LargeBinary',
                 'Decimal128(18, 3)', 'Decimal256(40, 6)', 'Timestamp(Nanosecond, None)',
                 'Timestamp(Microsecond, Some("UTC"))', 'Date32', 'Date64', 'Time64(Nanosecond)',
                 'Duration(Millisecond)', 'Interval(MonthDayNano)', 'List(Int64)',
                 'LargeList(Utf8)', 'FixedSizeList(3, Float32)', 'Struct(a Int64, b Utf8)',
                 'Map(Utf8, Int64)', 'Union(Int64, Utf8)']:
        add('arrow-' + str(len(result)), f"SELECT arrow_cast(NULL, '{name}') AS v")
    for id, value in [('integer', '1'), ('float','1.5'), ('sum','sum(CAST(1 AS INT))'),
                      ('avg','avg(CAST(1 AS INT))'), ('count','count(*)'), ('sqrt','sqrt(4)'),
                      ('decimal-add','CAST(1 AS DECIMAL(10,2)) + CAST(2 AS DECIMAL(12,3))'),
                      ('decimal-div','CAST(1 AS DECIMAL(10,2)) / CAST(2 AS DECIMAL(12,3))'),
                      ('int-div','3 / 2'), ('array','[1,2]'), ('struct',"named_struct('a', 1, 'b', 'x')"),
                      ('map',"map('a', 1)"), ('null','NULL'), ('timestamp',"TIMESTAMP '2024-01-02 03:04:05'"),
                      ('interval',"INTERVAL '2 days'")]:
        add(id, f'SELECT {value} AS v')
    add('using-order', 'SELECT * FROM (SELECT 1 AS a, 2 AS id) l JOIN (SELECT 2 AS id, 3 AS b) r USING(id)')
    add('qualified-using', 'SELECT l.*, r.* FROM (SELECT 1 AS a, 2 AS id) l JOIN (SELECT 2 AS id, 3 AS b) r USING(id)')
    add('where-alias','SELECT a + 1 AS v FROM (SELECT 1 AS a) t WHERE v > 1')
    add('lateral-alias','SELECT a + 1 AS v, v + 1 AS w FROM (SELECT 1 AS a) t')
    add('unnest','SELECT * FROM unnest([1,2]) AS u(v)')
    add('json-get', "SELECT json_get('{\"a\":1}', 'a') AS v", True)
    add('json-int', "SELECT json_get_int('{\"a\":1}', 'a') AS v", True)
    add('json-text', "SELECT '{\"a\":1}' ->> 'a' AS v", True)
    add('flatten', "SELECT * FROM flatten_json('{\"a\":1}')", True)
    add('tree', "SELECT * FROM json_tree('{\"a\":1}') ORDER BY id", True)
    for id, expression in [('sqrt-f32', 'sqrt(CAST(4 AS REAL))'), ('power-f32', 'power(CAST(2 AS REAL), CAST(3 AS REAL))'),
                           ('avg-f32', 'avg(CAST(2 AS REAL))'), ('sum-f32', 'sum(CAST(2 AS REAL))'),
                           ('floor-int', 'floor(CAST(2 AS INT))'), ('floor-f32', 'floor(CAST(2 AS REAL))'),
                           ('length', "length('a')"), ('length-large', "length(arrow_cast('a', 'LargeUtf8'))"),
                           ('length-view', "length(arrow_cast('a', 'Utf8View'))")]:
        add(id, f'SELECT {expression} AS v')
    return result


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    inputs = cases()
    (OUT / 'cases.json').write_text(json.dumps(inputs, indent=2) + '\n')
    requests = [dict(id=c['id'], operation='execute', sql=c['sql']) for c in inputs if not c['spice_only']]
    upstream = run_reference(ROOT / 'build/datafusion-reference/debug/brikk-datafusion-reference', requests, 'datafusion-55.2.0')
    (OUT / 'upstream.json').write_text(json.dumps(upstream, indent=2) + '\n')
    with spice_runtime(Path('/tmp/opencode/spice-2.4.0-runtime/spiced')) as (url, profile):
        rows = {c['id']: spice_execute(url, c['sql']) for c in inputs}
    (OUT / 'spice.json').write_text(json.dumps(dict(profile=profile, cases=rows), indent=2) + '\n')
    for name, rows in [('upstream', upstream), ('spice', rows)]:
        print(name)
        for id, r in rows.items():
            print(id, r.get('fields', r.get('type_observation', {}).get('rows', r.get('message'))))
