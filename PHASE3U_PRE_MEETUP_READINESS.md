# Phase 3U: Full system verification and pre-meetup readiness

Prepared 2026-10-28. This review preserves the Phase 3Q–3T working trees. `RemoteTransportGate.PRODUCTION_READY` remains `false`. No automatic real SMS was sent, no production listener or tunnel was deployed, and no physical Android instrumentation was executed on this Mac.

## Scope and Phase 3U file inventory

| File | Phase 3U change |
| --- | --- |
| `app/build.gradle.kts` | Both debug and release `BuildConfig.REPORT_API_KEY` and `COMMUNITY_API_KEY` are now empty. The previous build accepted shared keys from environment or `local.properties`, which embedded them in the APK and left report/research sharing as an outbound SMS path independent of the AI gate. |
| `app/src/main/java/com/apcida/smishingdetector/backend/worker/ReportUploadWorker.kt` | Do not enqueue report upload work without a configured report key. Reports can still be saved locally. The upload manager also refuses a missing key, including for existing queued work. |
| `app/src/main/java/com/apcida/smishingdetector/view/fragment/MessageDetailFragment.kt` | Report confirmation now says that the report was saved locally and sharing is unavailable in this build. |
| `app/src/test/java/com/apcida/smishingdetector/backend/phase3u/PreMeetupOutboundSafetyTest.kt` | Regression guard for the closed production gate and empty report/research keys. |
| `PHASE3U_PRE_MEETUP_READINESS.md` | This audit and device handoff. |

The Android and backend trees contain earlier uncommitted Phase 3Q–3T and Phase 3N–3S work. This phase did not reset, overwrite, or commit it. The backend code was not changed in Phase 3U. No model prompt, Gemma version, keyword scoring, RAG record, or frozen evaluation artifact was changed. Existing local report storage and separate research-consent controls remain; **network sharing of report/research SMS content is unavailable in this pre-meetup APK**. Re-enable only after a separate credential and privacy review. If an older APK containing those shared keys was distributed, rotate the keys before relying on them again.

## Architecture trace and reachability

```text
SMS_RECEIVED broadcast
  -> SmsReceiver assembles parts and calls SmsController on background dispatch
  -> receiver-side duplicate check and immediate BackendKeywordRules score/indicators
  -> MessageRepository inserts a new Room row; local warning or UNVERIFIED
  -> NotificationHelper posts immediate strong local warning if allowed
  -> only a new, nonhistorical row with AI consent and ProductionRemoteRuntime eligibility
  -> message-specific WorkManager job with network requirement plus 150 s expiry job
  -> worker reloads Room row and rechecks consent, generation, age, approved host,
     installation credential, and PRODUCTION_READY
  -> AutomaticRemoteCoordinator makes one atomic Room claim
  -> ProductionSmsTransport sends authenticated HTTPS /api/v1/sms/analyze
  -> production API validates, authenticates, rate-limits and idempotently claims
  -> local embedding + pgvector RAG + Gemma 3 12B, 90 s deadline
  -> conditional Room completion, same notification ID update/cancel, Room Flow UI refresh
```

The live receiver executes local scoring and persistence. The production branch is deliberately **unreachable** because `PRODUCTION_READY=false`; the approved production host is also blank by default. No automatic production worker can send an inbox message in this build. The synthetic Secure Pilot Test is a debug-only manual screen with a separate endpoint and credential. It is not a receiver dependency and must be used only with tester-entered synthetic text.

Historical inbox sync marks rows as historical, so they are ineligible for remote scheduling. Repeated broadcasts are checked against Room before insertion; per-message WorkManager uniqueness, an atomic Room claim, and backend message-ID idempotency guard the later stages. A duplicate completed backend request replays the encrypted cached response. A payload change with the same ID is rejected. The integration path after the closed gate has been exercised by injected/mocked Android tests and the isolated backend staging harness, but not by a live phone receiver.

