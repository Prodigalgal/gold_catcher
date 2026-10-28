"""Official MCP SDK OAuth discovery -> DCR -> PoW/consent -> PKCE -> refresh -> revoke.

Uses only disposable OAuth grants, revokes them in finally, and never calls a model.
The interactive browser approval is exercised by the same authenticated consent API.
"""
import argparse
import asyncio
import http.cookiejar
import importlib.metadata
import json
import os
import secrets
import urllib.error
import urllib.parse
import urllib.request
import httpx2
from mcp import ClientSession
from mcp.client.auth import OAuthClientProvider
from mcp.client.streamable_http import streamable_http_client
from mcp.shared.auth import AuthorizationCodeResult, OAuthClientMetadata
from auth_client import SessionClient, USER_AGENT
from gold_catcher_mcp import NoRedirect
from verify_mcp_sdk import inspect


class MemoryStorage:
    def __init__(self):
        self.tokens = None
        self.client = None

    async def get_tokens(self):
        return self.tokens

    async def set_tokens(self, value):
        self.tokens = value

    async def get_client_info(self):
        return self.client

    async def set_client_info(self, value):
        self.client = value


async def verify(base, owner, scopes=None, inspector=inspect):
    storage = MemoryStorage()
    requested_scopes = scopes or ['market:read', 'information:read', 'research:read']
    name = 'Temporary OAuth SDK acceptance ' + secrets.token_hex(4)
    callback = None
    grants = set()
    browser = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPCookieProcessor(owner.cookies))

    async def redirect(url):
        nonlocal callback
        parsed = urllib.parse.urlparse(url)
        expected = urllib.parse.urlparse(base)
        assert (parsed.scheme, parsed.netloc, parsed.path) == (expected.scheme, expected.netloc, '/api/oauth/authorize')
        request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
        try:
            browser.open(request, timeout=20)
            raise AssertionError('authorization did not require consent')
        except urllib.error.HTTPError as response:
            with response:
                assert response.code == 303
                location = response.headers['Location']
        assert location.startswith('/oauth/consent?')
        ticket = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)['request'][0]
        pending = owner.request('/api/oauth/consent?request=' + urllib.parse.quote(ticket))
        assert pending['clientName'] == name and pending['authenticated'] is True
        # Discovery may advertise more rights than this client/user elects to grant.
        assert set(requested_scopes) <= set(pending['scopes'])
        result = owner.request('/api/oauth/consent', {'request': ticket, 'decision': 'approve', 'scopes': requested_scopes})
        for item in owner.request('/api/access/oauth')['authorizations']:
            if item['clientName'] == name:
                grants.add(item['id'])
        target = urllib.parse.urlparse(result['redirectUrl'])
        assert target.scheme == 'http' and target.netloc == '127.0.0.1:18919' and target.path == '/callback'
        values = urllib.parse.parse_qs(target.query)
        callback = AuthorizationCodeResult(code=values['code'][0], state=values['state'][0], iss=values['iss'][0])

    async def receive():
        assert callback is not None
        return callback

    provider = OAuthClientProvider(server_url=base + '/api/mcp', storage=storage,
        client_metadata=OAuthClientMetadata(client_name=name, redirect_uris=['http://127.0.0.1:18919/callback'],
            token_endpoint_auth_method='none', grant_types=['authorization_code', 'refresh_token'], response_types=['code'],
            scope=' '.join(requested_scopes)), redirect_handler=redirect, callback_handler=receive)
    results = []
    try:
        # Native SDK headers are deliberately retained on all machine endpoints.
        for modern in (False, True):
            async with httpx2.AsyncClient(auth=provider, timeout=30) as http:
                async with streamable_http_client(base + '/api/mcp', http_client=http) as (read, write):
                    async with ClientSession(read, write, read_timeout_seconds=30) as session:
                        results.append(await inspector(session, modern))
        assert storage.tokens and storage.tokens.refresh_token and storage.client
        first_refresh = storage.tokens.refresh_token
        # Advance the SDK's own expiry clock, then perform a real refresh over HTTP.
        provider.context.token_expiry_time = 1
        async with httpx2.AsyncClient(auth=provider, timeout=30) as http:
            async with streamable_http_client(base + '/api/mcp', http_client=http) as (read, write):
                async with ClientSession(read, write, read_timeout_seconds=30) as session:
                    results.append({'afterRefresh': True, **await inspector(session, False)})
        assert storage.tokens.refresh_token != first_refresh
        async with httpx2.AsyncClient(timeout=30) as http:
            # A replay must commit family revocation, including the latest access token.
            replay = await http.post(base + '/api/oauth/token', data={'grant_type': 'refresh_token', 'client_id': storage.client.client_id,
                'resource': base + '/api/mcp', 'refresh_token': first_refresh})
            assert replay.status_code == 400 and replay.json()['error'] == 'invalid_grant'
            rejected = await http.get(base + '/api/external/v1/list_products', headers={'Authorization': 'Bearer ' + storage.tokens.access_token})
            assert rejected.status_code == 401
        return {'state': 'PASSED', 'sdk': 'mcp-python', 'version': importlib.metadata.version('mcp'),
            'discovery': True, 'dynamicRegistration': True, 'powAndCsrfConsent': True, 'pkceS256': True,
            'rotatingRefresh': True, 'replayRevocation': True, 'results': results}
    finally:
        # Recover a just-created grant even if a later network assertion failed.
        for item in owner.request('/api/access/oauth')['authorizations']:
            if item['clientName'] == name:
                grants.add(item['id'])
        for grant in grants:
            owner.request('/api/access/oauth/' + grant + '/revoke', {})
        storage.tokens = None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='https://gold.mnnu.eu.org')
    args = parser.parse_args()
    owner = SessionClient(args.base_url)
    owner.login(os.environ['GOLD_SMOKE_USERNAME'], os.environ['GOLD_SMOKE_PASSWORD'])
    try:
        print(json.dumps(asyncio.run(verify(args.base_url.rstrip('/'), owner))))
    finally:
        owner.logout()


if __name__ == '__main__':
    main()
