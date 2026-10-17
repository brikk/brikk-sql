#!/usr/bin/env python3
import json
from pathlib import Path
import tempfile
import unittest

from verify_release_candidates import problems

POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>dev.brikk.house</groupId>
<artifactId>{artifact}</artifactId><version>{version}</version><dependencies>
<dependency><groupId>dev.brikk.house</groupId><artifactId>brikk-sql-jvm</artifactId><version>{core}</version></dependency>
<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>2.4.10</version></dependency>
</dependencies></project>'''


class ReleaseCandidatesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='brikk-candidates-')
        self.addCleanup(self.temp.cleanup)
        self.repository = Path(self.temp.name)

    def install(self, artifact, version, core, module_core=None):
        folder = self.repository / 'dev/brikk/house' / artifact / version
        folder.mkdir(parents=True)
        (folder / f'{artifact}-{version}.pom').write_text(POM.format(artifact=artifact, version=version, core=core))
        variants = [{'dependencies': [{'group': 'dev.brikk.house', 'module': 'brikk-sql-jvm', 'version': {'requires': module_core or core}}]}]
        (folder / f'{artifact}-{version}.module').write_text(json.dumps({'variants': variants}))

    def test_a_release_on_released_dependencies_passes(self):
        self.install('brikk-sql-intellij-support', '0.2.0', '0.20.0')
        self.assertEqual([], problems(self.repository, 'brikk-sql-intellij-support', '0.2.0', {'brikk-sql-jvm': '0.20.0'}))

    def test_a_snapshot_dependency_fails_in_the_pom_and_the_module_metadata(self):
        self.install('brikk-sql-intellij-support', '0.2.0', '0.20.0-SNAPSHOT')
        found = problems(self.repository, 'brikk-sql-intellij-support', '0.2.0', {'brikk-sql-jvm': '0.20.0'})
        self.assertTrue(any('pom names a SNAPSHOT' in p for p in found), found)
        self.assertTrue(any('.module takes brikk-sql-jvm 0.20.0-SNAPSHOT' in p for p in found), found)

    def test_a_dependency_at_another_release_fails(self):
        self.install('brikk-sql-intellij-support', '0.2.0', '0.19.1', module_core='0.20.0')
        found = problems(self.repository, 'brikk-sql-intellij-support', '0.2.0', {'brikk-sql-jvm': '0.20.0'})
        self.assertEqual(['brikk-sql-intellij-support-0.2.0.pom takes brikk-sql-jvm 0.19.1, not 0.20.0'], found)

    def test_another_released_brikk_artifact_keeps_its_own_version(self):
        self.install('brikk-sql-oracle', '0.20.0', '0.16.0')
        self.assertEqual([], problems(self.repository, 'brikk-sql-oracle', '0.20.0', {'brikk-sql-oracle': '0.20.0'}))

    def test_a_missing_candidate_fails(self):
        self.assertTrue(problems(self.repository, 'brikk-sql-oracle', '0.20.0', {})[0].startswith('brikk-sql-oracle: no '))


if __name__ == '__main__':
    unittest.main()