The UI uses Room Flow and `DetectionPresentation`. A zero-rule message is UNVERIFIED. A locally warned message is labeled rules-only, including when AI is pending or unavailable. SAFE is displayed only for a complete, matching remote result with source/confidence/rationale; malformed, failed, timed-out, or canceled analysis cannot create SAFE. Strong local warnings do not wait for the network. Remote warning updates reuse the message notification ID and `setOnlyAlertOnce(true)`; remote SAFE cancels a prior local warning. The system notification permission/channel still governs visibility.

## Confirmed findings, fixes, and remaining implementation gaps

1. **Fixed independent outbound path.** Report and research submission previously accepted shared keys that were embedded in `BuildConfig` and could transmit selected SMS content outside the closed AI transport. The current APK builds both keys as empty; both Android upload guards therefore block network submission. `ReportUploadWorker` no longer enqueues new work without a key, and the report UI now states its local-only behavior. Current `local.properties` may still hold developer values but they are ignored by these two build fields and must remain untracked.
2. **No automatic production network path.** This is intentional. Activating it requires separate security and deployment approval, not a code change in this phase.
3. **Crash between Room commit and notification operation.** The stored verdict can be correct while an old OS notification remains. Reposting every row on startup could resurrect user-dismissed alerts. A device-tested reconciliation design is still needed.
4. **Interrupted analysis and restart.** A restarted `AI_ANALYZING` row becomes unavailable instead of launching a second model request. The backend might already hold a completed cached verdict; automatic replay is not currently attempted. This favors bounded inference over guaranteed retrieval.
5. **Inference contention.** One backend inference slot limits concurrent SMS. Another new request can receive `INFERENCE_BUSY`; Android keeps its keyword warning or UNVERIFIED and does not automatically retry. Validate realistic arrival bursts before activation.
6. **Broadcast and background execution.** The receiver uses `goAsync`, while analysis is deferred to WorkManager. Xiaomi/Android battery limits, notification permissions, process death, and long work scheduling require real-device checks. No JVM test proves receiver behavior under OS restrictions.
7. **Stable-host integration remains incomplete.** A named HTTPS host, Cloudflare Access/device-auth solution for unattended requests, approved production database, production credential lifecycle, and physical TLS checks have not been deployed. A Cloudflare Access 401 could be indistinguishable from a backend credential 401 and quarantine the credential. Do not point this build at the old Quick Tunnel.

## Synthetic integration verification

| Layer and command | Result | What it proves |
| --- | --- | --- |
| `cd apcida-ai-server && npm test` | 115 tests: 113 passed, 0 failed, 2 environment-gated skips | Production API contract/auth, timeout/cancellation, redaction, idempotency/rate/revocation logic with mocked model; the default command does not run optional database/staging tests. |
| `cd apcida-ai-server && bash scripts/phase3o-staging-verify.sh` | Backend staging API 2/2; disposable PostgreSQL repository 1/1; Android `ProductionStagingContractTest` 5/5 | Synthetic production-contract responses, authentication, duplicate replay, failure paths, and Android transport compatibility. The analyzer here is fake; no live Gemma inference. |
| `cd apcida-ai-server && APCIDA_PHASE3S_LIVE_SYNTHETIC=true caffeinate -i bash scripts/phase3s-live-synthetic-verify.sh` | One synthetic request: HTTP 200, SCAM/HIGH, 26,183 ms; duplicate: HTTP 200, identical response, 26,195 ms total | Real local Gemma 3 12B + approved RAG copy through the production contract and per-install auth. The script used a disposable PostgreSQL 17 cluster, a loopback-only listener, and a read-only copy of 100 approved records. It cleaned up afterward. This one warm-run latency is not a performance guarantee. |
| `cd Mobile_App && ./gradlew :app:testDebugUnitTest --rerun-tasks --offline --console=plain` and combined build below | 133 JVM tests: 128 passed, 0 failed, 5 staging-harness-only skips | Local keyword/UNVERIFIED behavior, synthetic response states, consent/gate/duplicate/timeout/credential logic, presentation and outbound guard. JVM mocks do not prove Android OS background behavior. |

The combined Android build command was:

```bash
cd /Users/giopatrick/Documents/apcida/Mobile_App
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileReleaseKotlin --offline --console=plain
```

