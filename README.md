# Phone API

An Android app that exposes SMS, contacts, calendars, notifications and device controls through an authenticated HTTP API over Tailscale. Android 14 or newer. No root access is needed.

The app displays live network addresses and active SIMs, supports selecting a SIM for SMS, and follows Android's light/dark theme. Optional features have separate permission controls. Webhook delivery is configured on the phone and stays off until enabled.

## Build and run

Requirements: JDK 21, Android SDK platform 37.2, and Tailscale on the phone and client. The project uses Android Gradle Plugin 9.3.0 and the included Gradle 9.5.0 wrapper.

```sh
# Set ANDROID_HOME to your Android SDK directory if it is not already configured.
./gradlew assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Connect Tailscale on the phone. Permit the intended clients to reach it on TCP port **8765** in your tailnet access policy.
2. Open **Phone API**, grant the permissions you need, and enable **Run API**.
3. Copy the Tailscale URL and generated API key from the app. Open `/docs` for the guide or `/` for device stats.

Source builds use application ID `dev.phoneapi`. This is a sideloaded prototype; it is not a Play Store or Android Auto app.

## Authentication and access

A random 256-bit API key is generated on first launch. It stays saved across restarts and normal app updates. You can save a custom key or generate a new one in the app; changes take effect immediately. Clearing app data or uninstalling generates a new key on the next launch. Keys are excluded from backups and are not logged.

Device and SMS routes accept `Authorization: Bearer <key>`. Browsers can use HTTP Basic authentication: username **api**, password **your saved key**. Only `/docs` and `/openapi.json` are public on the listener.

The server listens on loopback and active Tailscale IPv4/IPv6 addresses, updating listeners as networking changes. It does not listen on Wi-Fi or mobile addresses. Tailscale carries HTTP inside its encrypted tunnel; keep access restricted to trusted clients. Local Android apps still need the API key.

```sh
# Replace with the URL copied from Phone API.
export PHONE_API='http://your-phone:8765'
# Supply PHONE_API_KEY through your environment or secret storage.
curl -H "Authorization: Bearer $PHONE_API_KEY" "$PHONE_API/health"
curl --get -H "Authorization: Bearer $PHONE_API_KEY" \
  --data-urlencode 'number=+447700900000' \
  --data-urlencode 'region=GB' \
  "$PHONE_API/sms/messages"
```

`your-phone` is an example MagicDNS hostname. The example telephone number is a dummy, not a configured recipient.

## API routes

See the [on-device guide](app/src/main/assets/docs.html) and [OpenAPI 3.1 definition](app/src/main/assets/openapi.json) for parameters, schemas and error responses. Import `/openapi.json` into an API client or Swagger viewer.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/docs`, `/openapi.json` | Public guide and API definition |
| GET | `/` | Authenticated browser stats page |
| GET | `/health`, `/stats`, `/device/status` | Service, permissions, device, listener and SIM status |
| GET | `/health/live` | HTTP liveness and uptime |
| GET | `/health/ready` | 200 when Tailscale, SMS permissions and an active SIM are ready; otherwise 503 |
| GET | `/capabilities` | Optional feature permissions and availability |
| GET | `/device/battery`, `/device/network` | Battery, power state, transports and live IP addresses |
| GET | `/sms/subscriptions` | Active SIM IDs, labels, carriers, slots and default SMS SIM |
| GET | `/sms/messages` | Bounded SMS view, optionally filtered by E.164 number |
| POST | `/sms/send` | Submit SMS through a selected or default SIM |
| GET | `/sms/requests/{id}` | Persisted submission and sending result |
| GET | `/contacts` | Read contact phone entries by name or E.164 number |
| GET | `/calendar/calendars`, `/calendar/events` | Read calendars and recurring event instances |
| GET | `/location` | Cached fix, or one on-demand request with `fresh=true` |
| GET | `/notifications` | Current notifications after special access is enabled |
| POST / DELETE | `/device/ring` | Ring for 1–30 seconds / stop ringing |
| POST | `/device/vibrate` | Vibrate for 1–5,000 milliseconds |
| GET / PUT | `/webhooks` | Inspect or configure webhook delivery |
| POST | `/webhooks/test` | Queue a synthetic webhook event |

### SMS and SIM selection

`GET /sms/messages` returns newest messages first, with a limit of 1–100. Supply an E.164 `number`, such as `%2B447700900000`, to match a contact's incoming and sent SMS. National numbers stored on the phone are normalised using Android's E.164 formatter; matching uses the full number. `region=GB` overrides the inferred region. With a number the default is `box=all`; otherwise it is `box=inbox`. `after` and `before` are exclusive Unix timestamps in milliseconds.

