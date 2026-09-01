# KA-SDS App Guide

Keyword-Augmented Smishing Detection System (KA-SDS) is an Android app for detecting SMS phishing messages. The project uses a two-stage detection design: a fast local keyword/risk filter first, then an on-device Gemma validation step for messages that cross the risk threshold.

Team: Jhunren Apiag, Gio Patrick Cimeni, Edison Dasok  
Institution: MSU-IIT, BS Information Technology  
Project title: Smishing Detection Using Keyword Pre-Filtering and On-Device LLM Contextual Analysis  
Target completion: December 2026

## What The App Does

KA-SDS listens for incoming SMS messages, checks the content against a local keyword database, computes a risk score, and saves the result in a Room database. If the message is risky enough, the app asks an on-device Gemma model to classify the message with more context.

Main user-facing areas:

- Messages: shows detected SMS records and their risk status.
- Message Detail: shows the message result, matched indicators, and Gemma rationale when available.
- Report Logs: tracks user-submitted reports.
- Safety Tips: displays anti-smishing guidance from local seed data.
- Onboarding/Setup: requests SMS permissions when needed.

## Detection Flow

```text
Incoming SMS
    |
    v
SmsReceiver
    |
    v
SmsController
    |
    v
KeywordEngine -> RiskScorer -> ThresholdEvaluator
    |
    +-- below threshold: save as SAFE
    |
    +-- at/above threshold:
            |
            v
        GemmaValidator
            |
            v
        GemmaOutputParser
            |
            v
        update saved Message result
```

Current threshold:

```kotlin
const val RISK_THRESHOLD = 60f
```

Messages with a risk score greater than or equal to this threshold trigger Gemma validation.

## Current Architecture

Package: `com.apcida.smishingdetector`  
Language: Kotlin  
Pattern: MVC-style separation  
Database: Room / SQLite  
Minimum SDK: 29  
Target SDK: 36  
LLM runtime: MediaPipe LLM Inference API  
MediaPipe version: `0.10.20`

```text
app/src/main/java/com/apcida/smishingdetector/
|
+-- model/
|   +-- entity/          Room entities: Message, Keyword, MessageKeyword, Report, SafetyTip
|   +-- data/            DetectionResult, GemmaResult, RiskLevel
|
+-- view/
|   +-- activity/        MainActivity
|   +-- fragment/        Onboarding, Messages, Message Detail, Report Logs, Safety Tips
|   +-- adapter/         RecyclerView adapters
|   +-- dialog/          Scam alert, safe notification, report form
|
+-- controller/
|   +-- SmsController.kt Main detection pipeline coordinator
|
+-- backend/
|   +-- receiver/        SmsReceiver
|   +-- detection/       KeywordEngine, RiskScorer, ThresholdEvaluator
|   +-- gemma/           GemmaValidator, PromptBuilder, GemmaOutputParser
|   +-- database/        AppDatabase, DatabaseSeeder, DAOs
|   +-- repository/      Message, Keyword, Report, Safety Tip repositories
|   +-- network/         ReportApiService, ReportUploadManager
|
+-- util/
    +-- Constants, DateUtil, HashUtil, PermissionHelper
```

## Important Files

| File | Purpose |
| --- | --- |
| `AndroidManifest.xml` | Declares SMS, internet, and network permissions; registers `SmsReceiver`; launches `MainActivity`. |
| `MainActivity.kt` | Sets up navigation, seeds local data, loads Gemma, uploads pending reports, and requests SMS permissions. |
| `SmsReceiver.kt` | Receives SMS broadcasts, combines multipart messages, and passes them to `SmsController`. |
| `SmsController.kt` | Runs duplicate checks, keyword matching, scoring, Gemma validation, and message persistence. |
| `KeywordEngine.kt` | Loads active keywords from Room and performs case-insensitive pattern matching. |
| `RiskScorer.kt` | Computes the total risk score from matched keyword weights. |
| `ThresholdEvaluator.kt` | Maps score to risk level and decides whether Gemma should run. |
| `GemmaValidator.kt` | Loads the `.task` model, runs MediaPipe inference, and falls back safely if unavailable. |
| `PromptBuilder.kt` | Builds the structured LLM prompt from SMS content and matched keywords. |
| `GemmaOutputParser.kt` | Parses Gemma output into classification, confidence, and rationale. |
| `AppDatabase.kt` | Room database with entities for messages, keywords, reports, safety tips, and message-keyword links. |
| `DatabaseSeeder.kt` | Seeds keywords and safety tips from XML assets on first launch. |
| `ReportUploadManager.kt` | Uploads unsent reports when internet is available. |

## Gemma Model Setup

The code expects the model file name:

```text
gemma3-1b-it-int4.task
```

`GemmaValidator` looks in these locations:

```text
context.filesDir/gemma3-1b-it-int4.task
/data/local/tmp/llm/gemma3-1b-it-int4.task
```

Recommended development setup:

```bash
adb shell mkdir -p /data/local/tmp/llm
adb push gemma3-1b-it-int4.task /data/local/tmp/llm/
```

Notes:

- Test Gemma on a physical Android device. The validator intentionally skips model loading on emulators.
- Do not commit `.task` or other large model files.
- `setTemperature` is currently commented out because the selected MediaPipe API version does not expose it the same way as older examples.

## Permissions

The app currently requests/declares:

- `RECEIVE_SMS`
- `READ_SMS`
- `INTERNET`
- `ACCESS_NETWORK_STATE`

SMS permissions are required for the receiver and message scanning flow.

## Report Server Design

Report upload is designed as a separate backend service from the Android app. The mobile app performs SMS detection locally, then sends only anonymized report metadata when the user submits a report.

The recommended prototype authentication design is API key plus timestamp validation:

