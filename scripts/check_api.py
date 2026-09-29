#!/usr/bin/env python3
"""Check a running Phone API without sending an SMS or printing private data."""
import argparse
import base64
import http.client
import json
import os
import subprocess
import urllib.parse
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url", help="For example http://127.0.0.1:18765 or http://<tailnet-ip>:8765")
    parser.add_argument("--adb-serial", help="Read the debug app's token through ADB instead of API_TOKEN")
    args = parser.parse_args()
    token = os.environ.get("API_TOKEN")
    if args.adb_serial:
        prefs = subprocess.check_output([
            "adb", "-s", args.adb_serial, "shell", "run-as", "dev.phoneapi",
            "cat", "shared_prefs/api.xml",
        ])
        token = next(node.text for node in ET.fromstring(prefs) if node.attrib.get("name") == "token")
    assert token, "Set API_TOKEN or pass --adb-serial for a debug build"
    origin = urllib.parse.urlsplit(args.url)
    assert origin.scheme == "http" and origin.hostname and not origin.username

    def request(path, expected, method="GET", body=None, headers=None, authenticated=True):
        conn = http.client.HTTPConnection(origin.hostname, origin.port or 80, timeout=8)
        try:
            fields = {"Authorization": f"Bearer {token}"} if authenticated else {}
            fields.update(headers or {})
            conn.request(method, path, body=body, headers=fields)
            response = conn.getresponse()
            data = json.loads(response.read())
            assert response.status in ((expected,) if isinstance(expected, int) else expected), f"{method} {path}: expected {expected}, got {response.status}"
            assert response.getheader("Cache-Control") == "no-store"
            return data
        finally:
            conn.close()

    spec = request("/openapi.json", 200, authenticated=False)
    assert spec["openapi"] == "3.1.0"
    assert "/sms/send" in spec["paths"] and spec["security"]
    assert "submitting" in spec["components"]["schemas"]["SmsRequest"]["properties"]["state"]["enum"]
    request("/openapi.json", 405, method="POST", body=b"", authenticated=False)
    conn = http.client.HTTPConnection(origin.hostname, origin.port or 80, timeout=8)
    try:
        conn.request("GET", "/docs")
        response = conn.getresponse()
        data = response.read()
        assert response.status == 200 and b"Idempotency-Key" in data
        assert b"subscriptionId" in data and b"/openapi.json" in data
        assert response.getheader("Content-Security-Policy")
        assert not response.getheader("WWW-Authenticate")
    finally:
        conn.close()
    request("/health", 401, authenticated=False)
    request("/health", 401, headers={"Authorization": "Bearer incorrect"})
    health = request("/health", 200)
    assert health["running"] is True
    request("/device/status", 200)
    request("/stats", 200)
    request("/health/live", 200)
    request("/health/ready", 200 if health["ready"] else 503)
    request("/", 401, authenticated=False)
    conn = http.client.HTTPConnection(origin.hostname, origin.port or 80, timeout=8)
    try:
        basic = base64.b64encode(f"api:{token}".encode()).decode()
        conn.request("GET", "/", headers={"Authorization": f"Basic {basic}"})
        response = conn.getresponse()
        assert response.status == 200 and b"<title>Phone API</title>" in response.read()
    finally:
        conn.close()
    assert isinstance(health["addresses"], list)
    sims = request("/sms/subscriptions", 200 if health["readSims"] else 403)
    if health["readSims"]:
        assert all("subscriptionId" in sim for sim in sims["subscriptions"])
    caps = request("/capabilities", 200)
    assert "backgroundLocation" in caps and "numberRegion" in caps
    battery = request("/device/battery", 200)
    assert "temperatureC" in battery and "powerSave" in battery
    assert isinstance(request("/device/network", 200)["transports"], list)
    request("/contacts", 200 if caps["contacts"] else 403)
    request("/contacts?number=07700900000", 400)
    request("/contacts?region=invalid", 400)
    request("/calendar/calendars", 200 if caps["calendar"] else 403)
    request("/calendar/events", 200 if caps["calendar"] else 403)
    request("/calendar/events?from=0&to=999999999999999", 400)
    request("/location?fresh=invalid", 400)
    location_access = caps["location"] and (caps["backgroundLocation"] or caps["appVisible"])
    request("/location", (200, 503) if location_access else 403)
    notification_status = 200 if caps["notificationListenerConnected"] else 503
    request("/notifications", notification_status if caps["notificationAccess"] else 403)
    hooks = request("/webhooks", 200)
    assert "token" not in hooks and "hasToken" in hooks
    request("/webhooks", 405, method="POST", body=b"")
    request("/webhooks", 400, method="PUT", body=b'{"url":"file:///tmp/events"}', headers={"Content-Type":"application/json"})
    request("/webhooks", 400, method="PUT", body=b'{"events":["unknown"]}', headers={"Content-Type":"application/json"})
    if not hooks["enabled"]:
        request("/webhooks/test", 409, method="POST", body=b"")
    request("/device/ring", 405)
    request("/device/ring", 400, method="POST", body=b'{"durationSeconds":31}', headers={"Content-Type":"application/json"})
    request("/device/vibrate", 400, method="POST", body=b'{"durationMs":0}', headers={"Content-Type":"application/json"})
    request("/missing", 404)
    request("/sms/requests/does-not-exist", 404)
    request("/health", 405, method="POST", body=b"")
    request("/sms/send", 405)
    request("/sms/send", 415, method="POST", body=b"{}")
    base = {"Content-Type": "application/json", "Idempotency-Key": "safe-validation-check"}
    request("/sms/send", 413, method="POST", body=b"x" * 8193, headers=base)
    request("/sms/send", 400, method="POST", body=b"{}", headers={"Content-Type": "application/json"})
    request("/sms/send", 400, method="POST", body=b"{", headers=base)
    request("/sms/send", 400, method="POST", body=b'{"to":42,"text":"test"}', headers=base)
    request("/sms/send", 400, method="POST", body=b'{"to":"invalid","text":"test"}', headers=base)
    request("/sms/send", 400, method="POST", body=b'{"to":"invalid","text":"test","subscriptionId":"1"}', headers=base)
    request("/sms/send", 400, method="POST", body=b'{"to":"invalid","text":"test","subscriptionId":0}', headers=base)
    if health["readSms"]:
        request("/sms/messages?limit=0", 400)
        request("/sms/messages?limit=101", 400)
        request("/sms/messages?after=-1", 400)
        sample = request("/sms/messages?limit=1", 200)
        if sample["messages"] and sample["messages"][0]["numberE164"]:
            number = sample["messages"][0]["numberE164"]
            conversation = request("/sms/messages?number=" + urllib.parse.quote(number, safe=""), 200)
            assert all(message["numberE164"] == number for message in conversation["messages"])
            assert conversation["box"] == "all"
        request("/sms/messages?number=07700900000", 400)
        request("/sms/messages?box=invalid", 400)
        request("/sms/messages?after=10&before=10", 400)
        request("/sms/messages?number=%2B447700900000&region=GB&limit=1", 200)
        assert request("/sms/messages?after=9223372036854775807", 200)["messages"] == []
    else:
        request("/sms/messages", 403)
    print("PASS: docs, authentication, SMS number filtering, feature routes, permissions and validation. No SMS sent.")


if __name__ == "__main__":
    main()
