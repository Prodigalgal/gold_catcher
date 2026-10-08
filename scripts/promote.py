"""Change only Gold Catcher's existing, activated GitOps image mappings."""
import argparse
import json
import re
from pathlib import Path
import yaml


def promote(root: Path, inputs: Path, commit: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("expected full source commit")
    target = (root / "gold-catcher/oracle").resolve()
    if not target.is_relative_to(root.resolve()):
        raise ValueError("GitOps target escaped repository")
    file = target / "kustomization.yaml"
    data = yaml.safe_load(file.read_text(encoding="utf-8"))
    if data.get("namespace") != "gold-catcher":
        raise ValueError("unexpected deployment namespace")
    records = []
    for component in ("server", "web"):
        record = json.loads((inputs / f"component-{component}.json").read_text(encoding="utf-8"))
        image_name = f"docker.io/speedproxy/gold-catcher-{component}"
        if record.get("sourceCommit") != commit or record.get("component") != component or record.get("image") != image_name:
            raise ValueError("component provenance mismatch")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", record.get("digest", "")):
            raise ValueError("expected immutable digest")
        entries = [item for item in data.get("images", []) if item.get("name") == f"gold-catcher-{component}"]
        if len(entries) != 1 or entries[0].get("newName") != image_name:
            raise ValueError("expected exactly one correct image mapping")
        entries[0].pop("newTag", None)
        entries[0]["digest"] = record["digest"]
        records.append(record)
    # Validate every record before changing either file.
    release = {"sourceCommit": commit, "components": records}
    file.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    (target / "release.json").write_text(json.dumps(release, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--gitops", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    promote(args.gitops, args.inputs, args.commit)
