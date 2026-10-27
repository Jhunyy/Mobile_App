# Phase 3L physical instrumentation follow-up

Updated 2026-10-28 after a Windows teammate ran the suite on a Xiaomi 2109119DG with Android 14. Reported baseline: 13 tests, 10 passed, 2 failed, 1 skipped. This change has **not yet been rerun on that phone**.

## Root causes and fixes

| Test | Finding | Change |
| --- | --- | --- |
| `OfflineGemmaSmsTest.smsIsAnalyzedWithWifiOff` | Obsolete assertion expected on-device Gemma to reach `AVAILABLE`. The current SMS path uses immediate keyword rules and the production remote gate is false. The old test also did not actually switch Wi-Fi off; it only logged that phrase and waited up to two minutes for AI. | Replaced with `OfflineKeywordSmsTest`: two synthetic cases exercise `SmsController.receiveAndAnalyzeRules`, verify no remote enqueue or Gemma verdict, check zero-match `UNVERIFIED` and strong local SCAM verdict, and clean up only their own Room rows. No inbox or network access and no test notification. Existing scoring and classification code are untouched. |
| `ExampleInstrumentedTest.useAppContext` | Hard-coded template package `com.example.apcida` differs from the debug application ID `com.apcida.smishingdetector.debug`. | Assert `targetContext.packageName == com.apcida.smishingdetector.BuildConfig.APPLICATION_ID`, which follows the active build variant. |
| `SyntheticNotificationInstrumentedTest` | On Android 14 the API-level filter passes. A skip comes from one of three `assumeTrue` gates: app notifications disabled, `POST_NOTIFICATIONS` not granted, or ApCiDa scam alerts disabled in Settings. The teammate's summary does not identify which gate. | Kept the test and added explicit messages to each assumption so the next XML/report identifies the reason. Grant permission and enable both OS and in-app alerts to execute its notification assertions. |

Consent, Keystore, Room migration/atomic-claim, and coordinator tests were not changed. `PRODUCTION_READY` remains false. No on-device Gemma or automatic real SMS upload was added. No Gemma 3 12B, RAG, keyword scoring, or frozen evaluation change was made.

## Verification on this Mac

`./gradlew testDebugUnitTest assembleDebugAndroidTest --console=plain` passed: **108 JVM tests, 0 failures**, and the instrumentation APK compiled. The small test-name clarification was followed by another instrumentation APK compilation. No Android phone was attached here, so **physical instrumentation remains pending**. With two replacement offline tests instead of one obsolete test, the next full device suite should discover 14 tests; this is an expected count, not a pass claim.

## Windows rerun on the Xiaomi phone

Use the teammate's working Android SDK and JDK environment. In PowerShell from the Android repository:

```powershell
cd C:\path\to\Mobile_App
adb devices -l
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
.\gradlew.bat :app:installDebug --console=plain
```

Confirm `adb devices -l` shows the Xiaomi as `device`, not `unauthorized`. For the notification test, enable **Notifications** for the ApCiDa debug app in Android system settings, allow its notification channel, and turn on scam alerts in ApCiDa Settings. On Android 14, grant the runtime permission if it is not already granted:

```powershell
adb shell pm grant com.apcida.smishingdetector.debug android.permission.POST_NOTIFICATIONS
```

Then run the complete instrumentation suite:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

If only the notification test remains skipped, review its assumption message in `app\build\reports\androidTests\connected\index.html`, correct the relevant phone setting, and rerun that class:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.apcida.smishingdetector.util.SyntheticNotificationInstrumentedTest" --console=plain
```

The notification test checks same-ID local/remote updates, one active notification, and cancellation. It still does not prove user-visible sound behavior or persistence after an actual app process death; those require supervised device checks. The offline keyword tests do not toggle Wi-Fi, because they exercise a path with no network call. To observe actual offline behavior manually, turn off Wi-Fi and mobile data on the phone before the full suite; the test assertions themselves do not depend on connectivity.