It completed successfully. Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (SHA-256 `42106d1bfc807795647ed5eec5b02507a723cc4d6b3e081279a664c311c0d744`). Instrumentation APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` (SHA-256 `40f1d330f48347037051246068f59f7f21b76aeaf77190a402bed1093a406653`). Release Kotlin compiled. The instrumentation APK was **built, not run**; no phone was connected to this Mac. The final staging harness runs its targeted five Android tests and can replace local JUnit XML report contents, so use the above full command again when a complete local HTML report is needed. `git diff --check` passed for the Android changes.

Synthetic coverage includes the three remote verdicts, pending/unavailable and zero-keyword presentation, receiver/controller deduplication policy, historical exclusion, persistent consent and revocation logic, credential rejection/quarantine, deletion acknowledgment, HTTP failure and timeout, process-recreation Room/backend repository behavior, and notification copy/ID policy. Device-only behavior remains listed below. The live-model check covered only one SCAM fixture and its replay; it was not a dataset evaluation.

## Security and privacy audit

- **AI gate:** `PRODUCTION_READY=false` at intake and worker. `ProductionSmsTransport` is not constructed on the live path while closed. No production host or installation token is embedded in the APK. The debug manual pilot is separate; only tester-entered synthetic text is authorized there.
- **HTTPS and identity:** The production client requires an exact approved HTTPS hostname, normal platform TLS certificate verification, no redirects, and a per-install bearer from Android Keystore-backed storage. HTTP and the temporary Quick Tunnel are rejected for production. The debug manifest permits cleartext for legacy manual LAN screens; the release manifest disallows it, and the production transport independently rejects it. Do not use debug LAN screens for real SMS.
- **Consent:** AI Protection starts unchecked and persists separately from SMS permission and research contribution. Local opt-out commits before network cleanup, cancels tagged work, blocks new sends, and conditionally prevents stale completions. A request already received by the server cannot be retracted.
- **Revocation/deletion:** The bodyless self-revocation request can delete an active installation's cached responses and rate windows. Only authenticated backend HTTP 204 marks deletion confirmed locally. A 401/403, absent host/token, offline state, or timeout remains visible as pending/failed; a rejected credential cannot analyze again. Lost, expired, or revoked credentials need an administrator deletion path. Backup and upstream proxy log deletion are not guaranteed by 204.
- **Persistence and logs:** Android SMS remains in app-private Room storage; it is not an encrypted Room database. The manifest disables Android backup. Backend routine tables store no raw SMS or sender; completed responses are encrypted, default cache TTL is 24 hours (configurable 1–72), and startup/hourly cleanup may lag. Identity rows have no automatic expiry deletion. Cloudflare/OS/PostgreSQL backup retention needs separate approval. Production audit events omit SMS text, sender, bearer, and rationale; do not capture full HTTP bodies in reverse-proxy or device logs.
- **Other upload paths:** BuildConfig report and community keys are empty in debug/release, and worker/repository guards block network sharing in this pre-meetup build. Research opt-in remains a separate choice, but the submission path is unavailable. Local report saving still works. Previously distributed APKs may contain older shared keys; rotate those keys before reuse.
- **Repository secrets:** No production bearer or admin token is committed by these changes. `local.properties` and private environment files are untracked. The build now ignores local report/community key values. Do not copy `local.properties`, credentials, or verbose SMS logcat into the meetup handoff.

## Performance and reliability assessment

| Layer | Current bound | Assessment |
| --- | --- | --- |
| PostgreSQL retrieval | 10 s statement timeout | Embedding and query run within the backend overall deadline. |
| Ollama embed/chat | Existing 120 s individual limits | Both receive the shared production cancellation signal. |
| Node production analysis | 90 s wall-clock deadline | Error/504 on timeout, no SAFE fallback; a slow or uncooperative model may keep the single slot busy until it settles. |
| Android OkHttp | 10 s connect, 110 s read, 115 s total | Leaves 25 s beyond the Node budget for transport/proxy response. Cancellation closes the call but cannot recall already received content. |
| Cloudflare proxied origin | Documented default 125 s read timeout | Must be checked for the actual future route, plan and Access behavior. A 524/unavailable response is not SAFE. [Cloudflare limits](https://developers.cloudflare.com/fundamentals/reference/connection-limits/). |
| WorkManager expiry | 150 s conditional fallback | Cannot overwrite a completed remote verdict; may be delayed by Android battery restrictions. |
| Backend inference/request lease | 300 s crash recovery | Prevents permanent lock after process death; a crash before cache commit may cause later explicit repeat inference. |

A prior approximately 80-second Gemma sample left little margin under the 90-second backend deadline; this phase's single warm synthetic sample was 26.2 seconds. Cold loads, Mac sleep, memory pressure, multiple arrivals, and proxy delays remain unmeasured for a stable route. No automatic client inference retry is enabled; failed messages retain a local warning or UNVERIFIED. Safe cancellation, single-slot contention, backend replay, and conditional Room updates are tested in isolation, but OS scheduling and notification consistency need the phone.

## Windows + Xiaomi physical-device checklist

Use a reviewed transfer of this exact working tree or the two built APKs. Earlier GitHub commits may not include the current uncommitted Phase 3Q–3U changes. With transferred APKs, use `Get-FileHash .\app-debug.apk -Algorithm SHA256` and `Get-FileHash .\app-debug-androidTest.apk -Algorithm SHA256` to compare against the hashes above. Use a dedicated test phone and synthetic messages only. Keep the production gate false; do not provision a production credential or route. Capture test counts and failures, but do not share raw SMS, tokens, full bugreports, or unredacted HTTP traces.

### 1. Environment, build, and installation

PowerShell from the Android project root:

```powershell
cd C:\path\to\Mobile_App
java -version
adb version
adb devices -l
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileReleaseKotlin --console=plain
.\gradlew.bat :app:installDebug --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

