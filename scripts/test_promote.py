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
        base = self.root / "gold-catcher/base"
        base.mkdir()
        (base / "configmap.yaml").write_text(yaml.safe_dump({"kind":"ConfigMap", "metadata":{"name":"gold-catcher-config"},
            "data":{"GOLD_RESEARCH_AUTOMATIC":"false", "KEEP":"preserved"}}), encoding="utf-8")
        (base / "deployments.yaml").write_text(yaml.safe_dump_all([{"kind":"Deployment", "metadata":{"name":"gold-catcher-server"},
            "spec":{"replicas":1, "template":{"spec":{"nodeSelector":{"custom":"keep"}, "containers":[{"name":"server", "image":"old", "resources":{"requests":{"memory":"256Mi"}}}]}}}},
            {"kind":"Deployment", "metadata":{"name":"gold-catcher-web"}, "spec":{"replicas":1, "sentinel":"unchanged"}}]), encoding="utf-8")
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

    def test_runtime_defaults_are_promoted_without_deactivating_workloads_or_changing_placement(self):
        promote(self.root, self.inputs, self.commit)
        base = self.root / "gold-catcher/base"
        config = yaml.safe_load((base / "configmap.yaml").read_text())["data"]
        self.assertEqual(config["GOLD_COMPRESSION_ENABLED"], "true")
        self.assertEqual(config["GOLD_MARKET_ARCHIVE_RETENTION_DAYS"], "3")
        self.assertEqual(config["GOLD_RESEARCH_AUTOMATIC"], "false")
        self.assertEqual(config["KEEP"], "preserved")
        server, web = yaml.safe_load_all((base / "deployments.yaml").read_text())
        self.assertEqual(server["spec"]["replicas"], 1)
        self.assertEqual(server["spec"]["template"]["spec"]["nodeSelector"], {"custom":"keep"})
        self.assertEqual(server["spec"]["template"]["spec"]["containers"][0]["resources"]["limits"]["memory"], "1Gi")
        self.assertEqual(web["spec"], {"replicas":1, "sentinel":"unchanged"})

    def test_unexpected_runtime_target_leaves_images_and_release_unchanged(self):
        before = self.file.read_bytes()
        (self.root / "gold-catcher/base/configmap.yaml").write_text("kind: Secret\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            promote(self.root, self.inputs, self.commit)
        self.assertEqual(before, self.file.read_bytes())
        self.assertFalse((self.target / "release.json").exists())
