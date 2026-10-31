#!/usr/bin/env python3
"""Pinned source evidence for editor argument names; these are not exhaustive overloads."""
import hashlib
import json
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from probe_datafusion_spice import ROOT

RANGES = {
    'crates/runtime/src/embeddings/udtf.rs': [(118, 136)],
    'crates/runtime-search/src/full_text_udtf.rs': [(72, 87)],
    'crates/runtime-search/src/rrf.rs': [(112, 146)],
    'crates/runtime-search/src/rerank.rs': [(87, 105)],
    'crates/runtime-decide/src/functions.rs': [(60, 116)],
    'crates/runtime-decide/src/output.rs': [(17, 104)],
}

if __name__ == '__main__':
    pin = json.loads((ROOT / 'vendor/data/spice/2.4.0/dependency-provenance.json').read_text())['source_commit']
    def capture(path):
        url = f'https://raw.githubusercontent.com/spiceai/spiceai/{pin}/{path}'
        raw = urllib.request.urlopen(url, timeout=30).read()
        lines = raw.decode().splitlines()
        return dict(path=path, url=url, sha256=hashlib.sha256(raw).hexdigest(), excerpts=[
            dict(start=a, end=b, text='\n'.join(lines[a-1:b])) for a, b in RANGES[path]])
    with ThreadPoolExecutor(max_workers=4) as pool:
        sources = list(pool.map(capture, RANGES))
    out = ROOT / 'vendor/data/datafusion-spice/round8/editor-source-evidence.json'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(dict(source_commit=pin, evidence='source inspection, not model/search execution', sources=sources), indent=2) + '\n')
    print(out)