This is a bounded view, not lossless pagination. Messages sharing a timestamp boundary can be skipped during timestamp-only paging. Reading does not mark SMS as read. MMS and RCS are not supported.

For sending, use JSON with `to` (international number beginning with `+`), `text` (1–2,000 characters), and optional `subscriptionId` from `/sms/subscriptions`. Omitting the SIM uses Android's default SMS SIM; if Android is set to ask each time, choose an ID explicitly. SIM IDs can change when a SIM is replaced. Listing SIMs requires phone-state permission; SIM telephone numbers are not returned.

Send `Content-Type: application/json`, `Content-Length`, and an `Idempotency-Key` of 16–128 letters, digits, underscores or hyphens. Bodies are capped at 8,192 bytes; chunked bodies are rejected.

**An idempotency key identifies one message attempt.** Generate it once, for example with `uuidgen`, and keep it for retries. Repeating the same key, contents and requested SIM returns the existing result without sending again. Changing the contents or requested SIM with the same key returns 409. Each new message needs a new key. It is separate from your authentication key.

HTTP 202 means the request was recorded, not delivered. Poll `/sms/requests/{id}`: `submitting` means reserved, `submitted` means Android accepted the call, `sent` means all parts reported sending success, and `failed` means a submission or sending error. `sent` is not a recipient delivery receipt; multipart messages can partially send and incur multiple charges.

Submission records survive process restarts and are retained indefinitely. The app never automatically resends an uncertain request. If the process died during submission, check before using a new key: an SMS may already have gone out. Recipients and message text are not stored in the submission database.

### Optional features and webhooks

Contacts and calendars are read-only. Calendar events default to seven days, with a maximum 31-day range. Location has no continuous tracking: fresh requests last at most 15 seconds. Check availability, age and accuracy; background requests need Android's **Allow all the time** permission. Notification access requires Android's special-access setting, and Android may redact sensitive content.

Configure the webhook URL on the phone. Delivery is off by default; a separate optional Bearer token is stored privately and never returned by the API. Incoming SMS needs Receive SMS permission. Notification forwarding requires an explicit package allowlist and excludes Phone API's own notifications.

Events use a persistent queue and native JobScheduler with network constraints and exponential backoff. Receivers must return 2xx and deduplicate by event UUID: delivery is at least once. Redirects are disabled. HTTP destinations must resolve to private-network addresses; public destinations need HTTPS. The queue holds at most 500 events for seven days, with dropped counts. Disabling webhooks or changing the destination clears the queue but cannot recall an in-flight request. **Stop API** pauses capture and delivery.

## Background behaviour

Run API persists the user's choice. A foreground service shows a quiet notification with a stop action, and requests recovery after process interruption, reboot and app updates when Android permits. Stopping it disables recovery. Android force-stop requires reopening the app.

There is no idle polling, continuous location tracking, app-held wake lock or automatic battery-optimisation exemption. Failed listener binds retry with backoff. Doze, network loss and Tailscale availability can delay reachability and webhooks; the app cannot guarantee uninterrupted background access.

There is no root command execution, agent backend, voice recording or Android Auto interface.

## Checks

```sh
./gradlew connectedDebugAndroidTest
```

The native checks cover durable request reservation, conflicting keys, multipart callbacks with an in-memory database, E.164 normalisation, webhook validation and queue bounds, random-key persistence, and keyboard visibility. They do not send SMS. Key checks use the instrumentation app's preferences, not the API app's saved key. The UI check temporarily opens Phone API.

For an enabled debug build connected by USB:

```sh
adb forward tcp:18765 tcp:8765
python3 scripts/check_api.py http://127.0.0.1:18765 --adb-serial DEVICE_SERIAL
```

The script reads the debug app's token through ADB `run-as` without printing it. Alternatively, supply `API_TOKEN` in the environment and use a Tailscale URL. It checks routes, authentication, permissions and invalid inputs without sending SMS or printing inbox contents.

- [check_background.py](scripts/check_background.py) checks Home/background access and sticky recovery by terminating only the enabled debug app's process, then returns to Phone API. It does not force-stop the app.
- [check_webhooks.py](scripts/check_webhooks.py) checks synthetic authenticated delivery, failed-event retention and stable IDs on retry using a local USB receiver. It requires webhooks off, no saved webhook token and an empty queue, and restores the old settings.

Both take a URL and `--adb-serial DEVICE_SERIAL`. Keep device exports, keys, signing material and local configuration out of version control; ignore rules cover common local artefacts.
