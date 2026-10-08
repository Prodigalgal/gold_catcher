"""Cookie/session client using the same single-use PoW and CSRF flow as the browser."""
import hashlib
import http.cookiejar
import json
import time
import urllib.request

USER_AGENT = 'GoldCatcher-release-check/0.3'


class SessionClient:
    def __init__(self, base_url):
        self.base = base_url.rstrip('/')
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = {}

    def request(self, path, body=None, token=None):
        headers = {'Accept': 'application/json', 'User-Agent': USER_AGENT}
        data = None
        if body is not None:
            headers['Content-Type'] = 'application/json'
            current = token if token is not None else self.csrf
            if current:
                headers[current['csrfHeader']] = current['csrfToken']
            data = json.dumps(body, ensure_ascii=False).encode('utf-8')
        with self.opener.open(urllib.request.Request(self.base + path, data=data, headers=headers), timeout=20) as response:
            return json.load(response)

    def login(self, username, password):
        packet = self.request('/api/auth/challenge')
        challenge = packet['challenge']
        bits = challenge['difficulty']
        if challenge['algorithm'] != 'SHA-256' or not 8 <= bits <= 24:
            raise ValueError('unsupported PoW challenge')
        prefix = f'GC1:{challenge["id"]}:{challenge["nonce"]}:'
        deadline = time.monotonic() + min(115, challenge['expiresInSeconds'] - 2)
        solution = 0
        while time.monotonic() < deadline:
            digest = hashlib.sha256((prefix + str(solution)).encode()).digest()
            if int.from_bytes(digest, 'big') >> (256 - bits) == 0:
                break
            solution += 1
        else:
            raise TimeoutError('PoW challenge expired')
        self.request('/api/auth/login', {'username': username, 'password': password,
            'challengeId': challenge['id'], 'solution': str(solution)}, packet)
        self.csrf = self.request('/api/session')
        if self.csrf['username'] != username:
            raise ValueError('session identity mismatch')
        return self.csrf['username']

    def logout(self):
        self.request('/api/auth/logout', {})
        self.csrf = {}
