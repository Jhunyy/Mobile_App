# Phase 3I: Cloudflare Quick Tunnel debug pilot

This workflow is for **manually entered synthetic SMS only**. Cloudflare [Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) provide a public, temporary URL that changes when the tunnel is recreated. Keep automatic real SMS transmission disabled. Never put an installation credential in Git, `local.properties`, a URL, or a build script.

## Android configuration

The Secure Pilot Test debug activity reads the approved host from the existing ignored `local.properties` value. For the current tunnel, add or replace this one line in `/Users/giopatrick/Documents/apcida/Mobile_App/local.properties`:

```properties
apcida.pilotApprovedHost=kick-olympics-sand-hostels.trycloudflare.com
```

Use the hostname **without** `https://`, a path, or a trailing slash. The screen builds an HTTPS origin from the configured host, and the client checks an exact host match with normal TLS certificate validation. HTTP, another host, redirects, and a certificate failure are not accepted by the pilot client. The host is included only in the debug build; the release build sets the approved pilot host to an empty string. If Quick Tunnel prints a new URL, replace this line and rebuild the debug APK.

```sh
cd /Users/giopatrick/Documents/apcida/Mobile_App
./gradlew testDebugUnitTest assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Windows teammate

The teammate needs access to the Android repository and a working Android SDK/JDK. After the Android changes have been committed and pushed to `beta-test`:

```powershell
git clone --branch beta-test --single-branch https://github.com/Jhunyy/Mobile_App.git
cd Mobile_App
notepad local.properties
```

Keep the teammate's existing `sdk.dir` line. Add or replace:

```properties
apcida.pilotApprovedHost=kick-olympics-sand-hostels.trycloudflare.com
```

Then build:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`. The hostname is public configuration, but the installation credential must be separately approved and securely provisioned for **that installation**. The administrator key stays on the Mac and is never sent to the teammate. A regenerated Quick Tunnel hostname requires editing `local.properties` and rebuilding on Windows too.

## Physical Android test

1. Confirm that the Mac's pilot API is bound to `127.0.0.1:3010` and `cloudflared` points to that listener. From the phone on mobile data or another Wi-Fi network, open `https://kick-olympics-sand-hostels.trycloudflare.com/health` and verify HTTP 200 and a trusted HTTPS connection.
2. Install the debug APK. Open **Secure Pilot Test (debug)**. Confirm that the displayed approved host matches the active tunnel URL. The endpoint field should contain its `https://` origin.
3. Separately provision the approved per-install credential in the screen. Enter a clearly synthetic SMS, check the synthetic confirmation box, and tap **Analyze synthetic message**. Verify the source, SCAM/SAFE/SUSPICIOUS classification, confidence, and rationale. Do not paste inbox contents.
4. Test a revoked or invalid credential, then stop `cloudflared` or the Mac pilot listener and verify that the screen shows an error. Confirm that opening the SMS inbox or receiving SMS does not call the pilot client; the production remote transport gate remains closed.

The backend's protected endpoint still requires `Authorization: Bearer <installation credential>`, `x-apcida-pilot-synthetic: true`, `sender: ManualTest`, a `pilot-` message ID, text, and `receivedAt`. The screen does not read the inbox. A Quick Tunnel URL is public to anyone who knows it, though the analysis API requires a bearer credential. It is intended for development testing, not real SMS traffic or production availability.
