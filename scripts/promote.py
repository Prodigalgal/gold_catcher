"""Promote immutable Gold Catcher images and scoped runtime defaults together."""
import argparse
import json
import re
from pathlib import Path
import yaml


def runtime_changes(root: Path) -> list[tuple[Path, str]]:
    """Preserve replicas, placement, credentials and every unrelated application's settings."""
    source = Path(__file__).resolve().parents[1] / "deploy/gitops/base"
    target = (root / "gold-catcher/base").resolve()
    paths = [target / name for name in ("configmap.yaml", "deployments.yaml")]
    if any(not path.resolve().is_relative_to(root.resolve()) for path in paths):
        raise ValueError("runtime target escaped repository")
    config = yaml.safe_load(paths[0].read_text(encoding="utf-8"))
    template = yaml.safe_load((source / "configmap.yaml").read_text(encoding="utf-8"))
    if config.get("kind") != "ConfigMap" or config.get("metadata", {}).get("name") != "gold-catcher-config":
        raise ValueError("unexpected runtime ConfigMap")
    for key in ("GOLD_DRIVERS_ENABLED", "GOLD_COMPRESSION_ENABLED", "GOLD_COMPRESSION_CHUNKS_PER_PASS", "GOLD_MARKET_ARCHIVE_RETENTION_DAYS", "GOLD_CACHE_REDIS_ENABLED", "GOLD_CACHE_REDIS_HOST", "GOLD_CACHE_LOCAL_MIB", "GOLD_CACHE_VALUE_MIB"):
        config["data"][key] = template["data"][key]
    disabled = {value.strip().lower() for value in (config["data"].get("GOLD_AI_DISABLED_PROVIDERS", "") + "," + template["data"]["GOLD_AI_DISABLED_PROVIDERS"]).split(",") if value.strip()}
    config["data"]["GOLD_AI_DISABLED_PROVIDERS"] = ",".join(sorted(disabled))
    deployments = list(yaml.safe_load_all(paths[1].read_text(encoding="utf-8")))
    templates = list(yaml.safe_load_all((source / "deployments.yaml").read_text(encoding="utf-8")))
    servers = [item for item in deployments if item.get("kind") == "Deployment" and item.get("metadata", {}).get("name") == "gold-catcher-server"]
    if len(servers) != 1:
        raise ValueError("expected exactly one Gold Catcher server Deployment")
    containers = [item for item in servers[0]["spec"]["template"]["spec"]["containers"] if item.get("name") == "server"]
    if len(containers) != 1:
        raise ValueError("expected exactly one Gold Catcher server container")
    server_template = next(item for item in templates if item["metadata"]["name"] == "gold-catcher-server")
    containers[0]["resources"] = server_template["spec"]["template"]["spec"]["containers"][0]["resources"]
    env_from=containers[0].setdefault("envFrom",[])
    if not any(item.get("secretRef",{}).get("name")=="gold-catcher-cache" for item in env_from):
        env_from.append({"secretRef":{"name":"gold-catcher-cache","optional":True}})
    cache=list(yaml.safe_load_all((source/"cache.yaml").read_text(encoding="utf-8")))
    for item in cache:
        if item["kind"]=="Deployment":item["spec"]["replicas"]=servers[0]["spec"].get("replicas",0)
    kustomization_path=target/"kustomization.yaml"
    kustomization=yaml.safe_load(kustomization_path.read_text(encoding="utf-8"))
    if "cache.yaml" not in kustomization["resources"]:kustomization["resources"].append("cache.yaml")
    return [(paths[0], yaml.safe_dump(config, sort_keys=False)), (paths[1], yaml.safe_dump_all(deployments, sort_keys=False)),
        (target/"cache.yaml",yaml.safe_dump_all(cache,sort_keys=False)),(kustomization_path,yaml.safe_dump(kustomization,sort_keys=False))]


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
    # Validate images and every scoped runtime mutation before writing any file.
    updates = runtime_changes(root)
    release = {"sourceCommit": commit, "components": records}
    file.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    (target / "release.json").write_text(json.dumps(release, indent=2) + "\n", encoding="utf-8")
    for path, text in updates:
        path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--gitops", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    promote(args.gitops, args.inputs, args.commit)
