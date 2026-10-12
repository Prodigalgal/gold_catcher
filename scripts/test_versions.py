import json
import tempfile
import unittest
from pathlib import Path

from check_versions import check_versions


class VersionContractTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'backend').mkdir()
        (self.root / 'web').mkdir()
        (self.root / 'backend/build.gradle.kts').write_text('version = "0.5.1"\nspringBoot { buildInfo() }\n')
        (self.root / 'web/package.json').write_text(json.dumps({'version': '0.5.1'}))
        self.write_lock('0.5.1', '0.5.1')

    def write_lock(self, version, root_version):
        (self.root / 'web/package-lock.json').write_text(json.dumps({'version': version, 'packages': {'': {'version': root_version}}}))

    def test_consistent_version_is_accepted(self):
        self.assertEqual('0.5.1', check_versions(self.root))

    def test_stale_lock_root_is_rejected(self):
        self.write_lock('0.5.1', '0.5.0')
        with self.assertRaisesRegex(ValueError, 'versions differ'):
            check_versions(self.root)

    def test_stale_web_is_rejected(self):
        (self.root / 'web/package.json').write_text(json.dumps({'version': '0.5.0'}))
        with self.assertRaisesRegex(ValueError, 'versions differ'):
            check_versions(self.root)

    def test_missing_runtime_build_info_is_rejected(self):
        (self.root / 'backend/build.gradle.kts').write_text('version = "0.5.1"\n')
        with self.assertRaisesRegex(ValueError, 'build info missing'):
            check_versions(self.root)
