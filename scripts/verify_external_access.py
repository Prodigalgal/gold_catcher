"""Live PAT/MCP acceptance. Creates only temporary access tokens and always revokes them."""
import argparse
import json
import os
import urllib.error
import urllib.request
from auth_client import SessionClient, USER_AGENT
from gold_catcher_mcp import NoRedirect


def verify(base, username, password):
    client = SessionClient(base)
    client.login(username, password)
    created = []
    opener = urllib.request.build_opener(NoRedirect())
    sequence = 0
    checks = []

    def request(path, secret, body=None, headers=None):
        h = {'User-Agent': USER_AGENT, 'Accept': 'application/json, text/event-stream'}
        if secret:
            h['Authorization'] = 'Bearer ' + secret
        if headers:
            h.update(headers)
        data = None
        if body is not None:
            h['Content-Type'] = 'application/json'
            data = json.dumps(body).encode('utf-8')
        try:
            with opener.open(urllib.request.Request(base + path, data=data, headers=h), timeout=30) as response:
                assert not response.headers.get('Set-Cookie'), 'external endpoint created a session'
                raw = response.read(2_000_001)
                assert len(raw) <= 2_000_000, 'unbounded external response'
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            with error:
                raw = error.read(2_000_001)
                try:
                    packet = json.loads(raw)
                except ValueError:
                    packet = None
                return error.code, packet

    def rpc(secret, method, params=None, modern=False):
        nonlocal sequence
        sequence += 1
        params = dict(params or {})
        headers = {'MCP-Protocol-Version': '2026-07-28' if modern else '2025-11-25'}
        if modern:
            params['_meta'] = {'io.modelcontextprotocol/protocolVersion': '2026-07-28',
                               'io.modelcontextprotocol/clientInfo': {'name': 'gold-external-acceptance', 'version': '1'},
                               'io.modelcontextprotocol/clientCapabilities': {}}
            headers['Mcp-Method'] = method
            if method == 'tools/call':
                headers['Mcp-Name'] = params['name']
        status, packet = request('/api/mcp', secret, {'jsonrpc': '2.0', 'id': sequence, 'method': method, 'params': params}, headers)
        assert isinstance(packet, dict) and packet.get('jsonrpc') == '2.0', ('not MCP JSON', status)
        assert packet.get('id') == sequence
        return status, packet

    def call(secret, name, arguments=None):
        status, packet = rpc(secret, 'tools/call', {'name': name, 'arguments': arguments or {}})
        assert status == 200 and 'result' in packet and not packet['result'].get('isError'), (name, status, packet.get('error'))
        result = packet['result']
        assert json.loads(result['content'][0]['text']) == result['structuredContent']
        checks.append(name)
        return result['structuredContent']

    try:
        full = client.request('/api/access/tokens', {'name': 'Temporary MCP acceptance', 'scopes': ['market:read', 'information:read', 'research:read'], 'expiresInDays': 1, 'requestsPerMinute': 600})
        created.append(full['token']['id'])
        secret = full['secret']
        permanent = client.request('/api/access/tokens/' + full['token']['id'] + '/permanent', {})
        assert permanent['expiresAt'] is None and permanent['state'] == 'ACTIVE'
        inventory = client.request('/api/access/tokens')
        assert secret not in json.dumps(inventory)
        status, packet = rpc(secret, 'initialize', {'protocolVersion': '2025-11-25', 'capabilities': {}, 'clientInfo': {'name': 'acceptance', 'version': '1'}})
        assert status == 200 and packet['result']['protocolVersion'] == '2025-11-25'
        status, _ = request('/api/mcp', secret, {'jsonrpc': '2.0', 'method': 'notifications/initialized'}, {'MCP-Protocol-Version': '2025-11-25'})
        assert status == 202
        status, packet = rpc(secret, 'tools/list')
        assert status == 200 and len(packet['result']['tools']) == 12
        assert all(t['annotations']['readOnlyHint'] for t in packet['result']['tools'])
        status, packet = rpc(secret, 'server/discover', modern=True)
        assert status == 200 and '2026-07-28' in packet['result']['supportedVersions']
        assert packet['result']['resultType'] == 'complete'
        status, packet = rpc(secret, 'tools/call', {'name': 'list_products'}, modern=True)
        assert status == 200 and packet['result']['structuredContent']['readOnly'] is True
        products = call(secret, 'list_products')
        assert len(products['products']) == 2
        market_states = {}
        for symbol in ('XAU_USDT', 'GATE:XAUUSD'):
            market_states[symbol] = call(secret, 'get_quote', {'symbol': symbol})['state']
            for frame in products['frames']:
                candles = call(secret, 'get_candles', {'symbol': symbol, 'frame': frame, 'limit': 3})
                assert len(candles['candles']) <= 3
                assert all(isinstance(row['close'], str) for row in candles['candles'])
                assert candles['coverage'].startswith('CURRENT_COLLECTOR_WINDOW_ONLY')
            call(secret, 'get_indicators', {'symbol': symbol})
        call(secret, 'get_market_depth')
        news = call(secret, 'search_news', {'limit': 2})
        article_checked = bool(news['items'])
        if article_checked:
            for representation in ('summary', 'original'):
                call(secret, 'get_news', {'id': news['items'][0]['id'], 'representation': representation, 'maxCharacters': 500})
        else:
            status, packet = rpc(secret, 'tools/call', {'name': 'get_news', 'arguments': {'id': 'acceptance-no-record'}})
            assert status == 200 and packet['result']['isError'] is True
        call(secret, 'get_calendar', {'limit': 2})
        call(secret, 'get_drivers')
        call(secret, 'get_source_status')
        research = call(secret, 'list_research', {'limit': 1})
        research_checked = bool(research['items'])
        if research_checked:
            run = call(secret, 'get_research', {'id': research['items'][0]['id'], 'limit': 2})
            assert 'routeConfiguration' not in run and 'evidence' not in run
            assert all('budget' not in (m.get('answer') or {}) and 'raw' not in (m.get('answer') or {}) for m in run['messages'])
        else:
            status, packet = rpc(secret, 'tools/call', {'name': 'get_research', 'arguments': {'id': 'acceptance-no-record'}})
            assert status == 200 and packet['result']['isError'] is True
        status, rest = request('/api/external/v1/get_candles?frame=15&limit=2', secret)
        assert status == 200 and rest['frame'] == '15'
        for form in ('chat', 'responses'):
            status, definitions = request('/api/external/v1/openai/tools?format=' + form, secret)
            assert status == 200 and len(definitions['tools']) == 12
        chat_call = {'id': 'call_acceptance', 'type': 'function', 'function': {'name': 'list_products', 'arguments': '{}'}}
        status, converted = request('/api/external/v1/openai/tool', secret, {'tool_call': chat_call})
        assert status == 200 and converted['message']['tool_call_id'] == 'call_acceptance'
        assert len(json.loads(converted['message']['content'])['products']) == 2
        response_call = {'type': 'function_call', 'call_id': 'call_response_acceptance', 'name': 'list_products', 'arguments': '{}'}
        status, converted = request('/api/external/v1/openai/tool', secret, {'tool_call': response_call})
        assert status == 200 and converted['responseOutput']['call_id'] == 'call_response_acceptance'
        limited = client.request('/api/access/tokens', {'name': 'Temporary scope acceptance', 'scopes': ['market:read'], 'expiresInDays': 0, 'requestsPerMinute': 120})
        created.append(limited['token']['id'])
        assert limited['token']['expiresAt'] is None
        status, packet = rpc(limited['secret'], 'tools/list')
        assert status == 200 and len(packet['result']['tools']) == 5
        assert rpc(limited['secret'], 'tools/call', {'name': 'search_news'})[0] == 403
        for path in ('/api/access/tokens', '/api/ai/configuration', '/api/session'):
            assert request(path, secret)[0] == 401
        assert request('/api/mcp', None, {'jsonrpc': '2.0', 'id': 1, 'method': 'ping'})[0] == 401
        assert request('/api/mcp', secret, {'jsonrpc': '2.0', 'id': 1, 'method': 'ping'}, {'Origin': 'https://invalid.example'})[0] == 403
        client.request('/api/access/tokens/' + full['token']['id'] + '/revoke', {})
        assert request('/api/external/v1/list_products', secret)[0] == 401
        return {'state': 'PASSED', 'toolsDiscovered': 12, 'successfulToolCalls': len(checks), 'uniqueSuccessfulTools': sorted(set(checks)),
                'realArticleChecked': article_checked, 'realResearchChecked': research_checked, 'marketStates': market_states,
                'modernAndLegacy': True, 'openAiChatAndResponsesAdapters': True, 'scopeAndOwnerIsolation': True, 'revocationVerified': True, 'permanentPatVerified': True}
    finally:
        failures = []
        for token_id in created:
            try:
                client.request('/api/access/tokens/' + token_id + '/revoke', {})
            except Exception:
                failures.append(token_id)
        client.logout()
        if failures:
            raise RuntimeError('Temporary PAT cleanup failed for ids: ' + ','.join(failures))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='https://gold.mnnu.eu.org')
    args = parser.parse_args()
    result = verify(args.base_url.rstrip('/'), os.environ['GOLD_SMOKE_USERNAME'], os.environ['GOLD_SMOKE_PASSWORD'])
    print(json.dumps(result, ensure_ascii=False))
