"""Validate deployment invariants on rendered Kustomize YAML."""
import argparse
import re
from pathlib import Path
import yaml

parser = argparse.ArgumentParser()
parser.add_argument("path", type=Path)
parser.add_argument("--active", action="store_true")
args = parser.parse_args()
objects = list(yaml.safe_load_all(args.path.read_text(encoding="utf-8")))
seen = set()
for item in objects:
    kind, metadata = item["kind"], item["metadata"]
    identity = (kind, metadata.get("namespace"), metadata["name"])
    if identity in seen:
        raise SystemExit("duplicate manifest identity")
    seen.add(identity)
    if kind == "Namespace":
        assert metadata["name"] == "gold-catcher"
    else:
        assert metadata.get("namespace") == "gold-catcher"
    if kind == "Secret":
        raise SystemExit("credentials must not be committed")
    if kind in {"Deployment", "StatefulSet"}:
        if args.active:
            assert item["spec"]["replicas"] == 1
        else:
            assert item["spec"]["replicas"] == 0
        pod = item["spec"]["template"]["spec"]
        assert pod.get("automountServiceAccountToken") is False
        assert pod["securityContext"]["runAsNonRoot"]
        for container in pod["containers"]:
            assert not container["securityContext"]["allowPrivilegeEscalation"]
            assert container["securityContext"]["capabilities"]["drop"] == ["ALL"]
            assert "readinessProbe" in container
            if args.active and kind == "Deployment":
                if metadata["name"]=="gold-catcher-cache":
                    assert re.fullmatch(r"docker.io/library/redis@sha256:[0-9a-f]{64}",container["image"])
                else:
                    assert re.fullmatch(r"docker.io/speedproxy/gold-catcher-(server|web)@sha256:[0-9a-f]{64}", container["image"])
    if kind == "HTTPRoute":
        assert item["spec"]["hostnames"] == ["gold.mnnu.eu.org"]
        assert not any(match.get("path", {}).get("value") == "/actuator" for rule in item["spec"]["rules"] for match in rule.get("matches", []))
assert {key[2] for key in seen} >= {"gold-catcher-server", "gold-catcher-web", "gold-catcher-postgres"}
cache=[item for item in objects if item["kind"]=="Service" and item["metadata"]["name"]=="gold-catcher-cache"]
assert len(cache)==1 and cache[0]["spec"].get("type","ClusterIP")=="ClusterIP"
print(f"Validated {len(objects)} deployment objects ({'active' if args.active else 'inactive'}).")
