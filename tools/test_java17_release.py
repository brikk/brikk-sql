import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_java17_publication import verify


class Java17PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repository = Path(self.temp.name)
        self.artifact = 'brikk-sql-jvm'
        self.version = '0.20.0-SNAPSHOT'
        self.folder = self.repository / 'dev/brikk/house' / self.artifact / self.version
        self.folder.mkdir(parents=True)
        self.stem = self.folder / f'{self.artifact}-{self.version}'
        self.write_jar(61)
        self.write_metadata(17)
        Path(str(self.stem) + '.pom').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies/></project>')

    def write_jar(self, major):
        with zipfile.ZipFile(str(self.stem) + '.jar', 'w') as jar:
            jar.writestr('dev/brikk/house/sql/Example.class', b'\xca\xfe\xba\xbe\x00\x00' + major.to_bytes(2, 'big'))

    def write_metadata(self, jvm, dependencies=()):
        Path(str(self.stem) + '.module').write_text(json.dumps({'variants': [{
            'attributes': {'org.gradle.jvm.version': jvm}, 'dependencies': list(dependencies)}]}))

    def check_publication(self):
        with contextlib.redirect_stdout(io.StringIO()):
            verify(self.repository, self.version, self.artifact)

    def test_accepts_java17(self):
        self.check_publication()

    def test_rejects_java21_bytecode(self):
        self.write_jar(65)
        with self.assertRaisesRegex(AssertionError, 'newer than Java 17'):
            self.check_publication()

    def test_rejects_java21_variant(self):
        self.write_metadata(21)
        with self.assertRaisesRegex(AssertionError, 'variant requires Java 21'):
            self.check_publication()

    def test_rejects_high_runtime_brikk_dependency(self):
        self.write_metadata(17, [{'group': 'dev.brikk.house', 'module': 'brikk-sql-verify-trino'}])
        with self.assertRaisesRegex(AssertionError, 'non-Java-17 dependency'):
            self.check_publication()

    def test_rejects_stale_module_dependency(self):
        self.write_metadata(17, [{'group': 'dev.brikk.house', 'module': 'brikk-sql-metadata', 'version': {'requires': '0.20.1'}}])
        with self.assertRaisesRegex(AssertionError, 'stale module dependency'):
            self.check_publication()


if __name__ == '__main__':
    unittest.main()
