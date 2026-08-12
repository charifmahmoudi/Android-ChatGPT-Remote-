# Android ChatGPT Remote

An experimental standalone Android service that exposes the same phone's paired Wireless ADB as MCP through the public [OpenAI Secure MCP Tunnel client protocol](https://github.com/openai/tunnel-client/blob/master/docs/protocol.md). It needs no Termux process, DDNS, inbound port, or public phone endpoint.

> Independent community implementation—not an official OpenAI application. It does not impersonate ChatGPT Remote or grant ADB privileges by itself.

## Status

- Kotlin tunnel client: canonical endpoints, authentication, client headers, `200`/`204` polls, correlation, shard-token response header, deadlines, bounded concurrency, retries, and JSON-RPC/session termination.
- Android foreground service and persistent notification.
- Embedded MCP server with `adb_status`, `adb_shell`, `adb_packages`, and `adb_properties` tools.
- Direct Kotlin ADB client with Android 11+ Wireless Debugging PIN pairing.
- Android Keystore-backed encrypted configuration.
- Unit tests and GitHub Actions APK build.

## Install and configure

1. Download `android-chatgpt-remote-debug.apk` from the latest successful **Android CI** workflow artifact.
2. Obtain Secure MCP Tunnel access, a `tunnel_id`, and a runtime key with **Tunnels Read + Use**.
3. Enable **Developer options → Wireless debugging**. Use split-screen so this app and Settings remain open.
4. Tap **Pair device with pairing code**. Enter the temporary pairing port and six-digit PIN, then tap **Pair ADB**.
5. Return to the main Wireless debugging page and copy its separate connection port into **ADB connection port**.
6. Save and start the service, then configure the tunnel in ChatGPT Connectors.

Android may ask you to allow installation from your browser/files app. Debug APKs update only over an APK signed with the same debug key. Secrets are encrypted at rest, backup is disabled, and the configuration screen blocks screenshots.

## Architecture

```text
ChatGPT -> OpenAI Secure MCP Tunnel <- outbound HTTPS <- Android service
                                                        |
                                                        +-> embedded MCP -> paired local adbd
```

## Protocol coverage

- `GET /v1/tunnels/{id}/poll?limit=25&timeout_ms=15000`
- `POST /v1/tunnels/{id}/response`
- bearer auth, stable client identity, instance ID, MCP server-info, and shard token
- `jsonrpc` and `session_termination` commands
- monotonic `response_timeout` deadlines with fail-open malformed values
- transient poll/terminal-response retry with bounded exponential backoff
- unknown command types ignored, never reinterpreted

See [Security](docs/SECURITY.md) and [Development](docs/DEVELOPMENT.md).

## Build locally

Requires JDK 17, Android SDK 35, and Gradle 8.10.2.

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Licensed under Apache-2.0.
