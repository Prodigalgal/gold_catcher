"""Bounded CI acceptance; no cluster admin credential or market/LLM side effects."""
import json
import os
import time
import urllib.error
import urllib.request
from decimal import Decimal
from pathlib import Path
from auth_client import SessionClient, USER_AGENT
from http_snapshot import get_snapshot
from source_acceptance import publication_samples

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
    return get_snapshot(client, path)
api = client.request('/api/system/status')
if api.get("sourceCommit") != commit or api.get("mode") != "INTERNAL_SIMULATION_ALPHA" or api.get('venue') != 'GATE' or api.get('authentication') != 'SESSION_CSRF_SHA256_POW':
    raise SystemExit("API source commit/mode/venue/authentication mismatch")
if set(api.get('products', [])) != {'XAU_USDT', 'GATE:XAUUSD'}:
    raise SystemExit('unexpected active products')
expected_features = {'OFFICIAL_HTML_PDF_XLSX', 'MIMO_CACHED_SOURCE_COMPRESSION', 'FOUR_STAGE_DIVERSE_RESEARCH',
                     'THREE_MODEL_RESEARCH_AND_JUDGING', 'BLIND_THREE_JUDGE_VOTING', 'PERSISTED_AI_PROVIDER_CONFIGURATION', 'CONFIGURABLE_APPROVED_MODEL_FALLBACK',
                     'RESEARCH_SEAT_RETRIES', 'STALE_EVIDENCE_ANALYSIS_CONTINUES', 'RESEARCH_CHECKPOINT_RECOVERY', 'UNIFIED_RESEARCH_TEXT_PROTOCOL',
                     'PERSISTED_SHORT_LONG_SCHEDULER', 'INTERNAL_ORDER_MANAGEMENT', 'CFD_SEPARATE_ACCOUNT',
                     'CURSOR_MARKET_ARCHIVE', 'MINUTE_EQUITY_REVIEW', 'CSV_JSON_EXPORT',
                     'PUBLIC_INTRADAY_TREASURY_TIPS', 'PUBLIC_FINANCIAL_MEDIA_RSS',
                     'DIVERSE_NEWS_CANDIDATE_POOL', 'INDEPENDENT_COMPRESSION_LANE', 'ANY2API_CIRCUIT_RECOVERY', 'IMMEDIATE_SOURCE_PREPROCESSING',
                     'SUMMARY_ONLY_NEWS_ADMISSION', 'CONFIGURABLE_COMPRESSION_REPROCESSING', 'NONBLOCKING_RESEARCH_ADMISSION'}
expected_version = json.loads(Path('web/package.json').read_text())['version']
if api.get('version') != expected_version or not expected_features <= set(api.get('features', [])):
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
    details = {'perpetualState': perpetual['market']['state'], 'perpetualIndicators': len(perpetual['market']['indicators']),
               'perpetualQuoteSource': (perpetual['market'].get('quote') or {}).get('source'),
               'cfdState': cfd['market']['state'], 'cfdIndicators': len(cfd['market']['indicators']),
               'cfdRulesState': cfd['marketProvenance'].get('rulesState')}
    raise SystemExit('Gate data acceptance timed out: ' + json.dumps(details))
if (cfd['account']['symbol'] != 'GATE:XAUUSD' or cfd['account']['currency'] != 'USD'
        or cfd['account']['balance'] is None or not cfd['account']['costStatus'].startswith('CFD_QUOTE_SCENARIO;')
        or 'SWAP_' not in cfd['account']['costStatus']):
    raise SystemExit('separate CFD ledger, units or unknown-cost scope mismatch')
