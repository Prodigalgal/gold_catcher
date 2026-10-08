"""Write CI image provenance without emitting credentials."""
import json
import os
import re
from pathlib import Path

component = os.environ["COMPONENT"]
digest = os.environ["DIGEST"]
if component not in {"server", "web"} or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
    raise SystemExit("invalid component or digest")
Path(f"component-{component}.json").write_text(json.dumps({
    "component": component, "digest": digest, "sourceCommit": os.environ["GITHUB_SHA"],
    "image": f"docker.io/speedproxy/gold-catcher-{component}",
    "run": os.environ["GITHUB_RUN_ID"],
}, indent=2) + "\n", encoding="utf-8")
