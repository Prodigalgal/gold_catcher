"""Bounded CI acceptance; no cluster admin credential or market/LLM side effects."""
import base64
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

commit = os.environ["SOURCE_COMMIT"]
token = os.environ["ARGOCD_READ_TOKEN"]
username, password = os.environ["GOLD_SMOKE_USERNAME"], os.environ["GOLD_SMOKE_PASSWORD"]
if not all((token, username, password)):
    raise SystemExit("missing scoped acceptance credentials")
release = json.loads(Path("gitops/gold-catcher/oracle/release.json").read_text())
images = {f'{r["image"]}@{r["digest"]}' for r in release["components"]}


def read(url, headers):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=20) as response:
        return response.read().decode("utf-8")


deadline = time.monotonic() + 600
while time.monotonic() < deadline:
    try:
        app = json.loads(read("https://argocd.mnnu.eu.org/api/v1/applications/gold-catcher", {"Authorization": "Bearer " + token}))
        status = app.get("status", {})
        if status.get("sync", {}).get("status") == "Synced" and status.get("health", {}).get("status") == "Healthy" and images <= set(status.get("summary", {}).get("images", [])):
            break
    except (urllib.error.URLError, TimeoutError, ValueError):
        pass
    time.sleep(10)
else:
    raise SystemExit("Argo sync/health/digest acceptance timed out")
authorization = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
api = json.loads(read("https://gold.mnnu.eu.org/api/system/status", {"Authorization": authorization}))
if api.get("sourceCommit") != commit or api.get("mode") != "INTERNAL_SIMULATION":
    raise SystemExit("API source commit/mode mismatch; design prototype cannot pass production acceptance")
page = read("https://gold.mnnu.eu.org/", {})
if f'content="{commit}"' not in page:
    raise SystemExit("web source commit mismatch")
print("Argo digests and authenticated API/web release verified.")
