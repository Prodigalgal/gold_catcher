"""Bounded CI acceptance; no cluster admin credential or market/LLM side effects."""
import json
import os
import time
import urllib.error
import urllib.request
import http.client
from decimal import Decimal
from pathlib import Path
from auth_client import SessionClient, USER_AGENT

commit = os.environ["SOURCE_COMMIT"]
token = os.environ["ARGOCD_READ_TOKEN"]
username, password = os.environ["GOLD_SMOKE_USERNAME"], os.environ["GOLD_SMOKE_PASSWORD"]
if not all((token, username, password)):
    raise SystemExit("missing scoped acceptance credentials")
release = json.loads(Path("gitops/gold-catcher/oracle/release.json").read_text())
images = {f'{r["image"]}@{r["digest"]}' for r in release["components"]}


def read(url, headers):
    headers = {'User-Agent': USER_AGENT, **headers}
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
try:
    read('https://gold.mnnu.eu.org/api/system/status', {})
except urllib.error.HTTPError as error:
    if error.code != 401:
        raise SystemExit('unexpected unauthenticated API response')
else:
    raise SystemExit('API unexpectedly accessible without authentication')
client.login(username, password)
def snapshot(path):
    for attempt in range(3):
        try:
            return client.request(path)
        except (http.client.IncompleteRead, ConnectionResetError, urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(1)
api = client.request('/api/system/status')
if api.get("sourceCommit") != commit or api.get("mode") != "INTERNAL_SIMULATION_ALPHA" or api.get('venue') != 'GATE' or api.get('authentication') != 'SESSION_CSRF_SHA256_POW':
    raise SystemExit("API source commit/mode/venue/authentication mismatch")
if set(api.get('products', [])) != {'XAU_USDT', 'GATE:XAUUSD'}:
    raise SystemExit('unexpected active products')
expected_features = {'OFFICIAL_HTML_PDF_XLSX', 'MIMO_CACHED_SOURCE_COMPRESSION', 'FOUR_STAGE_DIVERSE_RESEARCH',
                     'PERSISTED_SHORT_LONG_SCHEDULER', 'INTERNAL_ORDER_MANAGEMENT', 'CFD_SEPARATE_ACCOUNT',
                     'CURSOR_MARKET_ARCHIVE', 'MINUTE_EQUITY_REVIEW', 'CSV_JSON_EXPORT'}
if api.get('version') != '0.4.0' or not expected_features <= set(api.get('features', [])):
    raise SystemExit('runtime version or completion feature contract mismatch')
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    perpetual = snapshot('/api/dashboard?symbol=XAU_USDT')
    cfd = snapshot('/api/dashboard?symbol=GATE:XAUUSD')
    if (perpetual['market']['state'] == 'LIVE' and len(perpetual['market']['indicators']) == 6
            and perpetual['market']['quote']['source'] == 'GATE_WS_BOOK_TICKER'
            and perpetual['market']['rules']['quantityMultiplier'] is not None
            and cfd['market']['state'] in {'LIVE', 'MARKET_CLOSED'}
            and len(cfd['market']['indicators']) == 6 and cfd['market']['quote']['source'] == 'GATE_REST_CFD'
            and cfd['marketProvenance']['rulesState'] == 'SPEC_AND_COMMISSION_VERIFIED'):
        break
    time.sleep(5)
else:
    raise SystemExit('Gate data acceptance timed out')
if (cfd['account']['symbol'] != 'GATE:XAUUSD' or cfd['account']['currency'] != 'USD'
        or cfd['account']['balance'] is None or not cfd['account']['costStatus'].startswith('CFD_QUOTE_SCENARIO;')
        or 'SWAP_' not in cfd['account']['costStatus']):
    raise SystemExit('separate CFD ledger, units or unknown-cost scope mismatch')
compression = perpetual.get('compression') or {}
if compression.get('provider') != 'mimo' or compression.get('voting') is not False:
    raise SystemExit('MIMO preprocessing role mismatch')
if not any(model['routeId'] == 'compressor-mimo' and model['provider'] == 'mimo'
           and model['credentialConfigured'] for model in perpetual['models']):
    raise SystemExit('MIMO preprocessing route is not credential-configured')
deadline = time.monotonic() + 120
while time.monotonic() < deadline:
    runtime = snapshot('/api/dashboard?symbol=XAU_USDT')
    journal = runtime.get('marketArchive') or {}
    channels = {row['channel'] for row in journal.get('channels', [])}
    if (journal.get('state') == 'RECORDING' and runtime.get('liveDepth', {}).get('state') == 'SEQUENCED'
            and {'CONNECTION_EPOCH', 'DEPTH_REST_BASE', 'futures.order_book_update'} <= channels):
        page = snapshot('/api/market/archive?symbol=XAU_USDT&after=0&limit=3')
        assert page['frames'] and page['nextCursor'] == page['frames'][-1]['sequence']
        assert all(row['receivedAt'] and 'payload' in row for row in page['frames'])
        break
    time.sleep(5)
else:
    raise SystemExit('sequenced depth or durable archive cursor acceptance timed out')
for product in ('XAU_USDT', 'GATE:XAUUSD'):
    sample = snapshot('/api/market/candles?symbol=' + product + '&frame=5')
    assert sample['symbol'] == product and sample['frame'] == '5' and sample['candles']
    with client.opener.open(urllib.request.Request(client.base + '/api/simulation/export?symbol=' + product,
                                                   headers={'User-Agent': USER_AGENT}), timeout=20) as response:
        csv_text = response.read(1_000_000).decode('utf-8-sig')
    assert csv_text.startswith('id,symbol,quantityUnit,') and 'costIncomplete,evidenceLevel' in csv_text
expected_sources = {'FED_PRESS', 'FED_SPEECH', 'ECB_PRESS', 'BEA_NEWS', 'BEA_CALENDAR',
                    'BLS_CPI', 'BLS_EMPLOYMENT', 'BLS_PPI', 'BLS_JOLTS', 'BLS_CALENDAR', 'FOMC_CALENDAR',
                    'BOE_NEWS', 'BOE_SPEECH', 'BOJ_NEWS', 'EIA_PRESS', 'EIA_ENERGY', 'EIA_NEW'}
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    sources = snapshot('/api/dashboard?symbol=XAU_USDT')
    states = {source['id']: source for source in sources['sources']}
    if (set(states) == expected_sources
            and all(states[key]['state'] == 'CURRENT' and states[key].get('quality') for key in expected_sources)
            and any(item['source'] == 'BEA_NEWS' and item.get('publishedAt') and not item.get('archived') for item in sources['news'])
            and any(item['source'].startswith('BLS_') and item.get('publishedPrecision') == 'DECLARED_RELEASE_TIME'
                    and item.get('contentCharacters', 0) > 80 for item in sources['news'])
            and any(event['source'] == 'BLS_CALENDAR' for event in sources['calendar'])
            and any(event['source'] == 'FOMC_CALENDAR' and event['scheduledAt'] is None
                    and event.get('timePrecision') == 'DATE_RANGE' for event in sources['calendar'])):
        break
    time.sleep(5)
else:
    raise SystemExit('Official feed, article body or calendar acceptance timed out')
boj_xlsx = [item for item in sources['news'] if item['source'] == 'BOJ_NEWS' and '.xlsx' in item['link'].lower()]
for item in boj_xlsx[:12]:
    body = snapshot('/api/news/' + item['id']).get('body') or {}
    if body.get('format') != 'XLSX_SOURCE_CELLS' or body.get('error') or not body.get('text'):
        raise SystemExit('BoJ source-cell XLSX body acceptance failed')
driver_sources = {'GATE_USIDX', 'GATE_EURUSD', 'GATE_USDJPY', 'TREASURY_NOMINAL', 'TREASURY_REAL', 'FF_EXPECTATIONS'}
driver_ids = {'GATE_USIDX', 'GATE_EURUSD', 'GATE_USDJPY', 'US_NOMINAL_2Y', 'US_NOMINAL_10Y', 'US_REAL_10Y'}
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    analysis = snapshot('/api/dashboard?symbol=XAU_USDT').get('analysis', {})
    channels = {item['id']: item for item in analysis.get('sources', [])}
    observations = {item['id']: item for item in analysis.get('drivers', [])}
    metrics = {item['indicator']: item for item in analysis.get('releases', [])}
    if (set(channels) == driver_sources and all(item['state'] == 'CURRENT' for item in channels.values())
            and set(observations) == driver_ids
            and all(observations[key]['frequency'] == 'DAILY' and observations[key]['asOfDate']
                    and observations[key]['unit'] == 'PERCENT' and observations[key]['responseAt'] is None
                    for key in {'US_NOMINAL_2Y', 'US_NOMINAL_10Y', 'US_REAL_10Y'})
            and all(observations[key]['timestampMeaning'] == 'SERVER_RESPONSE_TIME; LAST_TICK_TIME_NOT_SUPPLIED'
                    for key in {'GATE_USIDX', 'GATE_EURUSD', 'GATE_USDJPY'})
            and {item['id'] for item in analysis.get('horizons', [])} == {'M5_15', 'M15_60', 'H1_4', 'H4_48'}
            and analysis.get('method') == 'JAVA_STRUCTURE_V1_UNCALIBRATED' and analysis.get('gaps')
            and {'CPI_MOM', 'NFP', 'JOLTS', 'PCE_MOM'} <= set(metrics)
            and all(item['actual'] is not None and item['bodyHash'] and item['extractedAt']
                    and (item['surprise'] is None or item['expectationReceivedAt'] < item['publishedAt'])
                    for item in metrics.values())):
        break
    time.sleep(5)
else:
    raise SystemExit('Public driver, official numeric release or four-horizon analysis acceptance timed out')
supplemental_sources = {'DOL_CLAIMS', 'CENSUS_ECON', 'CFTC_GOLD', 'TREASURY_AUCTIONS', 'IAU_HOLDINGS', 'GLD_HOLDINGS', 'GATE_DEPTH', 'GATE_TRADES'}
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    extra = snapshot('/api/dashboard?symbol=XAU_USDT').get('analysis', {}).get('supplemental') or {}
    channels = {s['id']: s for s in extra.get('sources', [])}
    rows = extra.get('observations', [])
    depth, trades = extra.get('depth') or {}, extra.get('trades') or {}
    if (set(channels) == supplemental_sources and all(s['state'] == 'CURRENT' for s in channels.values())
            and {'INITIAL_CLAIMS', 'CONTINUED_CLAIMS'} <= {o['id'] for o in rows}
            and {'CENSUS_ECON', 'CFTC_GOLD', 'TREASURY_AUCTIONS', 'IAU_HOLDINGS', 'GLD_HOLDINGS'} <= {o['source'] for o in rows}
            and all(o['receivedAt'] and o['bodyHash'] for o in rows)
            and depth.get('symbol') == 'XAU_USDT' and 1 <= len(depth.get('bids', [])) <= 20
            and 1 <= len(depth.get('asks', [])) <= 20 and depth.get('responseAt') and depth.get('updatedAt')
            and trades.get('symbol') == 'XAU_USDT' and 1 <= trades.get('count', 0) <= 100
            and trades.get('firstAt') and trades.get('lastAt') and trades.get('vwap')
            and 'NOT_FIXED_TIME_WINDOW' in trades.get('coverage', '')
            and len(extra.get('coverage', [])) == 8):
        for level in depth['bids'] + depth['asks']:
            assert Decimal(level['quantity']) == Decimal(level['nativeContracts']) * Decimal(depth['quantityMultiplier'])
        assert len(trades['rows']) == trades['count']
        assert len({r['id'] for r in trades['rows']}) == trades['count']
        assert all(Decimal(o['values']['tonnes']) > 0 for o in rows if o['source'] == 'IAU_HOLDINGS' and 'tonnes' in o['values'])
        assert len([o for o in rows if o['source'] == 'GLD_HOLDINGS']) >= 2
        assert all(Decimal(o['values']['tonnes']) > 0 and o['publishedAt'] is None for o in rows if o['source'] == 'GLD_HOLDINGS')
        break
    time.sleep(5)
else:
    raise SystemExit('Eight supplemental public sources, provenance or microstructure samples acceptance timed out')
expected_health = expected_sources | driver_sources | supplemental_sources
deadline = time.monotonic() + 120
while time.monotonic() < deadline:
    health = snapshot('/api/dashboard?symbol=XAU_USDT').get('sourceReliability') or {}
    checks = health.get('sources', [])
    if (health.get('channels') == 31 and {c['id'] for c in checks} == expected_health
            and health.get('healthy') == 31 and health.get('stale') == 0
            and all(c['collection']['samples'] >= 1 and c['collection']['lastDurationMillis'] is not None for c in checks)
            and all(c['collection']['samples'] <= 128 for c in checks)
            and 'POLL_CADENCE_IS_NOT_PUBLICATION_SLA' in health.get('statisticsScope', '')):
        assert all(c['arrival']['lagMillis'] is None or c['arrival']['timestampMeaning'] in {'EXACT_PUBLICATION', 'DECLARED_RELEASE_TIME'}
                   for c in checks if c.get('arrival'))
        break
    time.sleep(5)
else:
    raise SystemExit('Source collection freshness, bounded latency metrics or public timing semantics not verified')
assert 'sampledMaximumDrawdown' in runtime['review'] and runtime['scheduling'].get('config')
if runtime['researchHistory']:
    run_id = runtime['researchHistory'][0]['id']
    assert snapshot('/api/research/' + run_id + '/export')['id'] == run_id
client.logout()
page = read("https://gold.mnnu.eu.org/", {})
if f'content="{commit}"' not in page:
    raise SystemExit("web source commit mismatch")
print("Argo digests, PoW session, Gate feeds, separate CFD ledger, MIMO role, sequenced depth/archive, exports, seventeen official channels, six driver/calendar channels, eight supplemental channels, dated metrics, sample units, coverage inventory, four horizons and API/web release verified. Live research completion is a separate acceptance.")
