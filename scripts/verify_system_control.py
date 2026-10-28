"""Native MCP acceptance for scoped operations. Temporary PATs are always revoked.

Default: status/config reads and disposable token lifecycle only, no model calls.
--simulation: create a minimal far-away INTERNAL limit order, replay, modify and cancel.
--rejection: verify an invalid virtual order is a failed MCP result (isolated CI only).
"""
import argparse
import asyncio
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR
import importlib.metadata
import json
import os
import uuid
import httpx2
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from auth_client import SessionClient

SCOPES = ['market:read', 'information:read', 'research:read', 'simulation:read', 'configuration:read', 'system:read',
          'simulation:write', 'research:write', 'information:write', 'configuration:write', 'access:manage']
CHILD_PREFIX = 'Temporary MCP control child '


def key():
    return 'accept-' + uuid.uuid4().hex


async def inspect(session, modern, owner=None):
    connected = await session.discover() if modern else await session.initialize()
    catalog = await session.list_tools()
    names = {tool.name for tool in catalog.tools}
    assert len(names) > 50 and {'place_simulation_order', 'start_research', 'update_ai_configuration', 'get_operation'} <= names
    for tool in catalog.tools:
        value = tool.model_dump(by_alias=True)
        if not value['annotations']['readOnlyHint']:
            assert 'request_key' in value['inputSchema']['required']
    calls = 0

    async def invoke(name, arguments=None):
        nonlocal calls
        answer = await session.call_tool(name, arguments or {})
        calls += 1
        assert not answer.is_error, name
        return answer.structured_content

    for name in ['get_simulation_account', 'list_simulation_orders', 'get_ai_configuration', 'get_source_configuration',
                 'get_system_status', 'get_api_performance', 'get_source_proxy_status', 'get_research_schedule',
                 'get_compression_status', 'get_model_status', 'get_market_cost_configuration', 'list_access_tokens', 'list_oauth_authorizations']:
        data = await invoke(name)
        if name == 'get_ai_configuration':
            assert {p['id'] for p in data['providers']} <= {'mimo', 'glm', 'longcat', 'minmax', 'grok', 'grok_web'}
    arguments = {'name': CHILD_PREFIX + uuid.uuid4().hex[:8], 'scopes': ['market:read'], 'expiresInDays': 1, 'request_key': key()}
    created = await invoke('create_access_token', arguments)
    assert created['ok'] and created['result']['secret'].startswith('gc_pat_')
    child_id = created['result']['token']['id']
    child_secret = created['result']['secret']
    try:
        replay = await invoke('create_access_token', arguments)
        assert replay['replayed'] and replay['operationId'] == created['operationId'] and replay['result']['secret'] is None
        receipt = await invoke('get_operation', {'id': created['operationId']})
        assert receipt['result']['secret'] is None and receipt['state'] == 'COMPLETED'
        permanent = await invoke('make_access_token_permanent', {'id': child_id, 'request_key': key()})
        assert permanent['ok'] and permanent['result']['expiresAt'] is None
        changed = await invoke('update_access_token_scopes', {'id': child_id, 'scopes': ['market:read', 'simulation:read'], 'request_key': key()})
        assert changed['ok'] and changed['result']['scopes'] == ['market:read', 'simulation:read']
        if owner:
            # Reuse both the published key and the native SDK session while Owner edits grants.
            async with httpx2.AsyncClient(headers={'Authorization': 'Bearer ' + child_secret}, timeout=30) as http:
                async with streamable_http_client(owner.base + '/api/mcp', http_client=http) as (read, write):
                    async with ClientSession(read, write, read_timeout_seconds=30) as child:
                        await child.initialize()
                        assert len((await child.list_tools()).tools) > 5
                        owner.request('/api/access/tokens/' + child_id + '/scopes', {'scopes': SCOPES, 'allowedTools': ['get_quote']})
                        assert [tool.name for tool in (await child.list_tools()).tools] == ['get_quote']
                        owner.request('/api/access/tokens/' + child_id + '/scopes', {'scopes': SCOPES, 'allowedTools': ['get_quote', 'get_candles']})
                        assert {tool.name for tool in (await child.list_tools()).tools} == {'get_quote', 'get_candles'}
                        owner.request('/api/access/tokens/' + child_id + '/scopes', {'scopes': SCOPES, 'allowedTools': []})
                        assert not (await child.list_tools()).tools
                        owner.request('/api/access/tokens/' + child_id + '/scopes', {'scopes': SCOPES, 'allowedTools': None})
                        assert len((await child.list_tools()).tools) == len(names)
    finally:
        revoked = await invoke('revoke_access_token', {'id': child_id, 'request_key': key()})
        assert revoked['ok'] and revoked['result']['state'] == 'REVOKED'
    return {'protocol': connected.supported_versions[0] if modern else connected.protocol_version,
            'tools': len(names), 'calls': calls, 'writeRetries': 'REPLAYED_ONCE', 'credentialReceipts': 'REDACTED',
            'existingKeyGrantsEdited': owner is not None}


