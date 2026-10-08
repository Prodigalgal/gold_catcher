"""Bounded CI acceptance; no cluster admin credential or market/LLM side effects."""
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path
from auth_client import SessionClient

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
client = SessionClient("https://gold.mnnu.eu.org")
client.login(username, password)
api = client.request('/api/system/status')
if api.get("sourceCommit") != commit or api.get("mode") != "INTERNAL_SIMULATION_ALPHA" or api.get('venue') != 'GATE' or api.get('authentication') != 'SESSION_CSRF_SHA256_POW':
    raise SystemExit("API source commit/mode/venue/authentication mismatch")
if set(api.get('products', [])) != {'XAU_USDT', 'GATE:XAUUSD'}:
    raise SystemExit('unexpected active products')
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    perpetual = client.request('/api/dashboard?symbol=XAU_USDT')
    cfd = client.request('/api/dashboard?symbol=GATE:XAUUSD')
    if (perpetual['market']['state'] == 'LIVE' and len(perpetual['market']['indicators']) == 6
            and perpetual['market']['quote']['source'] == 'GATE_WS_BOOK_TICKER'
            and perpetual['market']['rules']['quantityMultiplier'] is not None
            and cfd['market']['state'] in {'LIVE', 'MARKET_CLOSED'}
            and len(cfd['market']['indicators']) == 6 and cfd['market']['quote']['source'] == 'GATE_REST_CFD'):
        break
    time.sleep(5)
else:
    raise SystemExit('Gate data acceptance timed out')
client.logout()
page = read("https://gold.mnnu.eu.org/", {})
if f'content="{commit}"' not in page:
    raise SystemExit("web source commit mismatch")
print("Argo digests, PoW session, Gate feeds and authenticated API/web Alpha release verified.")
