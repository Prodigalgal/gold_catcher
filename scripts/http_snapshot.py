"""Bounded retries for read-only JSON snapshots, including a clean EOF in an unfinished JSON value."""
import http.client
import json
import time
import urllib.error


def get_snapshot(client, path, attempts=3, sleep=time.sleep):
    if not 1 <= attempts <= 3:
        raise ValueError('snapshot attempts must be between 1 and 3')
    for attempt in range(attempts):
        try:
            # No body argument: SessionClient always uses GET on this path.
            return client.request(path)
        except (http.client.IncompleteRead, ConnectionResetError,
                urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            if attempt == attempts - 1:
                raise
            sleep(1)
