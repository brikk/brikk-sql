import argparse
import contextlib
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import datafusion_upstream_capture as capture
import probe_datafusion_spice as probe


class RuntimeDirectoryTest(unittest.TestCase):
    def test_runtime_owns_its_directory_and_always_stops_the_process(self):
        profile = capture.default_profile('spice')
        (probe.ROOT / 'build').mkdir(exist_ok=True)
        temp = tempfile.TemporaryDirectory(dir=probe.ROOT / 'build')
        self.addCleanup(temp.cleanup)
        custom = Path(temp.name) / 'nested/work'
        for module, runtime, version, parent in [
            (probe, lambda: probe.spice_runtime(Path('spiced')), 'v2.4.0+models', probe.ROOT / 'build/spice-reference'),
            (capture, lambda: capture.runtime(Path('spiced'), profile), profile['runtime']['reported_version'], probe.ROOT / 'build/datafusion-upstream'),
            (probe, lambda: probe.spice_runtime(Path('spiced'), work_dir=custom), 'v2.4.0+models', custom),
            (capture, lambda: capture.runtime(Path('spiced'), profile, work_dir=custom), profile['runtime']['reported_version'], custom),
        ]:
            with self.subTest(module=module.__name__), contextlib.ExitStack() as stack:
                hash_name = 'sha256' if module is probe else 'file_hash'
                file_hash = getattr(module, hash_name)
                stack.enter_context(patch.object(module, hash_name, side_effect=lambda path:
                    profile['runtime']['binary_sha256'] if path == Path('spiced') else file_hash(path)))
                stack.enter_context(patch.object(module.subprocess, 'check_output', return_value=version))
                process = MagicMock()
                process.poll.return_value = None
                launch = stack.enter_context(patch.object(module.subprocess, 'Popen', return_value=process))
                ready = stack.enter_context(patch.object(module.urllib.request, 'urlopen'))
                ready.return_value.__enter__.return_value.status = 200
                with self.assertRaisesRegex(RuntimeError, 'fixture failure'):
                    with runtime():
                        directory = Path(launch.call_args.kwargs['cwd'])
                        self.assertEqual(parent.resolve(), directory.parent)
                        self.assertTrue((directory / 'spicepod.yaml').is_file())
                        raise RuntimeError('fixture failure')
                process.terminate.assert_called_once()
                process.wait.assert_called_once()
                self.assertFalse(directory.exists())


class ProbeArgumentsTest(unittest.TestCase):
    def parser(self, script):
        class Parsed(Exception):
            pass

        def stop(parser, *args, **kwargs):
            self.arguments = parser
            raise Parsed()

        with patch.object(argparse.ArgumentParser, 'parse_args', stop):
            with self.assertRaises(Parsed):
                runpy.run_path(str(probe.ROOT / 'tools' / script), run_name='__main__')
        return self.arguments

    def test_work_directories_default_to_build_and_accept_an_override(self):
        for script, required in [
            ('probe_datafusion_spice.py', ['--output', 'capture.json']),
            ('probe_datafusion_analysis.py', []),
            ('probe_flight_catalog.py', []),
            ('probe_flight_execution.py', ['--classpath-file', 'classpath.txt']),
            ('datafusion_upstream.py', ['refresh', 'spice', '--reference', 'reference', '--brikk', 'brikk.json', '--output', 'snapshot']),
        ]:
            with self.subTest(script=script):
                parser = self.parser(script)
                defaults = parser.parse_args(required)
                self.assertTrue(defaults.work_dir.is_relative_to(probe.ROOT / 'build'))
                args = parser.parse_args([*required, '--work-dir', 'custom/work'])
                self.assertEqual(Path('custom/work'), args.work_dir)
                if getattr(defaults, 'spiced', None) is not None:
                    self.assertTrue(defaults.spiced.is_relative_to(probe.ROOT / 'build'))
