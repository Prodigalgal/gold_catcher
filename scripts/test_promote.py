import json
import tempfile
import unittest
from pathlib import Path
import yaml
from promote import promote


class PromotionContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "gitops"
        self.target = self.root / "gold-catcher/oracle"
        self.target.mkdir(parents=True)
        self.inputs = Path(self.temp.name) / "inputs"
        self.inputs.mkdir()
        self.commit = "a" * 40
        self.file = self.target / "kustomization.yaml"
        self.file.write_text(yaml.safe_dump({"namespace": "gold-catcher", "images": [
            {"name": f"gold-catcher-{c}", "newName": f"docker.io/speedproxy/gold-catcher-{c}", "newTag": "old"}
            for c in ("server", "web")]}), encoding="utf-8")
        for component in ("server", "web"):
            (self.inputs / f"component-{component}.json").write_text(json.dumps({"component": component,
                "image": f"docker.io/speedproxy/gold-catcher-{component}", "sourceCommit": self.commit,
                "digest": "sha256:" + "b" * 64}), encoding="utf-8")

    def test_same_commit_digests_replace_both_tags_and_preserve_unrelated_file(self):
        unrelated = self.root / "other-app.yaml"
        unrelated.write_text("unchanged", encoding="utf-8")
        promote(self.root, self.inputs, self.commit)
        self.assertEqual(unrelated.read_text(), "unchanged")
        for image in yaml.safe_load(self.file.read_text())["images"]:
            self.assertNotIn("newTag", image)
            self.assertEqual(image["digest"], "sha256:" + "b" * 64)

    def test_mixed_commits_or_bad_digest_make_no_changes(self):
        before = self.file.read_bytes()
        path = self.inputs / "component-web.json"
        record = json.loads(path.read_text())
        for field, bad in (("sourceCommit", "c" * 40), ("digest", "latest"), ("image", "docker.io/unrelated/other")):
            broken = dict(record, **{field: bad})
            path.write_text(json.dumps(broken), encoding="utf-8")
            with self.assertRaises(ValueError):
                promote(self.root, self.inputs, self.commit)
            self.assertEqual(before, self.file.read_bytes())
            self.assertFalse((self.target / "release.json").exists())