async def simulate(session, expect_rejection):
    await session.initialize()
    now = datetime.now(timezone.utc)
    args = {'request_key': key(), 'symbol': 'XAU_USDT', 'side': 'BUY', 'type': 'LIMIT', 'quantity': '0', 'leverage': '1',
            'entryPrice': '1', 'expiresAt': (now + timedelta(minutes=3)).isoformat(), 'holdUntil': (now + timedelta(minutes=5)).isoformat(),
            'riskAssessment': 'MCP acceptance: minimal internal pending order, immediately cancel; no real exchange execution.',
            'invalidate': 'Acceptance completes or quote/rules/costs unavailable.'}
    if not expect_rejection:
        quote = (await session.call_tool('get_quote', {'symbol': 'XAU_USDT'})).structured_content
        assert quote['state'] == 'LIVE' and quote.get('quote') and quote.get('rules'), 'Live quote/rules required for simulation acceptance'
        rules = quote['rules']
        tick, step = Decimal(rules['tickSize']), Decimal(rules['qtyStep'])
        price = (Decimal(quote['quote']['bid']) * Decimal('0.5') / tick).to_integral_value(rounding=ROUND_FLOOR) * tick
        minimum = max(Decimal(rules['minQty']), Decimal(rules['minNotional']) / price)
        quantity = (minimum / step).to_integral_value(rounding=ROUND_CEILING) * step
        args.update(entryPrice=format(price, 'f'), quantity=format(quantity, 'f'), leverage=rules['minLeverage'])
    created = await session.call_tool('place_simulation_order', args)
    data = created.structured_content
    if expect_rejection:
        assert created.is_error and not data['ok'] and data['result']['status'] == 'REJECTED'
        return {'state': 'REJECTED_CORRECTLY', 'reason': data['result']['rejection']}
    assert not created.is_error and data['ok'] and data['result']['status'] == 'PENDING', data['result'].get('rejection')
    order_id = data['result']['id']
    try:
        replay = (await session.call_tool('place_simulation_order', args)).structured_content
        assert replay['replayed'] and replay['result']['id'] == order_id
        modified = await session.call_tool('modify_simulation_order', {'id': order_id, 'holdUntil': (now + timedelta(minutes=6)).isoformat(), 'request_key': key()})
        assert not modified.is_error and modified.structured_content['ok']
    finally:
        canceled = await session.call_tool('cancel_simulation_order', {'id': order_id, 'request_key': key()})
        assert not canceled.is_error and canceled.structured_content['result']['status'] == 'CANCELED'
    final = (await session.call_tool('get_simulation_order', {'id': order_id})).structured_content
    events = (await session.call_tool('get_simulation_order_events', {'id': order_id})).structured_content
    assert final['status'] == 'CANCELED' and Decimal(final['filled']) == 0 and events['items']
    return {'state': 'PENDING_REPLAY_MODIFY_CANCEL_PASSED', 'filled': final['filled'], 'events': len(events['items']), 'internalOnly': True}


