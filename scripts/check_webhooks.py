#!/usr/bin/env python3
"""Check webhook retry/acknowledgement with synthetic events and a local USB receiver.
Requires a debug phone with Run API on and webhooks off; restores configuration.
"""
import argparse
import http.client
import http.server
import json
import secrets
import subprocess
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url')
    parser.add_argument('--adb-serial', required=True)
    args = parser.parse_args()
    adb = ['adb', '-s', args.adb_serial]
    prefs = ET.fromstring(subprocess.check_output(adb + ['shell', 'run-as', 'dev.phoneapi', 'cat', 'shared_prefs/api.xml']))
    key = next(n.text for n in prefs if n.get('name') == 'token')
    origin = urllib.parse.urlsplit(args.url)
    assert origin.scheme == 'http' and origin.hostname

    def request(path, method='GET', data=None):
        conn = http.client.HTTPConnection(origin.hostname, origin.port or 80, timeout=8)
        try:
            headers = {'Authorization': 'Bearer ' + key}
            if data is not None:
                headers['Content-Type'] = 'application/json'
            conn.request(method, path, body=json.dumps(data).encode() if data is not None else b'' if method == 'POST' else None, headers=headers)
            response = conn.getresponse()
            payload = json.loads(response.read())
            assert response.status in (200, 202), f'HTTP {response.status} at {path}'
            return payload
        finally:
            conn.close()

    old = request('/webhooks')
    assert not old['enabled'] and not old['hasToken'] and old['pending'] == 0, 'Leave existing webhook configuration untouched; this check requires delivery off, no token and an empty queue'
    token = secrets.token_urlsafe(24)
    arrivals = []
    failures = []
    accept = [False]

    class Receiver(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            try:
                assert self.path == '/events'
                assert self.headers.get('Authorization') == 'Bearer ' + token
                event = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                assert event['type'] == 'test'
                assert self.headers['X-Phone-API-Event-ID'] == event['id']
                assert self.headers['X-Phone-API-Event'] == 'test'
                arrivals.append(event['id'])
                self.send_response(204 if accept[0] else 503)
                self.send_header('Content-Length', '0')
                self.end_headers()
            except Exception as error:
                failures.append(type(error).__name__)
                self.send_error(500)
        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Receiver)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    test_url = 'http://127.0.0.1:18766/events'
    subprocess.check_call(adb + ['reverse', 'tcp:18766', f'tcp:{server.server_port}'], stdout=subprocess.DEVNULL)
    configured = False
    try:
        request('/webhooks', 'PUT', {'url': test_url, 'token': token, 'enabled': True, 'events': [], 'packages': []})
        configured = True
        event = request('/webhooks/test', 'POST')
        assert event['queued']
        subprocess.check_call(adb + ['shell', 'cmd', 'jobscheduler', 'run', '-f', 'dev.phoneapi', '8765'], stdout=subprocess.DEVNULL)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            status = request('/webhooks')
            if arrivals and status['lastError']:
                assert status['pending'] == 1
                break
            time.sleep(.5)
        else:
            raise AssertionError(f'Expected a failed delivery kept in the queue: arrivals={len(arrivals)}, pending={status["pending"]}, delivered={status["delivered"]}, error={status["lastError"]}, receiver_errors={failures}')
        accept[0] = True
        subprocess.check_call(adb + ['shell', 'cmd', 'jobscheduler', 'run', '-f', 'dev.phoneapi', '8765'], stdout=subprocess.DEVNULL)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            status = request('/webhooks')
            if len(arrivals) >= 2 and status['pending'] == 0:
                assert len(set(arrivals)) == 1 and arrivals[0] == event['id']
                assert status['delivered'] == old['delivered'] + 1
                assert status['lastError'] == '' and not failures
                print('PASS: authenticated synthetic webhook, failed-delivery retention, stable event ID on retry and acknowledgement. No SMS sent.')
                break
            time.sleep(.5)
        else:
            raise AssertionError('Expected successful retry acknowledgement')
    finally:
        if configured:
            current = request('/webhooks')
            if current['url'] == test_url:
                request('/webhooks', 'PUT', {**{field: old[field] for field in ['url', 'enabled', 'events', 'packages']}, 'token': ''})
            else:
                print('Webhook settings changed during the check; left the new settings untouched.')
        subprocess.run(adb + ['reverse', '--remove', 'tcp:18766'], stdout=subprocess.DEVNULL, check=True)
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()