compression = perpetual.get('compression') or {}
schedule = perpetual.get('scheduling') or {}
deadline = time.monotonic() + 120
while time.monotonic() < deadline:
    configuration = snapshot('/api/ai/configuration')
    readiness = schedule.get('routeReadiness', [])
    if (configuration['encryptionConfigured'] is True
            and all(len(configuration[key]['researchers']) == len(configuration[key]['judges']) == 3 for key in ('shortRoutes', 'longRoutes'))
            and len(readiness) == 2 and all(len([r for r in row['checks'] if r['judge'] is judging and not r.get('fallbackFor')]) == 3 for row in readiness for judging in (False, True))):
        break
    time.sleep(5)
    perpetual = snapshot('/api/dashboard?symbol=XAU_USDT')
    schedule = perpetual.get('scheduling') or {}
else:
    raise SystemExit('Persistent three-model UI configuration acceptance timed out')
if 'qwen' not in schedule.get('disabledProviders', []) or any(model['provider'].strip().lower() == 'qwen' for model in perpetual['models']):
    raise SystemExit('withdrawn QWEN provider was not excluded')
readiness = schedule.get('routeReadiness', [])
if {row.get('horizon') for row in readiness} != {'SHORT', 'LONG'}:
    raise SystemExit('research route readiness diagnostics missing')
for row in readiness:
    if row.get('basis') != 'CREDENTIALS_CATALOG_AND_FAILURE_COOLDOWN_ONLY; NOT_INFERENCE_OR_CONTEXT_ACCEPTANCE':
        raise SystemExit('research readiness overstates its evidence')
    assert all(route['provider'].strip().lower() != 'qwen' for route in row['selected'])
    assert row.get('judgeMinimum') == row.get('researcherMinimum') == 3
    for judging in (False, True):
        identities = [route['model'].strip().lower().split('/', 1)[-1] for route in row['selected'] if route['judge'] is judging]
        assert len(identities) == len(set(identities))
        configured = [route for route in row['checks'] if route['judge'] is judging and not route.get('fallbackFor')]
        assert len(configured) == 3 and len({route['provider'] for route in configured}) == 3
    if row['state'] == 'READY':
        assert len(row['selected']) == 6
        research_models = {route['model'] for route in row['selected'] if not route['judge']}
        judge_models = {route['model'] for route in row['selected'] if route['judge']}
        assert len(research_models) == len(judge_models) == 3
assert configuration['encryptionConfigured'] is True
assert all('apiKey' not in provider and 'encryptedApiKey' not in provider for provider in configuration['providers'])
assert all(len(configuration[key]['researchers']) == len(configuration[key]['judges']) == 3 for key in ('shortRoutes', 'longRoutes'))
assert configuration['compression']['provider'] in {p['id'] for p in configuration['providers']}
assert configuration['compression']['provider'] != 'qwen'
approved_providers = {'mimo', 'glm', 'longcat', 'minmax', 'grok', 'grok_web'}
assert {p['id'] for p in configuration['providers']} <= approved_providers
for horizon in ('shortRoutes', 'longRoutes'):
    for role in ('researchers', 'judges'):
        for selection in configuration[horizon][role]:
            assert selection['provider'] in approved_providers
            assert not selection.get('fallback') or selection['fallback']['provider'] in approved_providers
assert not configuration['compression'].get('fallback') or configuration['compression']['fallback']['provider'] in approved_providers
for key in ('shortPolicy', 'longPolicy'):
    policy = configuration[key]
    assert 1 <= policy['maxAttempts'] <= 6
    assert 1 <= policy['retryBaseSeconds'] <= policy['retryMaxSeconds'] <= 120
    assert 30 <= policy['stageSeconds'] <= 1800 and 30 <= policy['judgeSeconds'] <= 1800
    assert max(policy['stageSeconds'], policy['judgeSeconds']) <= policy['workflowSeconds'] <= 7200
    assert 30 <= policy['decisionSeconds'] <= 172800
assert schedule.get('state') in {'DISABLED', 'RUNNING', 'MONITORING', 'PREPARING_EVIDENCE'}
if compression.get('provider') != configuration['compression']['provider'] or compression.get('voting') is not False:
    raise SystemExit('Source preprocessing role mismatch')
