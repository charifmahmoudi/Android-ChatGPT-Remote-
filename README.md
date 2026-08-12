# Android ChatGPT Remote

An experimental Android/Kotlin implementation of the public [OpenAI Secure MCP Tunnel client protocol](https://github.com/openai/tunnel-client/blob/master/docs/protocol.md). It lets an Android phone keep a private MCP server reachable through an outbound HTTPS long-poll—no DDNS, inbound port, or public phone endpoint.

> Independent community implementation—not an official OpenAI application. It does not impersonate ChatGPT Remote or grant ADB privileges by itself.

## Status

- Kotlin tunnel client: canonical endpoints, authentication, client headers, `200`/`204` polls, correlation, shard-token response header, deadlines, bounded concurrency, retries, and JSON-RPC/session termination.
- Android foreground service and persistent notification.
- Local Streamable HTTP MCP forwarding with multi-valued headers.
- Android Keystore-backed encrypted configuration.
- Unit tests and GitHub Actions APK build.
- Same-device ADB MCP tools: **not yet included**. Add a paired Wireless Debugging transport behind `McpTransport`; never expose `adbd` publicly.

## Install and configure

1. Download `android-chatgpt-remote-debug.apk` from the latest successful **Android CI** workflow artifact.
2. Obtain Secure MCP Tunnel access, a `tunnel_id`, and a runtime key with **Tunnels Read + Use**.
3. Run a Streamable HTTP MCP server on the phone or a private address reachable by it.
4. Enter the tunnel ID, runtime key, and MCP URL (for example `http://127.0.0.1:8765/mcp`) and start the service.
5. Configure the tunnel in ChatGPT Connectors while the client is healthy.

Android may ask you to allow installation from your browser/files app. Debug APKs update only over an APK signed with the same debug key. Secrets are encrypted at rest, backup is disabled, and the configuration screen blocks screenshots.

## Architecture

```text
ChatGPT -> OpenAI Secure MCP Tunnel <- outbound HTTPS <- Android service
                                                        |
                                                        +-> private/local MCP server
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
