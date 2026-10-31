#!/usr/bin/env python3
"""Pinned offline-editor diagnostic controls; only bounded scalar queries execute."""
import json
from probe_datafusion_spice import ROOT, run_reference

if __name__ == '__main__':
    sql = ["SELECT sqrt(1,2)", "SELECT sqrt([1,2])", "SELECT sqrt(NULL)",
           "SELECT sqrt(CAST(NULL AS STRUCT<a INT>))", "SELECT abs(1,2)",
           "SELECT sqrt('4')", "SELECT sqrt(4)"]
    requests = [dict(id=f'editor-{i}', sql=text, operation='execute') for i, text in enumerate(sql)]
    rows = run_reference(ROOT / 'build/datafusion-reference/debug/brikk-datafusion-reference', requests, 'datafusion-55.2.0')
    out = ROOT / 'vendor/data/datafusion-spice/round8'
    out.mkdir(parents=True, exist_ok=True)
    (out / 'diagnostics-native.json').write_text(json.dumps(dict(requests=requests, responses=rows), indent=2) + '\n')
    for request in requests:
        row = rows[request['id']]
        print(request['sql'], row['status'], row.get('message', ''))
