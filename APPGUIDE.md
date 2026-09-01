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
|   +-- activity/        MainActivity, SplashActivity, OnboardingActivity
|   +-- fragment/        Messages, Message Detail, Report Logs, Safety Tips
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

## What I Like About This Project

- The two-stage design is practical: keyword filtering keeps the app fast, while Gemma is reserved for higher-risk messages.
- The privacy story is strong because SMS detection happens locally and report uploads avoid sending raw SMS content.
- The Room entity split is clean enough for thesis documentation and future evaluation: messages, keywords, matched indicators, reports, and safety tips are separate.
- The Gemma integration has a safe fallback path, so detection can still continue even when the model is missing or unsupported.
- The project already has a good MVC-style folder structure, making it easier to explain in Chapter 4 and defend during evaluation.

## Current Issues To Fix

These are based on the current repository state:

- `DatabaseSeeder.kt` reads `safety_tips_seed.xml`, but the assets folder currently contains `safety_tips.xml`. Either rename the asset or update the seeder.
- `GemmaValidator.kt` has comments that still mention Gemma 2B and assets loading, but the active path uses Gemma 3 1B INT4 from internal storage or `/data/local/tmp/llm`.
- `SmsController.triggerScamAlert()` is still a TODO. Scam detection is logged, but the actual notification helper is not implemented yet.
- `MainActivity` creates and loads one `GemmaValidator`, while `SmsController` creates a separate `GemmaValidator`. That means the receiver-side validator may not share the model instance loaded at startup.
- `SplashActivity.kt` exists, but `MainActivity` is currently the launcher in `AndroidManifest.xml`.
- `OnboardingActivity` is stored under `view/activity`, but it is used as a navigation fragment in `nav_graph.xml`. Consider renaming it to `OnboardingFragment` for clarity.
- `BASE_URL` is still the placeholder `https://your-report-server.com/api/`.

## Near-Term Roadmap

- Fix the safety tips seed filename mismatch.
- Confirm Room seeding works on a fresh install.
- Confirm Gemma model loading on a physical device through Logcat.
- Make Gemma loading shared or injectable so SMS processing uses the loaded model.
- Implement notification display for scam results.
- Connect report submission from the detail/report form flow to `ReportUploadManager`.
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

## Thesis Notes

This implementation supports a Design Science Research and iterative prototyping methodology. Chapter 5 should focus on measured results from the current pipeline:

- Detection accuracy against labeled SMS samples.
- Stage 1 score distribution and threshold calibration.
- Gemma validation impact on false positives.
- Processing time before and after Gemma invocation.
- Device compatibility and model loading constraints.
- User feedback on alert clarity and reporting workflow.