```text
Android app
    |
    | HTTPS report request
    | Headers: X-API-Key, X-Timestamp
    v
Report server
    |
    +-- reject request if API key is invalid
    +-- reject request if timestamp is stale
    +-- store anonymized report metadata if valid
```

This validates that the report request came from an authorized app client. It does not validate the SMS message itself; the message classification is still produced by the on-device keyword and Gemma detection pipeline.

Prototype limitation: because the shared API key is packaged with the Android app, it could be extracted from the APK by a skilled attacker. This is acceptable for the thesis prototype if documented, but a production system should use stronger authentication.

The Android client keeps report uploads pending until both values are configured:

```kotlin
const val BASE_URL = "https://your-report-server.com/api/"
const val REPORT_API_KEY = ""
```

Recommended hosting approach:

1. Render or Railway with Node/Express and hosted Postgres. Best default for this thesis prototype because it is simple to deploy from GitHub, matches the Android Retrofit REST client, and provides enough database support for flagged-message reports.
2. Firebase Cloud Functions with Firestore. Good if the team wants less server management, but it adds Firebase-specific tooling and data modeling.
3. University VPS or on-campus server. Academically nice if MSU-IIT can provide quick access and setup support.
4. Self-managed VPS. More control, but more work than needed for a defense-scope report endpoint.

Minimal server endpoint:

```text
POST /api/reports/submit
Headers:
  X-API-Key: <shared app/reporting key>
  X-Timestamp: <request timestamp>
Body:
  anonymized report metadata only
```

Server responsibilities:

- Reject missing or invalid API keys.
- Reject stale timestamps.
- Store only anonymized report metadata.
- Return a clear success/failure JSON response.

Current Android retry behavior:

- `ReportUploadManager` attempts uploads on app startup.
- `ReportUploadManager` attempts uploads immediately after a user submits a report.
- If the device is offline, the server URL is still a placeholder, or the API key is empty, reports stay pending in Room.

Planned retry improvement:

- Add Android WorkManager.
- Schedule a report upload worker after report submission.
- Use a network constraint so the worker runs only when internet is available.
- Keep failed reports pending for later retry.

## What I Like About This Project

- The two-stage design is practical: keyword filtering keeps the app fast, while Gemma is reserved for higher-risk messages.
- The privacy story is strong because SMS detection happens locally and report uploads avoid sending raw SMS content.
- The Room entity split is clean enough for thesis documentation and future evaluation: messages, keywords, matched indicators, reports, and safety tips are separate.
- The Gemma integration has a safe fallback path, so detection can still continue even when the model is missing or unsupported.
- The project already has a good MVC-style folder structure, making it easier to explain in Chapter 4 and defend during evaluation.

## Current Issues To Fix

These are based on the current repository state:

- `BASE_URL` is still the placeholder `https://your-report-server.com/api/`.
- `REPORT_API_KEY` is still empty until the report server secret is finalized.

## Near-Term Roadmap

- Fix the safety tips seed filename mismatch. Done.
- Confirm local unit tests and debug APK build work. Done.
- Add focused unit tests for risk scoring, thresholding, and Gemma output parsing. Done.
- Confirm Room seeding works on a fresh install.
- Confirm Gemma model loading on a physical device through Logcat.
- Make Gemma loading shared or injectable so SMS processing uses the loaded model. Done.
- Implement notification display for scam results. Done.
- Connect report submission from the detail/report form flow to `ReportUploadManager`. Done.
- Add WorkManager-based automatic retry for pending report uploads.
- Rename `OnboardingActivity` to `OnboardingFragment` for clearer navigation structure. Done.
- Remove unused `SplashActivity` placeholder and keep `MainActivity` as launcher. Done.
- Run full pipeline testing: receive SMS -> keyword match -> risk score -> Gemma validation -> saved result -> alert/report.
- Prepare evaluation data for Chapter 5: accuracy, latency, false positives, false negatives, and usability feedback.

## Build And Test

Common commands:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew connectedAndroidTest
```

Recommended manual test cases:

- Safe ordinary message.
- Message with urgency words but no link.
- Message with bank/payment impersonation.
- Message with shortened URL.
- Multipart SMS message.
- Duplicate SMS content.
- Report submission while offline, then retry with internet available.

## Physical Device Test Checklist

Use this when an Android phone is available:

- Install the debug APK on the phone.
- Grant SMS and notification permissions.
- Push `gemma3-1b-it-int4.task` to `/data/local/tmp/llm/`.
- Confirm Logcat shows Gemma model loading or a clear fallback reason.
- Send a safe SMS sample and confirm it is saved as `SAFE`.
- Send suspicious/scam SMS samples and confirm keyword matches, score, Gemma result, and notification behavior.
- Open the notification and confirm it navigates to the message detail screen.
- Submit a report while offline and confirm it remains `Pending`.
- Reconnect internet and confirm report upload retry behavior when the server is configured.

## Report Server Setup Checklist

Use this when the separate report server is ready:

- Replace `BASE_URL` with the server API base URL.
- Set `REPORT_API_KEY` to the agreed shared secret for the prototype.
- Make the server require `X-API-Key` and `X-Timestamp` headers.
- Reject requests with an invalid key or stale timestamp.
- Confirm the server stores only anonymized report metadata.
- Test a successful report upload and confirm the local report changes from `Pending` to `Sent`.
- Test an invalid API key and confirm the app leaves the report pending for retry.

## Thesis Notes

This implementation supports a Design Science Research and iterative prototyping methodology. Chapter 5 should focus on measured results from the current pipeline:

- Detection accuracy against labeled SMS samples.
- Stage 1 score distribution and threshold calibration.
- Gemma validation impact on false positives.
- Processing time before and after Gemma invocation.
- Device compatibility and model loading constraints.
- User feedback on alert clarity and reporting workflow.