Confirm `adb devices -l` shows `device`, not `unauthorized` or `offline`. Read `app\build\reports\androidTests\connected\index.html` for actual pass/fail/skip counts. `SyntheticNotificationInstrumentedTest` intentionally skips unless notifications, `POST_NOTIFICATIONS`, and in-app scam alerts are enabled. If only APKs were transferred, install them and invoke the runner:

```powershell
adb install -r .\app-debug.apk
adb install -r .\app-debug-androidTest.apk
adb shell am instrument -w com.apcida.smishingdetector.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Check `adb shell pm list instrumentation` if the runner name differs. For a fresh-install test on a **dedicated test device only**, first uninstall `com.apcida.smishingdetector.debug` (this deletes its local app data), then reinstall the debug APK. Do not uninstall a participant's normal app.

### 2. Fresh launch, permissions, consent, and persistence

- On a fresh debug install, confirm AI Protection consent is unchecked and no remote SMS is sent. Review SMS permission separately from remote-analysis consent. Grant SMS and notification permissions through Android UI; enable scam alerts in ApCiDa Settings. Check denied-permission explanations and the app's behavior if notifications stay denied.
- Toggle AI consent on, close/reopen the app, and verify it persists. Then revoke it and repeat; no pending upload may restart. Research contribution must remain a separate unchecked choice. Confirm Settings does not show a token or claim server availability while the gate is closed.
- Run instrumentation and specifically inspect consent, Keystore, Room migration, remote-analysis DAO, offline keyword, and notification results. Verify the installed debug app's package ID is `com.apcida.smishingdetector.debug`.

### 3. Offline local SMS and UI states

- Disable Wi-Fi and mobile data in the phone UI. If the device permits ADB control, `adb shell svc wifi disable` and later `adb shell svc wifi enable` are useful, but confirm actual offline state in the UI.
- Using a controlled synthetic incoming SMS, verify a strong local keyword warning appears promptly and is labeled `RULES ONLY`; a neutral synthetic SMS must remain `UNVERIFIED`, never SAFE. Check inbox, detail, history, score/indicators, and notification content. Do not use a personal SMS. The receiver's real broadcast behavior may require a second test handset or SMS test facility; the instrumentation fixtures themselves do not read the inbox.
- Check a message with AI consent on still has no automatic remote upload because the gate is closed. Observe `AI OFF`/unavailable wording as applicable. No AI pending state should be falsely claimed for a closed gate.

### 4. Duplicates, background limits, process restart, notifications

- Send the **same synthetic SMS event** twice under a controlled harness, then check one Room message and no duplicate alert. Do not infer deduplication from two similar but genuinely distinct network SMS messages. Verify historical inbox import does not schedule remote work.
- Background the app with `adb shell input keyevent KEYCODE_HOME`, then reopen with `adb shell monkey -p com.apcida.smishingdetector.debug 1`. Use `adb shell am kill com.apcida.smishingdetector.debug` for a process-recreation check; do not use `am force-stop` to judge receiver behavior, because Android suppresses a force-stopped package until it is launched again.
- On Xiaomi, inspect battery optimization, autostart/background restrictions, notification permission, channel settings, lock-screen alerts, and any vendor security settings. Test app termination and reboot persistence with synthetic fixtures. Confirm a local warning remains visible and no stale notification survives a completed SAFE mock result. A notification-permission denial should affect display, not stored detection.
- Check Room migration on an upgrade install carrying an older synthetic database, then restart and ensure rows/consent remain. This is a device test, not established by compiling the instrumentation APK.

Useful filtered commands:

```powershell
adb shell dumpsys package com.apcida.smishingdetector.debug | findstr /i "RECEIVE_SMS READ_SMS POST_NOTIFICATIONS"
adb logcat -c
adb logcat -v time -s AndroidRuntime:E SmsReceiver:D NotificationHelper:W WorkManager:I '*:S'
```

Treat logcat as sensitive even with filters; inspect locally and redact before sharing. Never add a filter that prints SMS body or authorization headers.

### 5. Synthetic HTTPS and failure scenarios

- The debug Secure Pilot Test may use only a separately approved HTTPS pilot host and separately provisioned per-install **pilot** credential. Enter synthetic text manually. Verify SCAM/HIGH/rationale, invalid credential, timeout, and Mac/server outage over another Wi-Fi or mobile data. The pilot client is not the production transport and does not prove the automatic receiver path.
- Use mock/instrumentation fixtures for remote SCAM, SUSPICIOUS, SAFE, pending, timeout, offline, credential rejection, process restart, duplicate replay, and notification update/cancel. A complete remote SAFE must be the only route to a SAFE label; timeout or 401 must leave warning/UNVERIFIED. Do not paste real SMS into the pilot screen.
- Revoke AI consent while a mock request is pending. Verify local opt-out remains effective while offline and deletion status stays unconfirmed without backend 204. Real backend self-revocation and cache deletion require a separately approved isolated HTTPS staging route; do not infer it from local UI state.

### 6. What is safe now and what requires a separate pilot

**Safe before production activation:** local synthetic SMS/keyword and UI checks, JVM and on-device instrumentation using fixtures, Room/permission/notification/restart tests, offline checks, and manual debug pilot analysis of tester-entered synthetic text through an approved pilot host.

**Requires a separately approved controlled pilot:** stable production HTTPS route/Access design, production credential provisioning, real backend self-revocation and deletion confirmation from Android, live production transport over a phone, representative cold/warm latency under contention, and any automatic analysis of real SMS. Do not change `PRODUCTION_READY` during this meetup.

## Go/no-go for the face-to-face session

**Go for synthetic debugging and UX verification.** The local backend suite, disposable staging integration, one live synthetic Gemma + RAG request, Android JVM tests, and all requested builds passed. The current APK keeps automatic AI remote transmission closed and also blocks the independent report/research network paths. Bring the reviewed APKs or exact source revision, a working JDK/SDK/ADB, one authorized Xiaomi phone, a controlled source of synthetic SMS, and optional pilot HTTPS details.

**No-go for automatic real-SMS activation or production hosting.** Physical instrumentation has not run here; stable protected hosting/Access authentication, production database and credential approval, retention/deletion review, cold-load reliability, Xiaomi background behavior, and the notification/restart edge case remain unresolved. Activation needs a separate explicit security and product review.