if compression.get('state') not in {'DISABLED', 'CREDENTIAL_MISSING', 'RUNNING', 'UPSTREAM_BACKOFF', 'COMPRESSION_POOL_BUSY', 'SCANNING', 'QUEUED', 'ARTICLE_RETRY_WAIT', 'NO_ELIGIBLE_BODY', 'UP_TO_DATE'}:
    raise SystemExit('Source preprocessing scheduling state missing')
assert compression.get('executionLane') == 'compression_lane' and compression.get('independent') is True
queue = compression.get('queue') or {}
assert compression.get('method') == 'source-summary-v5'
for product in (perpetual, cfd):
    run = product.get('research') or {}
    preprocessing = run.get('preprocessing') or {}
    if preprocessing.get('method') == 'source-summary-v5':
        articles = preprocessing.get('articles') or []
        admitted = [a for a in articles if a['state'] == 'EXTRACTED']
        assert preprocessing['completedArticles'] == len(admitted)
        assert preprocessing['omittedArticles'] == len(articles) - len(admitted)
        assert all(0 < a['sentCharacters'] <= 300 for a in admitted)
        assert all(a['sentCharacters'] == 0 for a in articles if a['state'] != 'EXTRACTED')
        assert {a['newsId'] for a in admitted} == {n['id'] for n in run['evidence']['news']}
for field in ('candidateArticles', 'eligibleBodies', 'completedSummaries', 'queuedArticles', 'processingArticles', 'retryingArticles', 'missingBodies', 'unsuitableBodies'):
    assert isinstance(queue.get(field), int) and queue[field] >= 0
assert queue['candidateArticles'] == queue['eligibleBodies'] + queue['missingBodies'] + queue['unsuitableBodies']
assert queue['eligibleBodies'] == queue['completedSummaries'] + queue['queuedArticles'] + queue['processingArticles'] + queue['retryingArticles']
if not any(model['routeId'] == 'compressor-mimo' and model['provider'] == configuration['compression']['provider']
           and model['credentialConfigured'] for model in perpetual['models']):
    raise SystemExit('Source preprocessing route is not credential-configured')
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
source_selection = snapshot('/api/sources/configuration')
expected_sources = {item['id'] for item in source_selection['channels'] if item['enabled'] and item['family'] == 'NEWS'}
assert expected_sources.isdisjoint(source_selection['configuration']['disabledSourceIds'])
publication_evidence = publication_samples(client, expected_sources)
if not publication_evidence['bea'] or not publication_evidence['bls']:
    raise SystemExit('Stored official publication or declared-time BLS body acceptance failed')
print('Verified stored official publication/body samples (not a live publication SLA): ' + json.dumps(publication_evidence))
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    sources = snapshot('/api/dashboard?symbol=XAU_USDT')
    states = {source['id']: source for source in sources['sources']}
    if (set(states) == expected_sources
            and all(states[key]['state'] == 'CURRENT' and states[key].get('quality') for key in expected_sources)
            and publication_evidence['bea'] and publication_evidence['bls']
            and ('BLS_CALENDAR' not in expected_sources or any(event['source'] == 'BLS_CALENDAR' for event in sources['calendar']))
            and ('FOMC_CALENDAR' not in expected_sources or any(event['source'] == 'FOMC_CALENDAR' and event['scheduledAt'] is None
                    and event.get('timePrecision') == 'DATE_RANGE' for event in sources['calendar']))):
        break
    time.sleep(5)
else:
    raise SystemExit('Official feed, article body or calendar acceptance timed out')
assert all(states[key]['intervalSeconds'] == 30 and states[key]['quality']['publication'] == 'PROVIDER_REPORTED_TIME'
           for key in expected_sources.intersection({'CNBC_TOP', 'CNBC_ECONOMY', 'CNBC_WORLD'}))
