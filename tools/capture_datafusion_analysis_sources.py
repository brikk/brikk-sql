#!/usr/bin/env python3
"""Capture the pinned Spice implementation excerpts used by DFS-006 analysis rules."""
import hashlib
import json
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from probe_datafusion_spice import ROOT

RANGES = {
    'crates/runtime/src/embeddings/udtf.rs': [(463, 498), (748, 765), (1134, 1238)],
    'crates/runtime-search/src/rrf.rs': [(78, 78), (795, 829)],
    'crates/runtime-search/src/rerank.rs': [(150, 156), (588, 598)],
    'crates/runtime-datafusion-udfs/src/flatten_json.rs': [(143, 163)],
    'crates/runtime-datafusion-udfs/src/json_tree.rs': [(132, 165)],
    'crates/runtime-decide/src/output.rs': [(17, 104)],
    'crates/runtime-decide/src/functions.rs': [(106, 120), (175, 195)],
    'crates/runtime-decide/src/args.rs': [(52, 52), (423, 467)],
}


if __name__ == '__main__':
    pin = json.loads((ROOT / 'vendor/data/spice/2.4.0/dependency-provenance.json').read_text())['source_commit']
    def capture(path):
        url = f'https://raw.githubusercontent.com/spiceai/spiceai/{pin}/{path}'
        raw = urllib.request.urlopen(url, timeout=30).read()
        lines = raw.decode().splitlines()
        return dict(path=path, url=url, sha256=hashlib.sha256(raw).hexdigest(), excerpts=[
            dict(start=start, end=end, text='\n'.join(lines[start - 1:end])) for start, end in RANGES[path]])
    with ThreadPoolExecutor(max_workers=4) as pool:
        sources = list(pool.map(capture, RANGES))
    out = ROOT / 'vendor/data/datafusion-spice/round5/source-evidence.json'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(dict(source_commit=pin, evidence='source inspection; no model/search execution', sources=sources), indent=2) + '\n')
    print(out)
