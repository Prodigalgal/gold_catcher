"""Read-only authenticated observation. Never starts research or changes orders/configuration."""
import argparse
import json
import os
import time
from pathlib import Path

from auth_client import SessionClient


def observe(client):
    system = client.request('/api/system/status')
    perpetual = client.request('/api/dashboard?symbol=XAU_USDT')
    cfd = client.request('/api/dashboard?symbol=GATE:XAUUSD')
    reliability = perpetual['sourceReliability']
    compression = perpetual.get('compression') or {}
    journal = perpetual.get('marketArchive') or {}
    channels = journal.get('channels') or []
    return {
        'serverTime': perpetual['serverTime'], 'version': system['version'],
        'sourceCommit': system['sourceCommit'], 'mode': system['mode'],
        'automatic': perpetual['scheduling']['automatic'],
        'sources': {key: reliability[key] for key in ['channels', 'healthy', 'degraded', 'stale']},
        'market': perpetual['market']['state'], 'cfd': cfd['market']['state'],
        'depth': (perpetual.get('liveDepth') or {}).get('state'),
        'archive': {**{key: journal.get(key) for key in ['state', 'queued', 'droppedSinceStart', 'error']},
                    'lastSequence': max((row['lastSequence'] for row in channels), default=0)},
        'compression': {**{key: compression.get(key) for key in
                          ['model', 'running', 'error', 'retryAt', 'consecutiveFailures', 'lastSuccessfulAt']},
                        'completed': sum(row['state'] == 'EXTRACTED' for row in compression.get('recent', [])),
                        'partial': sum(row['state'] == 'PARTIAL' for row in compression.get('recent', []))},
        'accounts': {symbol: {'state': packet['account']['state'], 'balance': packet['account']['balance']}
                     for symbol, packet in [('XAU_USDT', perpetual), ('GATE:XAUUSD', cfd)]},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='https://gold.mnnu.eu.org')
    parser.add_argument('--seconds', type=int, default=600)
    parser.add_argument('--interval', type=int, default=20)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 60 <= args.seconds <= 3600 or not 5 <= args.interval <= 60:
        parser.error('observation must be 60–3600 seconds, interval 5–60 seconds')
    username = os.environ.get('GOLD_SMOKE_USERNAME', '')
    password = os.environ.get('GOLD_SMOKE_PASSWORD', '')
    if not username or not password:
        raise SystemExit('missing scoped acceptance credentials')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    client = SessionClient(args.base)
    samples, errors = [], []
    started = time.monotonic()
    client.login(username, password)
    try:
        with args.output.open('w', encoding='utf-8') as output:
            while True:
                try:
                    sample = observe(client)
                    samples.append(sample)
                except Exception as failure:
                    # Exception bodies/URLs may contain private values. Record only the type.
                    sample = {'readError': type(failure).__name__, 'elapsed': round(time.monotonic() - started)}
                    errors.append(sample)
                output.write(json.dumps(sample, ensure_ascii=False) + '\n')
                output.flush()
                elapsed = time.monotonic() - started
                if len(samples) % 3 == 1 or elapsed >= args.seconds:
                    print(json.dumps({'elapsedSeconds': round(elapsed), 'successfulSamples': len(samples),
                                      'readErrors': len(errors), 'latest': sample}, ensure_ascii=False), flush=True)
                if elapsed >= args.seconds:
                    break
                time.sleep(min(args.interval, args.seconds - elapsed))
    finally:
        try:
            client.logout()
        except Exception:
            pass
    observations = {
        'durationSeconds': round(time.monotonic() - started), 'samples': len(samples), 'readErrors': len(errors),
        'healthySourceSamples': sum(s['sources']['healthy'] == s['sources']['channels'] == 31 for s in samples),
        'sequencedDepthSamples': sum(s['depth'] == 'SEQUENCED' for s in samples),
        'freshQuoteSamples': sum(s['market'] == 'LIVE' for s in samples),
        'summaryErrorSamples': sum(bool(s['compression']['error']) for s in samples),
        'archiveAdvanced': len(samples) >= 2 and samples[-1]['archive']['lastSequence'] > samples[0]['archive']['lastSequence'],
        'archiveLossSamples': sum(s['archive']['state'] != 'RECORDING' or s['archive']['droppedSinceStart'] != 0 for s in samples),
    }
    # Keep data collection and dependency quality separate; a completed observation is not an AI acceptance.
    observations['collectionPassed'] = (bool(samples) and not errors and observations['archiveAdvanced']
        and not observations['archiveLossSamples'] and observations['healthySourceSamples'] == len(samples)
        and all(s['mode'] == 'INTERNAL_SIMULATION_ALPHA' for s in samples))
    observations['researchAcceptance'] = 'NOT_PERFORMED_READ_ONLY_OBSERVATION'
    args.output.with_suffix('.summary.json').write_text(json.dumps(observations, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(observations, ensure_ascii=False), flush=True)
    return 0 if observations['collectionPassed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