media = [item for item in sources['news'] if item['source'].startswith('CNBC_')]
assert (media or not expected_sources.intersection({'CNBC_TOP', 'CNBC_ECONOMY', 'CNBC_WORLD'})) and all(item['verification'] == 'MEDIA_REPORT' and item['publishedPrecision'] == 'PROVIDER_REPORTED_TIME'
                     and item['contentState'] == 'PUBLIC_FEED_SUMMARY' and item['bodyHash'] is None for item in media)
assert len({item['link'] for item in media}) == len(media)
boj_xlsx = [item for item in sources['news'] if item['source'] == 'BOJ_NEWS' and '.xlsx' in item['link'].lower()]
for item in boj_xlsx[:12]:
    body = snapshot('/api/news/' + item['id']).get('body') or {}
    if body.get('format') != 'XLSX_SOURCE_CELLS' or body.get('error') or not body.get('text'):
        raise SystemExit('BoJ source-cell XLSX body acceptance failed')
driver_sources = {'GATE_USIDX', 'GATE_EURUSD', 'GATE_USDJPY', 'TREASURY_NOMINAL', 'TREASURY_REAL', 'FF_EXPECTATIONS', 'TE_EXPECTATIONS', 'CNBC_US2Y', 'CNBC_US10Y', 'CNBC_US10YTIPS'}
driver_sources -= set(source_selection['configuration']['disabledSourceIds'])
driver_ids = {'GATE_USIDX', 'GATE_EURUSD', 'GATE_USDJPY', 'US_NOMINAL_2Y', 'US_NOMINAL_10Y', 'US_REAL_10Y', 'US_INTRADAY_NOMINAL_2Y', 'US_INTRADAY_NOMINAL_10Y', 'US_INTRADAY_REAL_10Y'}
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
public_rates = {'US_INTRADAY_NOMINAL_2Y', 'US_INTRADAY_NOMINAL_10Y', 'US_INTRADAY_REAL_10Y'}
assert all(channels[key]['intervalSeconds'] == 60 for key in {'CNBC_US2Y', 'CNBC_US10Y', 'CNBC_US10YTIPS'})
assert all(observations[key]['frequency'] == 'INTRADAY_PUBLIC_WEB' and observations[key]['unit'] == 'PERCENT'
           and observations[key]['changeUnit'] == 'BASIS_POINTS' and observations[key]['asOfDate']
           and observations[key]['responseAt'] and observations[key]['receivedAt']
           and observations[key]['timestampMeaning'] == 'PROVIDER_LAST_TIME_WITH_DATE_AND_OFFSET; RECEIPT_IS_NOT_QUOTE_TIME'
           and isinstance(observations[key]['changesBp'], dict) and not observations[key]['returnsPct']
           and Decimal('-20') <= Decimal(observations[key]['value']) <= Decimal('50') for key in public_rates)
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
    if (health.get('channels') == len(expected_health) and {c['id'] for c in checks} == expected_health
            and health.get('healthy') == len(expected_health) and health.get('stale') == 0
            and all(c['collection']['samples'] >= 1 and c['collection']['lastDurationMillis'] is not None for c in checks)
            and all(c['collection']['samples'] <= 128 for c in checks)
            and 'POLL_CADENCE_IS_NOT_PUBLICATION_SLA' in health.get('statisticsScope', '')):
        assert all(c['arrival']['lagMillis'] is None or c['arrival']['timestampMeaning'] in {'EXACT_PUBLICATION', 'DECLARED_RELEASE_TIME', 'PROVIDER_DATED_QUOTE_TIME; PUBLIC_WEB_NOT_SLA', 'PROVIDER_REPORTED_PUBLICATION'}
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
print("Argo digests, PoW session, Gate feeds, separate CFD ledger, source selection and compression role, sequenced depth/archive, exports, configured news and driver/calendar sources, eight supplemental channels, dated metrics, sample units, coverage inventory, four horizons and API/web release verified. Live research completion is a separate acceptance.")
