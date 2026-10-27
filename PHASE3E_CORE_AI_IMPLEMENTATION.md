# Phase 3E implementation status

This build makes local protection independent of on-device Gemma and records explicit remote-consent and assessment state. **Automatic remote SMS transmission is disabled.** The current HTTP LAN API and shared development API key are only for manual synthetic tests.

## Files changed in Phase 3E

- Consent and orchestration: `backend/consent/AiProtectionConsentStore.kt`, `backend/consent/AutomaticAnalysisPolicy.kt`, `backend/network/RemoteTransportGate.kt`, `backend/network/RemoteVerdictPolicy.kt`, `controller/SmsController.kt`, `backend/worker/SmsProcessingWorker.kt`, `backend/worker/SmsInboxSyncWorker.kt`.
- Local rules and state: `backend/detection/BackendKeywordRules.kt`, `model/data/DetectionState.kt`, `model/data/ProcessingState.kt`, `model/entity/Message.kt`, `backend/database/AppDatabase.kt`, `backend/database/dao/MessageDao.kt`.
- User flow: `view/fragment/OnboardingFragment.kt`, `res/layout/activity_onboarding.xml`, `view/fragment/SettingsFragment.kt`, `view/activity/MainActivity.kt`, `view/adapter/MessageListAdapter.kt`, `view/fragment/MessagesFragment.kt`, `view/fragment/MessageDetailFragment.kt`, `util/NotificationHelper.kt`.
- Research/reporting: `backend/community/CommunityScanner.kt`, `view/fragment/CommunityFragment.kt`, `backend/reporting/ReportSnapshotFactory.kt`.
- Build/security: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/debug/AndroidManifest.xml`, `app/src/release/AndroidManifest.xml`, `app/src/release/res/xml/production_network_security.xml`; `GemmaManager.kt` and `GemmaValidator.kt` moved from `main` to `debug` under `backend/gemma/`.
- Tests/docs: `app/src/test/java/com/apcida/smishingdetector/backend/phase3e/BackendKeywordRulesParityTest.kt`, `Phase3EPolicyTest.kt`, `backend/reporting/ReportSnapshotFactoryTest.kt`, `app/src/androidTest/java/com/apcida/smishingdetector/backend/consent/AiProtectionConsentStoreTest.kt`, `README.md`, and this file.

## Implemented

- On first launch, the onboarding screen explains the automatic remote SMS flow, transmitted fields, outage fallback, revocation, and separate research contribution. The opt-in box starts unchecked. SMS permission alone does not grant remote consent. A user may continue with local rules only.
- `AiProtectionConsentStore` persists a versioned choice, grant time, and generation. Settings can grant through a separate confirmation and revoke synchronously; revocation cancels tagged work. Existing installations default to off.
- New SMS receives the Kotlin port of frozen backend rule version 2, including category deduplication, phrase cap 60, URL cap 80, and thresholds 30/60. A rule score below 30 means **UNVERIFIED**, not finally SAFE. Strong local SCAM indicators can warn immediately.
- `Message` stores detection state, verdict source, stable request ID, consent generation, and notification marker. Room migration 7→8 marks existing rows `LEGACY_ON_DEVICE` without relabeling historical results. A completed remote result has a separate server-primary application method, but it has no production caller while the security gate is closed.
- Inbox import is local-only even for messages discovered after opt-in. The unique worker path and stored-message duplicate check remain, with no unbounded remote retries. Revocation and stale work cannot use the debug LAN client.
- Inbox and detail screens distinguish local rules, AI unavailable, unverified, and completed remote verdicts. A local warning and later remote result use one notification ID; a final remote SAFE result cancels the local warning.
- Active incoming-SMS and research scans no longer invoke on-device Gemma. Research suggestions are local-rule-only, with unverified low-rule candidates unselected until a user reviews them. Research upload remains a separate confirmation. Sensitive Room data is excluded from Android backup; production cleartext is disabled.

## Security gate and deferred work

`RemoteTransportGate.PRODUCTION_READY` is false and no production remote client is wired into `SmsProcessingWorker`. Enabling Settings consent persists the choice but **does not send real SMS**. The worker must stay closed until all of these are built and reviewed:

1. A stable HTTPS endpoint with validated server identity, plus a production gateway that keeps Ollama and PostgreSQL private.
2. Per-install enrollment, credential storage, rotation, revocation, and per-device rate limits. The shared development key must never be compiled into a production APK.
3. An authenticated client implementing the existing four-field API contract with typed errors and cancellation. It must recheck consent immediately before a request and before applying a response.
4. Server-side idempotency for the stable request ID, bounded retry and retention policy, and end-to-end tests for offline, timeout, RAG failure, revocation races, and duplicate broadcasts.
5. A physical-device run for SMS receiver behavior, Room migration from an installed version 7 database, process death, notification updates, and consent persistence/revocation. This was not run because no Android device is available.

The debug manual synthetic test screen remains separate. The old on-device model manager/validator and batch evaluation source are retained under `app/src/debug` for historical tooling. MediaPipe is a debug-only dependency; the release source set has no on-device Gemma runtime. The debug batch evaluation launcher is not exposed, and its source remains available for historical work.

## Verification commands

From `/Users/giopatrick/Documents/apcida/Mobile_App`:

```sh
./gradlew testDebugUnitTest --offline
./gradlew assembleDebug --offline
./gradlew compileDebugAndroidTestKotlin --offline
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. To run device tests once hardware is available:

```sh
./gradlew connectedDebugAndroidTest
```

Backend tests from `/Users/giopatrick/smishing-llm-rag/apcida-ai-server`:

```sh
npm test
```

No full evaluation or real SMS transmission was run in Phase 3E. The frozen backend classifier, rules, prompts, resolver, RAG knowledge, datasets, and historical reports were unchanged.
