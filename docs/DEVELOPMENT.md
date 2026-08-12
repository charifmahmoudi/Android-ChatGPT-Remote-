# Development

The source of truth is OpenAI's public `tunnel-client/docs/protocol.md` and `docs/openapi.json`. Models ignore unknown keys for forward compatibility.

- `TunnelClient`: control-plane polling, dispatch, correlation, deadlines, response and retry.
- `HttpMcpTransport`: private Streamable HTTP MCP binding.
- `TunnelService`: foreground Android lifecycle.
- `SecureConfig`: Keystore-backed settings.

CI runs unit tests, lint, and debug assembly, then uploads the APK and reports.

## Known gaps

- Streaming MCP progress notifications, `Retry-After`, managed Cloudflare mode, OAuth rewriting, multiple channels, and mTLS are not implemented.
- No same-device ADB transport is bundled.
- Release signing and Play Store packaging are intentionally not configured.
