# Security model

This app creates a powerful bridge. Treat the runtime key and every MCP tool as production credentials.

- The phone makes outbound TLS connections to `api.openai.com`; it opens no inbound Internet listener.
- The runtime key is encrypted using Android Keystore-backed preferences and is never intentionally logged.
- MCP is restricted in the UI to loopback HTTP or HTTPS. Remote cleartext endpoints are rejected.
- Unknown command properties are accepted, but unknown command types are never guessed.

## ADB warning

This release does not bundle ADB. A future ADB transport must pair with Wireless Debugging, keep its key in Keystore, require visible approval for arbitrary shell commands, impose time/output limits, and never expose the ADB daemon or pairing port. Prefer typed tools over `shell(command)`.

Never post keys, tunnel IDs, ADB keys, device logs, or MCP bodies in public issues. Revoke exposed keys immediately.
