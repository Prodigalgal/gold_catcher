"""Official MCP SDK OAuth discovery -> DCR -> PoW/consent -> PKCE -> refresh -> revoke.

Uses disposable backing PATs and OAuth grants, always revokes them, and never calls a model.
Consent runs in a separate anonymous browser session and can only use the submitted PAT.
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
    backing = owner.request('/api/access/tokens', {'name': 'Temporary OAuth backing PAT ' + secrets.token_hex(4),
        'scopes': requested_scopes, 'expiresInDays': 1, 'requestsPerMinute': 600})
    anonymous = SessionClient(base)
    browser = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPCookieProcessor(anonymous.cookies))

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
        pending = anonymous.request('/api/oauth/consent?request=' + urllib.parse.quote(ticket))
        assert pending['clientName'] == name and pending['authenticationMethod'] == 'PAT'
        anonymous.csrf = {'csrfHeader': pending['csrfHeader'], 'csrfToken': pending['csrfToken']}
        # The source PAT determines authority; no account sign-in or scope picker is used.
        assert set(requested_scopes) <= set(pending['scopes'])
        preview = anonymous.request('/api/oauth/consent', {'request': ticket, 'decision': 'inspect', 'token': backing['secret']})
        assert set(preview['scopes']) == set(requested_scopes) and backing['secret'] not in json.dumps(preview)
        result = anonymous.request('/api/oauth/consent', {'request': ticket, 'decision': 'approve', 'token': backing['secret']})
        try:
            anonymous.request('/api/session')
            raise AssertionError('PAT consent must never create an Owner login')
        except urllib.error.HTTPError as rejected:
            with rejected:
                assert rejected.code == 401
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
                        if not modern:
                            path = '/api/access/tokens/' + backing['token']['id'] + '/scopes'
                            owner.request(path, {'scopes': requested_scopes, 'allowedTools': ['list_products']})
                            assert [tool.name for tool in (await session.list_tools()).tools] == ['list_products']
                            owner.request(path, {'scopes': requested_scopes, 'allowedTools': []})
                            assert not (await session.list_tools()).tools
                            owner.request(path, {'scopes': requested_scopes, 'allowedTools': None})
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
        # A fresh grant from the same PAT also dies when its backing PAT is revoked.
        followup_storage = MemoryStorage()
        followup = OAuthClientProvider(server_url=base + '/api/mcp', storage=followup_storage,
            client_metadata=OAuthClientMetadata(client_name=name, redirect_uris=['http://127.0.0.1:18919/callback'],
                token_endpoint_auth_method='none', grant_types=['authorization_code', 'refresh_token'], response_types=['code'],
                scope=' '.join(requested_scopes)), redirect_handler=redirect, callback_handler=receive)
        async with httpx2.AsyncClient(auth=followup, timeout=30) as http:
            async with streamable_http_client(base + '/api/mcp', http_client=http) as (read, write):
                async with ClientSession(read, write, read_timeout_seconds=30) as session:
                    await session.initialize()
                    assert (await session.list_tools()).tools
        owner.request('/api/access/tokens/' + backing['token']['id'] + '/revoke', {})
        async with httpx2.AsyncClient(timeout=30) as http:
            denied = await http.get(base + '/api/external/v1', headers={'Authorization': 'Bearer ' + followup_storage.tokens.access_token})
            assert denied.status_code == 401
            rejected = await http.post(base + '/api/oauth/token', data={'grant_type': 'refresh_token', 'client_id': followup_storage.client.client_id,
                'resource': base + '/api/mcp', 'refresh_token': followup_storage.tokens.refresh_token})
            assert rejected.status_code == 400 and rejected.json()['error'] == 'invalid_grant'
        return {'state': 'PASSED', 'sdk': 'mcp-python', 'version': importlib.metadata.version('mcp'),
            'discovery': True, 'dynamicRegistration': True, 'anonymousPatAndCsrfConsent': True, 'ownerSessionNotCreated': True, 'pkceS256': True,
            'dynamicPatToolPermissions': True, 'parentPatRevocation': True, 'rotatingRefresh': True, 'replayRevocation': True, 'results': results}
    finally:
        # Recover a just-created grant even if a later network assertion failed.
        for item in owner.request('/api/access/oauth')['authorizations']:
            if item['clientName'] == name:
                grants.add(item['id'])
        for grant in grants:
            owner.request('/api/access/oauth/' + grant + '/revoke', {})
        owner.request('/api/access/tokens/' + backing['token']['id'] + '/revoke', {})
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
