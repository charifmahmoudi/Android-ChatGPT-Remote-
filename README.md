# Android ChatGPT Remote

An experimental standalone Android foreground service that exposes the same phone's paired
Wireless ADB as MCP through OpenAI's public
[Secure MCP Tunnel client protocol](https://github.com/openai/tunnel-client/blob/master/docs/protocol.md).
It requires no Termux process, DDNS record, inbound port, or public phone endpoint.

> Independent community implementation—not an official OpenAI application. It does not
> impersonate ChatGPT Remote, bypass Android's security model, or grant ADB privileges by itself.

## What it does

- Runs pairing, configuration, tunnel polling, retries, and MCP dispatch in a foreground service.
- Keeps working when the activity is closed; a persistent notification reports service status.
- Shows UI fields only when a tunnel credential, pairing PIN, or ADB connection port is needed.
- Stores the tunnel runtime key and endpoint configuration in Keystore-backed encrypted storage.
- Provides four MCP tools: `adb_status`, `adb_shell`, `adb_packages`, and `adb_properties`.
- Uses outbound HTTPS to OpenAI and loopback Wireless ADB; it opens no Internet-facing listener.

## Architecture

```mermaid
flowchart TB
    ChatGPT["ChatGPT connector"] --> Control["OpenAI Secure MCP Tunnel"]
    Service["Android foreground service"] -->|"outbound HTTPS long poll"| Control
    Service --> MCP["Embedded MCP dispatcher"]
    MCP -->|"Kadb over loopback"| ADB["Android wireless adbd"]
    UI["Status and credential UI"] -. "service intents / status" .-> Service
```

No request is routed directly from the public Internet to the phone. The service polls the tunnel,
dispatches received JSON-RPC to the embedded MCP implementation, and posts correlated responses.

## Requirements

- Samsung Galaxy or another Android 11+ device with **Wireless debugging**.
- Developer options enabled.
- Secure MCP Tunnel access, a `tunnel_id`, and a runtime key authorized for tunnel use.
- Android must allow the app's foreground service and persistent notification.

The app targets Android API 35 and supports API 26+, but same-device Wireless Debugging pairing
requires Android 11 or later in practice.

## Install and configure

1. Download `android-chatgpt-remote-debug.apk` from a successful **Android CI** run.
2. Allow installation from the browser or files application and install the APK.
3. Open the app and allow notifications when Android asks. The foreground service starts.
4. Enter the tunnel ID and runtime key if requested.
5. Open **Settings → Developer options → Wireless debugging**. Split-screen is helpful because the
   temporary pairing dialog must remain open.
6. Tap **Pair device with pairing code**. Enter its temporary pairing port and six-digit PIN in the
   app, then tap **Pair ADB**.
7. Return to the main Wireless debugging screen. Enter its separate **IP address & port** connection
   port in the app. Do not reuse the temporary pairing port.
8. Close the activity if desired. Confirm that the persistent notification reports the service as
   running, then configure the corresponding tunnel in ChatGPT.

```mermaid
stateDiagram-v2
    [*] --> NeedTunnel
    NeedTunnel --> NeedPairing: credentials saved
    NeedPairing --> Pairing: PIN submitted
    Pairing --> NeedAdbPort: paired
    Pairing --> NeedPairing: pairing failed
    NeedAdbPort --> Connecting: connection port saved
    Connecting --> Running: client started
    Running --> NeedTunnel: authorization failed
    Running --> Error: unexpected failure
    Error --> Connecting: retry
    Running --> Stopped: stop
```

Pairing success is remembered across process restarts. Android may change the Wireless ADB
connection port after Wireless debugging or the device restarts; reopen the app and update it when
the stored port no longer works.

## MCP tools

| Tool | Input | Behavior |
| --- | --- | --- |
| `adb_status` | none | Runs `id` and reads the device model to validate ADB connectivity. |
| `adb_shell` | `command` string | Runs one command as Android's `shell` user; input is limited to 8,192 characters. |
| `adb_packages` | optional `include_system` boolean | Lists third-party packages by default, or all packages when true. |
| `adb_properties` | none | Returns Android system properties using `getprop`. |

Text output is capped at 1,000,000 characters per tool result. ADB access is powerful even without
root; require user approval for `adb_shell` calls and inspect every command.

## Tunnel protocol coverage

Implemented:

- canonical `GET /v1/tunnels/{id}/poll` and `POST /v1/tunnels/{id}/response` endpoints;
- bearer authentication and stable client name, version, instance, and MCP server-info headers;
- `200` command envelopes and normal `204 No Content` polls;
- `jsonrpc` and `session_termination` dispatch without guessing unknown command types;
- per-command correlation using request ID, channel, and shard token;
- monotonic `response_timeout` deadlines anchored at poll receipt;
- one poll loop with four concurrent command workers;
- bounded exponential backoff for network failures and documented terminal-response retry statuses;
- terminal handling of response `404` and operator-visible tunnel `401`/`403` failures;
- unknown JSON properties and malformed timeout values accepted for forward compatibility.

Not implemented yet:

- intermediate `jsonrpc_notify` progress delivery;
- `Retry-After` parsing;
- explicit multi-channel poll subscriptions;
- managed Cloudflare credentials, mTLS, and OAuth rewriting;
- structured tunnel-failure provenance;
- a release-signed APK or Play Store distribution.

See [Development](docs/DEVELOPMENT.md) for component details and [Security](docs/SECURITY.md)
before exposing a device.

## Build and test

The supported reproducible path is GitHub Actions. For a local build, install JDK 17, Android SDK
36, and Gradle 8.13, then run:

```bash
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. CI uploads both the renamed APK
and test/lint reports. Debug APK upgrades require the same signing key; uninstall an incompatible
older build first if Android reports a signature conflict.

Licensed under Apache-2.0.
