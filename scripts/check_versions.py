"""Require one source version for the Java build, web package and lockfile."""
import json
import re
from pathlib import Path


def check_versions(root):
    backend = (root / 'backend/build.gradle.kts').read_text(encoding='utf-8')
    match = re.search(r'^version\s*=\s*"(\d+\.\d+\.\d+)"\s*$', backend, re.MULTILINE)
    if not match or not re.search(r'springBoot\s*\{\s*buildInfo\(\)\s*}', backend):
        raise ValueError('Java version or generated build info missing')
    package = json.loads((root / 'web/package.json').read_text(encoding='utf-8'))
    lock = json.loads((root / 'web/package-lock.json').read_text(encoding='utf-8'))
    versions = {match.group(1), package['version'], lock['version'], lock['packages']['']['version']}
    if len(versions) != 1:
        raise ValueError('Java, web and lockfile versions differ')
    return match.group(1)


if __name__ == '__main__':
    print('Version contract: ' + check_versions(Path(__file__).resolve().parent.parent))
