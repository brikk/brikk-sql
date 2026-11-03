#!/usr/bin/env python3
"""Manual CI entry point. Build reviewed references; verify the Spice asset before executing it."""
import hashlib
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request

from datafusion_upstream import load
from datafusion_upstream_capture import pinned_file, validate_profile


def main():
    product = os.environ['PRODUCT']
    if product not in ('datafusion', 'spice'):
        raise ValueError('Unknown product')
    profile_path = Path(os.environ.get('CANDIDATE_PROFILE') or 'build/default-profile.json')
    profile = load(profile_path)
    if profile['product'] != product:
        raise ValueError('The candidate profile belongs to another product')
    validate_profile(profile, product)
    output = Path('build/upstream-candidate')
    output.mkdir(parents=True, exist_ok=False)
    build = profile['reference_build']
    manifest = pinned_file({'path': build['manifest'], 'sha256': build['manifest_sha256']})
    pinned_file(profile['reference_lock'])
    subprocess.run(['cargo', '+1.96.0', 'build', '--locked', '--manifest-path', str(manifest),
                    '--bin', build['binary'], '--target-dir', 'build/upstream-reference', '-j', '2'], check=True)
    corpus = pinned_file(profile['cases'])
    brikk = output / 'brikk.json'
    subprocess.run(['./kotlin', 'run', '-m', 'dialect-audit', '--', product, str(corpus), str(brikk)], check=True)
    reference = Path('build/upstream-reference/debug') / build['binary']
    command = ['python3', 'tools/datafusion_upstream.py', 'refresh', product, '--profile', str(profile_path),
               '--reference', str(reference), '--brikk', str(brikk), '--output', str(output / 'snapshot')]
    if product == 'spice':
        release = profile['runtime']
        archive = output / 'spiced.tar.gz'
        with urllib.request.urlopen(release['asset_url'], timeout=90) as response, archive.open('xb') as stream:
            while block := response.read(1024 * 1024):
                stream.write(block)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != release['asset_sha256']:
            raise ValueError('Spice archive checksum mismatch')
        binary = output / 'spiced'
        with tarfile.open(archive) as tar:
            members = [member for member in tar.getmembers() if Path(member.name).name == 'spiced' and member.isfile()]
            if len(members) != 1:
                raise ValueError('Expected one regular spiced binary in the archive')
            with tar.extractfile(members[0]) as source, binary.open('xb') as target:
                while block := source.read(1024 * 1024):
                    target.write(block)
        binary.chmod(0o700)
        command += ['--spiced', str(binary)]
    try:
        subprocess.run(command, check=True)
    finally:
        if product == 'spice':
            archive.unlink(missing_ok=True)
            binary.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
