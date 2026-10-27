import unittest
from urllib.parse import urlsplit, parse_qs
from source_acceptance import publication_samples


class Owner:
    base = 'https://example.test'
    def __init__(self):
        self.calls = []
    def request(self, path, body=None):
        self.calls.append((path, body))
        if path == '/api/access/tokens':
            return {'token': {'id': 'temporary'}, 'secret': 'ephemeral-test'}
        return {'state': 'REVOKED'}


class SourceCorpusTest(unittest.TestCase):
    def test_empty_ui_window_does_not_hide_archived_official_evidence_and_body_mismatch_is_rejected(self):
        owner = Owner()
        paths = []
        def read(path):
            paths.append(path)
            args = {k: v[0] for k,v in parse_qs(urlsplit(path).query).items()}
            if '/search_news?' in path:
                if args['source'] == 'BEA_NEWS':
                    if 'cursor' not in args:
                        return {'items': [], 'nextCursor': 'next-bea'}
                    return {'items': [{'id': 'bea-older', 'source': 'BEA_NEWS', 'publishedAt': '2025-04-02T14:00:00Z', 'freshness': 'BACKGROUND'},
                        {'id': 'bea', 'source': 'BEA_NEWS', 'publishedAt': '2026-10-06T12:30:00Z', 'freshness': 'BACKGROUND'}]}
                return {'items': [{'id': 'mismatch', 'source': 'BLS_CPI'}, {'id': 'coherent', 'source': 'BLS_CPI'}]}
            return {'news': {'publishedPrecision': 'DECLARED_RELEASE_TIME'}, 'totalCharacters': 1000,
                'bodyMatchesCurrentFeed': args['id'] == 'coherent', 'bodyError': None, 'state': 'AVAILABLE'}
        result = publication_samples(owner, {'BEA_NEWS', 'BLS_CPI'}, read=read)
        self.assertTrue(result['bea'] and result['bls'])
        self.assertEqual(result['samples'][0]['freshness'], 'BACKGROUND')
        self.assertEqual(result['samples'][0]['id'], 'bea')
        self.assertEqual(result['samples'][1]['id'], 'coherent')
        self.assertTrue(any('cursor=next-bea' in p for p in paths))
        self.assertEqual(owner.calls[0][1]['scopes'], ['information:read'])
        self.assertEqual(owner.calls[-1][0], '/api/access/tokens/temporary/revoke')

    def test_unverified_time_does_not_pass_body_acceptance_and_token_is_revoked(self):
        owner = Owner()
        def read(path):
            if '/search_news?' in path:
                return {'items': [{'id': 'bad', 'source': 'BLS_CPI'}]}
            return {'news': {'publishedPrecision': 'FEED_TIMESTAMP'}, 'totalCharacters': 1000,
                'bodyMatchesCurrentFeed': True, 'bodyError': None, 'state': 'AVAILABLE'}
        result = publication_samples(owner, {'BLS_CPI'}, read=read)
        self.assertFalse(result['bls'])
        self.assertEqual(owner.calls[-1][0], '/api/access/tokens/temporary/revoke')

    def test_repeated_cursor_and_exceptions_cannot_leave_a_live_test_token(self):
        owner = Owner()
        with self.assertRaisesRegex(ValueError, 'cursor repeated'):
            publication_samples(owner, {'BEA_NEWS'}, read=lambda path: {'items': [], 'nextCursor': 'same'})
        self.assertEqual(owner.calls[-1][0], '/api/access/tokens/temporary/revoke')
        with self.assertRaises(ValueError):
            publication_samples(owner, set(), timeout=0)