async def verify(base, secret, simulation, rejection, owner):
    results = []
    for modern in (False, True):
        async with httpx2.AsyncClient(headers={'Authorization': 'Bearer ' + secret}, timeout=90) as http:
            async with streamable_http_client(base + '/api/mcp', http_client=http) as (read, write):
                async with ClientSession(read, write, read_timeout_seconds=90) as session:
                    results.append(await inspect(session, modern, owner))
    if simulation or rejection:
        async with httpx2.AsyncClient(headers={'Authorization': 'Bearer ' + secret}, timeout=90) as http:
            async with streamable_http_client(base + '/api/mcp', http_client=http) as (read, write):
                async with ClientSession(read, write, read_timeout_seconds=90) as session:
                    results.append({'simulation': await simulate(session, rejection)})
    return results


async def verify_adapters(base, secret):
    arguments = {'name': CHILD_PREFIX + uuid.uuid4().hex[:8], 'scopes': ['market:read'], 'allowedTools': ['get_quote'], 'request_key': key()}
    async with httpx2.AsyncClient(headers={'Authorization': 'Bearer ' + secret}, timeout=30) as http:
        created = await http.post(base + '/api/external/v1', json={'name': 'create_access_token', 'arguments': arguments})
        created.raise_for_status()
        result = created.json()
        assert result['ok'] and not result['replayed']
        child = result['result']['token']['id']
        try:
            replay = await http.post(base + '/api/external/v1/openai/tool', json={'tool_call': {'id': 'adapter_replay', 'type': 'function',
                'function': {'name': 'create_access_token', 'arguments': json.dumps(arguments)}}})
            replay.raise_for_status()
            replayed = json.loads(replay.json()['message']['content'])
            assert not replay.json()['isError'] and replayed['replayed'] and replayed['operationId'] == result['operationId'] and replayed['result']['secret'] is None
            async with httpx2.AsyncClient(headers={'Authorization': 'Bearer ' + result['result']['secret']}, timeout=30) as limited:
                for path, body in [('/api/external/v1', {'name': 'get_candles', 'arguments': {}}),
                                   ('/api/external/v1/openai/tool', {'tool_call': {'type': 'function_call', 'call_id': 'denied', 'name': 'get_candles', 'arguments': '{}'}})]:
                    denied = await limited.post(base + path, json=body)
                    assert denied.status_code == 403 and denied.json()['error'] == 'TOOL_NOT_GRANTED'
        finally:
            response = await http.post(base + '/api/external/v1', json={'name': 'revoke_access_token', 'arguments': {'id': child, 'request_key': key()}})
            response.raise_for_status()
            assert response.json()['ok']
    return {'crossTransportRetry': 'REPLAYED_ONCE', 'restAndOpenAiToolGrants': 'ENFORCED'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='https://gold.mnnu.eu.org')
    parser.add_argument('--simulation', action='store_true')
    parser.add_argument('--rejection', action='store_true')
    parser.add_argument('--oauth', action='store_true')
    args = parser.parse_args()
    owner = SessionClient(args.base_url)
    owner.login(os.environ['GOLD_SMOKE_USERNAME'], os.environ['GOLD_SMOKE_PASSWORD'])
    token_id = None
    try:
        token = owner.request('/api/access/tokens', {'name': 'Temporary full MCP acceptance', 'scopes': SCOPES, 'expiresInDays': 1, 'requestsPerMinute': 600})
        token_id = token['token']['id']
        results = asyncio.run(verify(args.base_url.rstrip('/'), token['secret'], args.simulation, args.rejection, owner))
        adapters = asyncio.run(verify_adapters(args.base_url.rstrip('/'), token['secret']))
        oauth = None
        if args.oauth:
            from verify_oauth_sdk import verify as verify_oauth
            oauth = asyncio.run(verify_oauth(args.base_url.rstrip('/'), owner, scopes=SCOPES, inspector=lambda session, modern: inspect(session, modern, owner)))
        print(json.dumps({'state': 'PASSED', 'sdk': 'mcp-python', 'version': importlib.metadata.version('mcp'), 'results': results, 'adapters': adapters, 'oauthControl': oauth}))
    finally:
        try:
            # Also cleans up if a tool call lost its response after the token was created.
            for row in owner.request('/api/access/tokens')['tokens']:
                if row['id'] == token_id or row['name'].startswith(CHILD_PREFIX) and row['state'] == 'ACTIVE':
                    owner.request('/api/access/tokens/' + row['id'] + '/revoke', {})
        finally:
            owner.logout()


if __name__ == '__main__':
    main()
