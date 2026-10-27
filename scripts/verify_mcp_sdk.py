"""Acceptance with the official Python MCP SDK (optional test dependency). No LLM calls."""
import argparse
import asyncio
import importlib.metadata
import json
import os
import sys
from pathlib import Path
import httpx2
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.client.streamable_http import streamable_http_client
from auth_client import SessionClient


async def inspect(session, modern):
    connected = await session.discover() if modern else await session.initialize()
    tools = await session.list_tools()
    assert len(tools.tools) == 12
    products = await session.call_tool('list_products', {})
    assert not products.is_error and len(products.structured_content['products']) == 2
    candles = await session.call_tool('get_candles', {'symbol': 'XAU_USDT', 'frame': '15', 'limit': 3})
    assert not candles.is_error and candles.structured_content['frame'] == '15'
    return {'protocol': connected.supported_versions[0] if modern else connected.protocol_version, 'tools': len(tools.tools), 'calls': 2}


async def verify(base, secret, include_stdio):
    results = []
    for modern in (False, True):
        # Use SDK's own User-Agent/headers to detect gateway compatibility failures.
        async with httpx2.AsyncClient(headers={'Authorization': 'Bearer ' + secret}, timeout=30) as http:
            async with streamable_http_client(base + '/api/mcp', http_client=http) as (read, write):
                async with ClientSession(read, write, read_timeout_seconds=30) as session:
                    results.append(await inspect(session, modern))
    if include_stdio:
        server = StdioServerParameters(command=sys.executable, args=[str(Path(__file__).with_name('gold_catcher_mcp.py'))],
            env={**os.environ, 'GOLD_CATCHER_MCP_URL': base + '/api/mcp', 'GOLD_CATCHER_PAT': secret})
        async with stdio_client(server) as (read, write):
            async with ClientSession(read, write, read_timeout_seconds=30) as session:
                results.append({'transport': 'stdio-bridge', **await inspect(session, False)})
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='https://gold.mnnu.eu.org')
    parser.add_argument('--stdio', action='store_true')
    args = parser.parse_args()
    base = args.base_url.rstrip('/')
    owner = SessionClient(base)
    owner.login(os.environ['GOLD_SMOKE_USERNAME'], os.environ['GOLD_SMOKE_PASSWORD'])
    token_id = None
    try:
        token = owner.request('/api/access/tokens', {'name': 'Temporary official SDK acceptance', 'scopes': ['market:read', 'information:read', 'research:read'], 'expiresInDays': 1, 'requestsPerMinute': 120})
        token_id = token['token']['id']
        results = asyncio.run(verify(base, token['secret'], args.stdio))
        print(json.dumps({'state': 'PASSED', 'sdk': 'mcp-python', 'version': importlib.metadata.version('mcp'), 'results': results}))
    finally:
        try:
            if token_id:
                owner.request('/api/access/tokens/' + token_id + '/revoke', {})
        finally:
            owner.logout()


if __name__ == '__main__':
    main()
