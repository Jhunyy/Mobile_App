# Phase 3T: Android UX completion and detection lifecycle

Prepared 2026-10-28. This is an Android UX and presentation update. The existing navigation, Room/WorkManager orchestration, backend contract, consent storage, and notification ID behavior remain in place. `RemoteTransportGate.PRODUCTION_READY=false`; no automatic real SMS upload, production hosting, model change, RAG change, or evaluation change occurred.

## Changed files

| File | Change |
| --- | --- |
| `app/src/main/java/com/apcida/smishingdetector/view/presentation/DetectionPresentation.kt` | Central display policy for local, pending, unavailable, consent-disabled, older, and verified remote states. Requires a complete matching remote result before showing SAFE. |
| `view/adapter/MessageListAdapter.kt`, `view/fragment/MessagesFragment.kt` | Inbox badges and colors now follow the display policy. The SAFE filter contains only verified remote SAFE results; older/local SAFE-looking rows remain UNVERIFIED. |
| `res/layout/item_message.xml` | Allows the longer pending/unavailable badge to use the available card width and up to two lines on narrow screens. |
| `view/fragment/MessageDetailFragment.kt` | Shows the same state as the inbox, explains pending and unavailable analysis, displays remote rationale and self-reported confidence, and identifies older on-device results. Removes the ineffective visible AI retry action from the detail view. |
| `view/presentation/ProtectionStatusPresentation.kt`, `view/fragment/SettingsFragment.kt` | Separate status text for AI consent/connection, local keyword coverage, installation credential, and remote deletion. Server availability is explicitly untested while the production gate is closed. Research contribution is labeled separately. |
| `backend/network/ProductionCredentialStore.kt` | Exposes a boolean quarantine status for Settings without reading or showing the raw token. Credential storage and authentication behavior are unchanged. |
| `res/layout/activity_onboarding.xml` | Clarifies what SMS metadata would be sent, notification permission, local protection without remote consent, and separate research sharing. Consent checkbox remains unchecked by default. |
| `view/presentation/NotificationCopy.kt`, `util/NotificationHelper.kt` | Local and remote warning notifications name their source. Remote warnings no longer display a misleading zero local-rule score. Notification text excludes SMS body, sender, and model rationale. Existing notification ID and `setOnlyAlertOnce(true)` are preserved. |
| `app/src/test/java/com/apcida/smishingdetector/view/presentation/*Test.kt` | Eight synthetic JVM tests for state presentation, SAFE gating, pending/failure/legacy cases, Settings status, and private notification copy. |
| `PHASE3T_ANDROID_UX_COMPLETION.md` | This handoff. |

No backend file was changed in Phase 3T. The prior uncommitted Phase 3Q/3R Android and Phase 3S backend work was preserved.

## Detection-state presentation

| Stored lifecycle | Inbox/detail presentation | Meaning |
| --- | --- | --- |
| Local SCAM or SUSPICIOUS | `SCAM/SUSPICIOUS · RULES ONLY`, `AI OFF`, `AI PENDING`, or `AI UNAVAILABLE` as applicable | Immediate local keyword warning; not AI verified. |
| Local zero-warning score | `UNVERIFIED` with the same state suffix | No final SAFE result. |
| Remote request queued/analyzing | Local warning or UNVERIFIED plus `AI PENDING` | The detail page explains that analysis may take a couple of minutes and the user can continue using the app. Room Flow updates the open screen when the row changes. |
| Remote failure, timeout, expiry, or Mac unavailable | Local warning or `UNVERIFIED · AI UNAVAILABLE` | No fabricated SAFE verdict. Existing warning remains. |
| Remote SCAM/SUSPICIOUS/SAFE | `SCAM/SUSPICIOUS/SAFE · REMOTE AI` only when source, detection state, processing state, classification, confidence, and rationale agree | Detail page shows Gemma 3 12B + RAG rationale and self-reported confidence. Only this completed remote SAFE enters the SAFE filter. |
| Older on-device result | Local warning or `UNVERIFIED · OLDER RESULT` | Preserves history while clearly saying it was not verified by the current remote service. |

