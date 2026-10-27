"""Optional stdio-to-HTTP MCP adapter. Stdlib only; PAT is read from the environment."""
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36 Edg/154.0.0.0'


def header_name(value):
    if not isinstance(value, str):
        return None
    if value != value.strip() or any(ord(c) < 32 or ord(c) > 126 for c in value) or (value.startswith('=?base64?') and value.endswith('?=')):
        return '=?base64?' + base64.b64encode(value.encode('utf-8')).decode('ascii') + '?='
    return value


def validate_endpoint(endpoint):
    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.username or parsed.password or parsed.query or parsed.fragment or not parsed.hostname:
        raise ValueError('MCP endpoint must not contain credentials, query parameters or fragments')
    if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('127.0.0.1', 'localhost', '::1')):
        raise ValueError('MCP endpoint requires HTTPS, except on loopback')
    return endpoint


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # Never forward a bearer credential to a redirected host or a login page.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Bridge:
    def __init__(self, endpoint, secret):
        self.endpoint = validate_endpoint(endpoint)
        if not secret or not secret.startswith('gc_pat_'):
            raise ValueError('GOLD_CATCHER_PAT is missing or invalid')
        self.secret = secret
        self.version = '2025-03-26'
        self.opener = urllib.request.build_opener(NoRedirect())

    def forward(self, message):
        params = message.get('params') or {}
        meta = params.get('_meta') or {}
        version = meta.get('io.modelcontextprotocol/protocolVersion', self.version)
        headers = {'Authorization': 'Bearer ' + self.secret, 'Content-Type': 'application/json',
                   'Accept': 'application/json, text/event-stream', 'User-Agent': USER_AGENT,
                   'MCP-Protocol-Version': version}
        if 'io.modelcontextprotocol/protocolVersion' in meta:
            headers['Mcp-Method'] = message['method']
            if message['method'] in ('tools/call', 'prompts/get', 'resources/read'):
                name = header_name(params.get('name', params.get('uri')))
                if name is not None:
                    headers['Mcp-Name'] = name
        data = json.dumps(message, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        request = urllib.request.Request(self.endpoint, data=data, headers=headers, method='POST')
        try:
            response = self.opener.open(request, timeout=30)
        except urllib.error.HTTPError as failure:
            with failure:
                raw = failure.read(2_000_001)
                try:
                    packet = json.loads(raw)
                    if isinstance(packet, dict) and packet.get('jsonrpc') == '2.0' and isinstance(packet.get('error'), dict):
                        return packet
                except (ValueError, UnicodeError):
                    pass
            raise RuntimeError('MCP_HTTP_' + str(failure.code)) from None
        with response:
            if response.status == 202:
                return None
            raw = response.read(2_000_001)
            if len(raw) > 2_000_000:
                raise RuntimeError('MCP_RESPONSE_TOO_LARGE')
            packet = json.loads(raw)
            if not isinstance(packet, dict) or packet.get('jsonrpc') != '2.0':
                raise RuntimeError('INVALID_MCP_RESPONSE')
            if message.get('method') == 'initialize' and 'result' in packet:
                self.version = packet['result']['protocolVersion']
            return packet


def main():
    try:
        bridge = Bridge(os.environ.get('GOLD_CATCHER_MCP_URL', 'https://gold.mnnu.eu.org/api/mcp'), os.environ.get('GOLD_CATCHER_PAT', ''))
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1
    while raw := sys.stdin.buffer.readline(65537):
        message = None
        try:
            if len(raw) > 65536:
                while raw and not raw.endswith(b'\n'):
                    raw = sys.stdin.buffer.readline(65537)
                raise ValueError('MCP_STDIN_MESSAGE_TOO_LARGE')
            message = json.loads(raw)
            if not isinstance(message, dict) or message.get('jsonrpc') != '2.0' or not isinstance(message.get('method'), str):
                raise ValueError('INVALID_MCP_MESSAGE')
            packet = bridge.forward(message)
        except (ValueError, OSError, RuntimeError):
            # Never include credential values, HTTP bodies, URLs or exception representations in output.
            packet = {'jsonrpc': '2.0', 'id': message.get('id') if isinstance(message, dict) else None,
                      'error': {'code': -32603, 'message': 'MCP_TRANSPORT_FAILED; check endpoint, PAT, permission and HTTP availability'}}
        if packet is not None and (not isinstance(message, dict) or 'id' in message):
            print(json.dumps(packet, ensure_ascii=False, separators=(',', ':')), flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
