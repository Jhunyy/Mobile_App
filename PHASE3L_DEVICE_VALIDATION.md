# Phase 3L: Android device validation

Date: 2026-10-27. This phase keeps automatic real SMS transmission disabled. All new fixtures are synthetic. No Gemma, RAG, scoring, prompt, verdict policy, or evaluation data changed.

## What was checked

- `AutomaticRemoteCoordinator` is not constructed or called from `SmsReceiver`, `SmsProcessingWorker`, or `SmsController`. Its default authorization gate uses `RemoteTransportGate.canTransmit`, whose `PRODUCTION_READY` constant remains `false`.
- Consent is explicit, persistent, and checked before a remote claim, before sending, and after receiving a response. A generation change prevents a stale result from being committed. Revocation cancels tagged work and reconciles queued Room rows when Settings or the main activity runs. Research consent is separate.
- The pilot activity is in the debug source set; it uses the synthetic-only authenticated client. No production transport or real-message endpoint is configured.
- Room claim and completion are conditional updates. A second claim or completion for the same message cannot win. Zero-rule failures remain `UNVERIFIED`; a local SCAM warning remains local if remote analysis fails.
- Notification IDs are derived from the message ID. A remote warning updates the same notification ID with `setOnlyAlertOnce(true)`; SAFE cancels that ID. This still needs on-device verification.

## Tests and builds actually run

| Command | Result |
|---|---|
| `./gradlew testDebugUnitTest assembleDebugAndroidTest assembleDebug assembleRelease --console=plain` | Passed. 105 JVM tests, 0 failures; debug app, instrumentation APK, and release APK built. |
| `./gradlew assembleDebugAndroidTest testDebugUnitTest --console=plain` | Passed after final test addition. |
| `adb devices -l` | No attached/authorized device. |
| `./gradlew connectedDebugAndroidTest --console=plain` | Not run: no ADB device. |

The compiled instrumentation tests have **not** yet passed on a physical phone. A database close/reopen test models persistence but is not a forced Android process death. The notification test checks one active notification ID, title update, and cancellation, but cannot count user-visible alert sounds or establish survival across process death until it runs on a phone.

## Synthetic instrumentation coverage ready for the phone

`RemoteAnalysisDaoInstrumentedTest` checks an actual 7→8 migration in a disposable named database, row preservation and new defaults; persistent state after database reopen; single-use claim and completion; concurrent claims; stale consent generation; revocation; timeout/offline/revoked mock outcomes; local keyword warning fallback; and zero-rule `UNVERIFIED`. It does not open or reset the app's normal `kasds_database`.

`SyntheticNotificationInstrumentedTest` posts only synthetic notifications and cleans them up. It skips when notification permission or the app alert setting is off. It checks a local warning, same-ID remote update, `FLAG_ONLY_ALERT_ONCE`, and cancellation. JVM coordinator tests use MockWebServer for the authenticated synthetic HTTP contract and cover success, malformed responses, gate rejection, consent, and failure paths. No device test contacts a real server.

## Run on a physical Android phone

1. Enable Developer options and USB debugging on the phone; connect it to this Mac and accept its RSA prompt. A trusted ADB wireless pairing is also acceptable. No emulator is required.
2. From `/Users/giopatrick/Documents/apcida/Mobile_App`, run:

   ```sh
   /Users/giopatrick/Library/Android/sdk/platform-tools/adb devices -l
   ./gradlew connectedDebugAndroidTest --console=plain
   ```

3. Confirm the first command shows exactly the intended device as `device`, then inspect `app/build/reports/androidTests/connected/` and `app/build/outputs/androidTest-results/connected/` for the test count, failures, and skips. Grant notification permission to the debug app if needed and rerun the notification test so its assertions execute rather than skip.
4. For notification behavior after a real process restart, use a separate supervised manual procedure with synthetic messages. The automated notification test does not kill the app process. Keep the real SMS receiver free of pilot transport and do not submit inbox messages.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Instrumentation APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

## Remaining blockers before automatic integration

- Complete the compiled instrumentation suite on an authorized physical device and investigate any migration or notification failures.
- Verify notification persistence and duplicate handling across a genuine process death with a synthetic-only harness; no claim of this is made from a database reopen alone.
- Review consent and Room-state race boundaries, crash recovery after a claimed request, and notification delivery after a successful Room commit. A crash between commit and notification can leave the row updated without a posted alert.
- Approve and implement a separate production HTTPS endpoint and transport for real SMS, per-install authentication lifecycle, server availability and retention controls, and privacy/security review. The synthetic pilot API must remain separate.
- Keep `RemoteTransportGate.PRODUCTION_READY = false` and the live receiver/worker disconnected until those gates and device checks are satisfied.