The display policy never writes classifications or changes keyword scoring. It deliberately fails closed if a stored remote row is incomplete or inconsistent; a strong deterministic local warning can still be shown. The receiver still performs immediate offline keyword detection before any future remote work. Message details and history remain driven by the existing Room Flow, so the user can leave the page while the background request runs and return later. No blocking model call was added to the UI thread.

## Consent, Settings, and privacy

Onboarding still requests SMS permission separately from an unchecked, explicit AI Protection agreement. MainActivity requests notification permission where Android requires it; Settings shows SMS and notification permission controls. AI consent persists through `AiProtectionConsentStore` and can be revoked in Settings. Revocation first commits local opt-out, cancels tagged work, reconciles pending rows, and keeps backend deletion visible until confirmed; only authenticated backend 204 confirms deletion. Rejected credentials remain quarantined for inference. Research contribution has its own review and upload confirmation.

Settings now says whether consent is off or saved, that this build has **no automatic remote connection**, whether local screening has SMS permission, and whether a production installation credential is absent, stored but unverified, rejected, or held for deletion. It never prints a token. It does not probe the production server while the gate is closed, so it does not claim that the Mac is online. The existing deletion retry text distinguishes pending, configuration required, paused, credential unavailable, authentication rejected, and confirmed states.

## Notifications and long-running analysis

The receiver posts a strong local warning immediately when permitted. The same message ID is used for a later remote warning update; `setOnlyAlertOnce(true)` avoids a second audible alert. A verified remote SAFE cancels a prior warning. No pending or unavailable result posts a SAFE notification. The text now identifies local keywords or remote AI and omits raw SMS, sender, rationale, and secrets. Android notification permission and channel settings still control whether any alert appears.

An approximately 80-second Gemma request stays in WorkManager/OkHttp, outside the UI thread. The inbox/detail pages show `AI PENDING` while eligible work is queued or analyzing; after timeout or the 150-second expiry fallback, they show `AI UNAVAILABLE` with the local warning or UNVERIFIED state. The coordinator has one attempt and the backend uses a stable message ID; no new retry queue was added.

Process restart re-enqueues recent queued work and marks stale work unavailable as implemented in Phase 3R. A remaining edge case is a crash after Room commits a remote verdict but before Android posts/cancels its notification. The database result is preserved, but the OS notification may be stale. Blindly reposting every flagged row on restart would also resurrect notifications the user dismissed, so notification reconciliation needs a device-backed design and test before activation.

## Verification

Executed locally:

```bash
cd /Users/giopatrick/Documents/apcida/Mobile_App
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileReleaseKotlin --offline --console=plain
git diff --check
```

- Android JVM: **132 tests, 127 passed, 0 failed, 5 staging-only skips**. The eight new presentation/notification tests passed. Existing synthetic mock transport and consent tests remained passing.
- Debug APK built: `app/build/outputs/apk/debug/app-debug.apk`.
- Instrumentation APK built: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- Release Kotlin compilation passed. `git diff --check` passed.
- No physical Android device was available here. Instrumentation was **compiled, not executed**. No real SMS or production endpoint was contacted.

## Remaining physical-device checks

On a connected Android phone, run the instrumentation suite and inspect the report, then verify the visible inbox/detail/Settings flow with synthetic fixtures and a mock transport: local high-risk warning, zero-rule UNVERIFIED, pending state during a slow response, remote SCAM/SUSPICIOUS/SAFE with rationale and confidence, timeout/unavailable fallback, credential rejection, consent revocation and deletion status, duplicate notifications, notification permission denial, and a supervised process-kill/relaunch. Check that an old on-device SAFE row is labeled UNVERIFIED in history and excluded from the SAFE filter. A device check is needed for layout sizing and accessibility as well as WorkManager/notification timing.

```powershell
cd C:\path\to\Mobile_App
adb devices -l
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

Keep `PRODUCTION_READY=false`; use synthetic fixture data and mock transport responses only. Physical execution, stable HTTPS/Access authentication, server availability, and the notification reconciliation edge case remain outside this local UX verification.
