"""Source corpus acceptance uses paginated data reads, never a UI's latest-300 display window."""
import json
import time
import urllib.parse
import urllib.request
from auth_client import USER_AGENT


def publication_samples(owner, expected_sources, timeout=90, read=None):
    if not 1 <= timeout <= 180:
        raise ValueError('source sample timeout must be between 1 and 180 seconds')
    deadline = time.monotonic() + timeout
    created = owner.request('/api/access/tokens', {'name': 'Temporary deployment corpus acceptance',
        'scopes': ['information:read'], 'expiresInDays': 1, 'requestsPerMinute': 600})
    evidence = {'bea': 'BEA_NEWS' not in expected_sources,
                'bls': not bool(set(expected_sources) & {'BLS_CPI', 'BLS_PPI', 'BLS_JOLTS', 'BLS_EMPLOYMENT'}), 'samples': []}

    def query(tool, args):
        if time.monotonic() >= deadline:
            raise TimeoutError('source corpus acceptance deadline exceeded')
        path = '/api/external/v1/' + tool + '?' + urllib.parse.urlencode(args)
        if read is not None:
            return read(path)
        request = urllib.request.Request(owner.base + path, headers={'Authorization': 'Bearer ' + created['secret'],
            'Accept': 'application/json', 'User-Agent': USER_AGENT})
        with urllib.request.urlopen(request, timeout=max(1, min(20, deadline-time.monotonic()))) as response:
            return json.load(response)

    try:
        for source in ['BEA_NEWS', 'BLS_CPI', 'BLS_PPI', 'BLS_EMPLOYMENT', 'BLS_JOLTS']:
            if source not in expected_sources or source.startswith('BLS_') and evidence['bls']:
                continue
            cursor = None
            seen = set()
            bea_sample = None
            for _ in range(24):
                args = {'source': source, 'limit': 20}
                if cursor:
                    args['cursor'] = cursor
                page = query('search_news', args)
                for item in page['items']:
                    if item.get('archived') or item.get('source') != source:
                        continue
                    if source == 'BEA_NEWS' and item.get('publishedAt'):
                        evidence['bea'] = True
                        sample = {'id': item['id'], 'source': source, 'publishedAt': item['publishedAt'],
                            'freshness': item.get('freshness'), 'purpose': 'STORED_PUBLICATION_AVAILABILITY_NOT_LIVE_NEWS'}
                        if bea_sample is None or sample['publishedAt'] > bea_sample['publishedAt']:
                            bea_sample = sample
                    if source.startswith('BLS_'):
                        detail = query('get_news', {'id': item['id'], 'representation': 'original', 'maxCharacters': 1})
                        n = detail.get('news') or {}
                        if (n.get('publishedPrecision') == 'DECLARED_RELEASE_TIME' and detail.get('totalCharacters', 0) > 80
                                and detail.get('bodyMatchesCurrentFeed') is True and not detail.get('bodyError')
                                and detail.get('state') in ('AVAILABLE', 'TRUNCATED')):
                            evidence['bls'] = True
                            evidence['samples'].append({'id': item['id'], 'source': source, 'publishedAt': n.get('publishedAt'),
                                'characters': detail['totalCharacters'], 'freshness': n.get('freshness'),
                                'precision': n['publishedPrecision'], 'purpose': 'STORED_BODY_PARSER_AVAILABILITY_NOT_LIVE_NEWS'})
                            break
                if source.startswith('BLS_') and evidence['bls']:
                    break
                cursor = page.get('nextCursor')
                if not cursor:
                    break
                if cursor in seen:
                    raise ValueError('source corpus cursor repeated')
                seen.add(cursor)
            if bea_sample is not None:
                evidence['samples'].append(bea_sample)
        return evidence
    finally:
        owner.request('/api/access/tokens/' + created['token']['id'] + '/revoke', {})
