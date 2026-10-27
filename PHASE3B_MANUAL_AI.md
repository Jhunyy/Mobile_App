# Phase 3B: manual remote AI test (debug builds only)

This feature is a separate launcher activity named **Remote AI Manual Test (debug)**. It sends only text entered on that screen after the tester confirms it is synthetic and taps Analyze. It does not read the inbox, register an SMS receiver, replace on-device Gemma, update local verdicts, or post notifications. The API key and server address stay in the screen's memory and are not stored in preferences or BuildConfig. Release builds do not contain this activity, client, or cleartext network configuration.

## Start the Mac services

Use the existing private `apcida-ai-server/.env` with `OLLAMA_MODEL=gemma3:12b`, `RAG_ENABLED=true` if approved knowledge should be used, `RULES_SHADOW_MODE=true`, and `VERDICT_POLICY=gemma`. Keep Ollama and PostgreSQL bound to loopback. Do not put the API key into Android source or a committed file.

1. Start the Ollama app, or run `ollama serve` in its own Terminal if it is not already running. Check `ollama list` for `gemma3:12b` and `nomic-embed-text`.
2. If RAG is enabled, start the existing local PostgreSQL 17 installation. For Homebrew: `brew services start postgresql@17`. Check `pg_isready -h 127.0.0.1 -p 5432`.
3. Find the Mac's Wi-Fi address with `ipconfig getifaddr en0`. If Wi-Fi uses another interface, find its private IPv4 address in macOS Network settings.
4. Start the development listener, replacing the example address with the Mac's address:

```sh
cd /Users/giopatrick/smishing-llm-rag/apcida-ai-server
caffeinate -i env APCIDA_DEV_LAN_HOST=192.168.1.23 node src/dev/lanServer.js
```

The launcher rejects a public or wildcard address and refuses a non-shadow policy. Keep the Mac lid open, use a trusted Wi-Fi network, and do not forward port 3000 on a router. Use synthetic messages only: the development connection is HTTP on the LAN.

## Build and run on a physical Android phone

Connect the phone and Mac to the same trusted Wi-Fi. Enable USB debugging if installing with `adb`:

```sh
cd /Users/giopatrick/Documents/apcida/Mobile_App
./gradlew assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio can also run the **debug** build variant on the connected phone. Open the **Remote AI Manual Test (debug)** launcher icon, not the normal inbox icon. In the phone's browser, `http://192.168.1.23:3000/health` should show `{"status":"ok"}` after replacing the address. If it does not, check Wi-Fi client isolation, the Mac firewall, and the server terminal.

On the manual screen, enter `http://<Mac-private-IP>:3000`, enter the development API key from the Mac's private `.env`, type a synthetic SMS, check the synthetic-message confirmation, and tap **Analyze on remote AI server**. The screen identifies successful results as **remote AI server (experimental)** and shows classification, confidence, and rationale. Invalid keys, unavailable services, timeouts, and malformed responses appear as errors; the local detector is unaffected. The key is not saved, so reenter it after the screen is recreated.

The client accepts only an explicit private IPv4 HTTP origin with a port. `http://127.0.0.1:3000` is also accepted for an optional USB tunnel after `adb reverse tcp:3000 tcp:3000`; it does not reach the Mac directly over Wi-Fi. The debug manifest permits cleartext HTTP; release builds have no such override. Connection timeout is 10 seconds, socket read timeout 150 seconds, and whole-call timeout 270 seconds to cover embedding followed by Gemma inference. All calls run off the Android UI thread.

## Check errors and existing behavior

- Use a wrong key to check the invalid-key message. Stop the Mac listener to check unavailable-server handling. Both should leave the local inbox and notifications unchanged.
- Use the normal ApCiDa launcher to confirm Reports and Settings navigation and existing on-device detection still work. The manual result is not inserted into the message database.
- No Android source in the SMS receiver, processing workers, `SmsController`, on-device Gemma, or notification helper was changed for this feature.
- For automated checks, run `./gradlew testDebugUnitTest`. The tests stub network responses and cover the authenticated POST, all three verdicts, invalid key, connection failure, timeout, malformed responses, and no request before an explicit analyze call.

This is a development tool, not a remote classifier for incoming SMS. It does not validate reliability on real SMS. Keep real-world quantitative evaluation deferred until an independently labeled dataset is ready.
