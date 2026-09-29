#!/usr/bin/env python3
"""Check enabled debug-service recovery on a USB-connected phone; no SMS sent.
Temporarily opens Home and terminates only Phone API's process, never force-stops it.
"""
import argparse
import http.client
import json
import subprocess
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
    token = next(n.text for n in prefs if n.get('name') == 'token')
    assert any(n.get('name') == 'enabled' and n.get('value') == 'true' for n in prefs), 'Enable Run API first'
    origin = urllib.parse.urlsplit(args.url)
    assert origin.scheme == 'http' and origin.hostname and not origin.username

    def health():
        conn = http.client.HTTPConnection(origin.hostname, origin.port or 80, timeout=3)
        try:
            conn.request('GET', '/health', headers={'Authorization': 'Bearer ' + token})
            response = conn.getresponse()
            assert response.status == 200
            result = json.loads(response.read())
            assert result['backgroundEnabled'] and len(result['listeners']) >= 2
            return result
        finally:
            conn.close()

    health()
    subprocess.check_call(adb + ['shell', 'input', 'keyevent', 'KEYCODE_HOME'])
    time.sleep(2)
    health()
    old = subprocess.check_output(adb + ['shell', 'pidof', 'dev.phoneapi']).decode().strip()
    assert old.isdigit(), 'Expected one Phone API process'
    subprocess.check_call(adb + ['shell', 'run-as', 'dev.phoneapi', 'kill', '-9', old])
    deadline = time.monotonic() + 50
    try:
        while time.monotonic() < deadline:
            time.sleep(1)
            try:
                status = health()
                new = subprocess.check_output(adb + ['shell', 'pidof', 'dev.phoneapi']).decode().strip()
                if new and new != old:
                    print(f"PASS: background access and sticky process recovery; uptime {status['uptimeSeconds']}s. No SMS sent.")
                    break
            except (OSError, http.client.HTTPException):
                pass
        else:
            raise AssertionError('Service did not recover within 50 seconds')
    finally:
        subprocess.run(adb + ['shell', 'am', 'start', '-n', 'dev.phoneapi/.MainActivity'], stdout=subprocess.DEVNULL, check=True)


if __name__ == '__main__':
    main()
