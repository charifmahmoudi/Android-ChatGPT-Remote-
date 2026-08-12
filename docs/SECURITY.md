# Security model

This app creates a powerful bridge. Treat the runtime key and every MCP tool as production credentials.

- The phone makes outbound TLS connections to `api.openai.com`; it opens no inbound Internet listener.
- The runtime key is encrypted using Android Keystore-backed preferences and is never intentionally logged.
- MCP is restricted in the UI to loopback HTTP or HTTPS. Remote cleartext endpoints are rejected.
- Unknown command properties are accepted, but unknown command types are never guessed.

## ADB warning

The app pairs with same-device Wireless Debugging and exposes typed inspection tools plus `adb_shell`. Commands run as Android's `shell` user, not root. Output is capped at 1 MB and command input at 8 KiB. The app never exposes the ADB daemon or pairing port to the Internet. Because `adb_shell` is powerful, configure ChatGPT tool policy to require approval for every call and review the exact command.

Never post keys, tunnel IDs, ADB keys, device logs, or MCP bodies in public issues. Revoke exposed keys immediately.
