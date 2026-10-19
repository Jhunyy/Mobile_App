# KA-SDS: Keyword-Augmented Smishing Detection System

Android application for detecting SMS phishing ("smishing") messages using a two-stage pipeline: a lightweight on-device keyword/pattern pre-filter, followed by contextual validation from an on-device LLM (Gemma). Built as an undergraduate thesis project.

## How Detection Works

**Stage 1 — Keyword Pre-Filtering**
Incoming SMS text is tokenized and checked against a seeded keyword/URL-pattern database. Matches are weighted and summed into a risk score, which is compared against a configurable threshold.

**Stage 2 — On-Device LLM Validation**
Messages that cross the Stage 1 threshold are passed to Gemma (running fully on-device via MediaPipe's LLM Inference API) along with the matched keywords, for a contextual classification, confidence score, and rationale.

```
SMS received
    │
    ▼
Stage 1: KeywordEngine → RiskScorer → ThresholdEvaluator
    │
    ├── below threshold ──► marked safe
    │
    └── above threshold
            │
            ▼
        Stage 2: GemmaValidator (PromptBuilder → on-device inference → GemmaOutputParser)
            │
            ▼
        DetectionResult (SAFE / SUSPICIOUS / SCAM + rationale)
```

All SMS processing happens on-device. No raw SMS content is ever transmitted off the device — only anonymized metadata is sent when a user submits a report.

## Architecture

MVC pattern, Kotlin, Min SDK 29.

```
app/src/main/java/com/apcida/smishingdetector/
│
├── model/                       # Data layer
│   ├── entity/                  # Room entities: Message, Keyword, MessageKeyword, Report, SafetyTip
│   └── data/                    # DetectionResult, GemmaResult, RiskLevel enum
│
├── view/                        # UI layer
│   ├── activity/                # MainActivity
│   ├── fragment/                # OnboardingFragment, MessagesFragment, MessageDetailFragment, ReportLogsFragment, SafetyTipsFragment
│   ├── dialog/                  # ScamAlertDialog, SafeNotificationDialog, ReportFormDialog
│   └── adapter/                 # RecyclerView adapters
│
├── controller/                  # Coordinates model/view interactions
│   ├── SmsController.kt         # SMS receive → detection pipeline
│   ├── DetectionController.kt   # Orchestrates Stage 1 → Stage 2
│   ├── ReportController.kt
│   └── SafetyTipController.kt
│
├── backend/
│   ├── detection/                # KeywordEngine, UrlPatternScanner, RiskScorer, ThresholdEvaluator
│   ├── gemma/                    # GemmaValidator, PromptBuilder, GemmaOutputParser
│   ├── receiver/                 # SmsReceiver (BroadcastReceiver)
│   ├── repository/               # CRUD repositories for each Room entity
│   ├── database/                 # AppDatabase, DatabaseSeeder
│   ├── network/                  # ReportApiService, ReportUploadManager
│   └── worker/                   # Persistent SMS analysis and report upload
│
└── util/                         # Constants, HashUtil, PermissionHelper, DateUtil
```

## Key Components

| File | Responsibility |
|---|---|
| `SmsReceiver.kt` | Listens for incoming SMS, forwards to `SmsController` |
| `SmsController.kt` | Entry point of the detection pipeline |
| `DetectionController.kt` | Runs Stage 1; if above threshold, runs Stage 2; saves and displays result |
| `KeywordEngine.kt` | Tokenizes message, matches against `KeywordRepository` |
| `RiskScorer.kt` | Sums weights of matched keywords/patterns into a risk score |
| `ThresholdEvaluator.kt` | Compares score against `Constants.RISK_THRESHOLD` |
| `GemmaValidator.kt` | Loads Gemma via MediaPipe LLM Inference API, runs on-device inference |
| `PromptBuilder.kt` | Builds the structured prompt (system instruction + message + keywords) |
| `GemmaOutputParser.kt` | Parses Gemma's output into classification, confidence, and reason |
| `DatabaseSeeder.kt` | Seeds the keyword/pattern/tip tables from XML on first launch |
| `ReportUploadManager.kt` | Sends pending reports and marks server-acknowledged reports as sent |
| `ReportUploadWorker.kt` | Retries pending report uploads with a network constraint |

## Setup

### Prerequisites
- Android Studio, min SDK 29
- A physical device is recommended for Gemma inference (the emulator lacks GPU support required by MediaPipe's LLM Inference API)

### Dependencies (`app/build.gradle`)
```gradle
// Room Database
implementation "androidx.room:room-runtime:2.6.1"
kapt "androidx.room:room-compiler:2.6.1"
implementation "androidx.room:room-ktx:2.6.1"

// MediaPipe LLM Inference (Gemma)
implementation "com.google.mediapipe:tasks-genai:0.10.35"

// Networking (report upload)
implementation "com.squareup.retrofit2:retrofit:2.9.0"
implementation "com.squareup.retrofit2:converter-gson:2.9.0"
implementation "com.squareup.okhttp3:okhttp:4.12.0"
implementation "com.squareup.okhttp3:logging-interceptor:4.12.0"

// Coroutines
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
implementation "androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0"

// Navigation Component
implementation "androidx.navigation:navigation-fragment-ktx:2.7.6"
implementation "androidx.navigation:navigation-ui-ktx:2.7.6"
```

### Permissions (`AndroidManifest.xml`)
```xml
<uses-permission android:name="android.permission.RECEIVE_SMS"/>
<uses-permission android:name="android.permission.READ_SMS"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
```

### Send test SMS messages from Terminal (Android Emulator)

Start the `Medium_Phone` emulator, open KA-SDS, and grant its SMS permissions.
Then paste these commands into a Mac Terminal:

```bash
cd ~/Documents/apcida/Mobile_App
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" devices
"$ADB" -s emulator-5554 emu sms send 6505550100 "See you tomorrow at 3 pm."
"$ADB" -s emulator-5554 emu sms send 6505550100 "Urgent: verify your bank account at https://example.com/test"
```

The sender number is a test value. Open the app's **Messages** tab to confirm
the messages arrived. If `adb devices` shows a different
emulator ID, replace `emulator-5554` in the commands. Change the message text
when repeating a test because the app suppresses recent duplicates. This
command works with an Android emulator; a physical phone needs a real incoming
SMS. See the [Android emulator SMS command documentation](https://developer.android.com/studio/run/emulator-console).

### Gemma Model
This project uses **Gemma 3 1B IT (INT4)**. Push the `.task` model file to a physical device via ADB rather than bundling it in `assets/`:

```bash
adb push gemma3-1b-it-int4.task /data/local/tmp/llm/
```

The model file is large — do **not** commit it to GitHub. Add to `.gitignore`:
```
*.task
*.bin
```

## Notes

- `Constants.RISK_THRESHOLD` defaults to `60f`; calibrate during testing.
- Privacy by design: raw SMS content never leaves the device. Only anonymized metadata is sent when a report is submitted.

## Report Server Plan

Report collection is handled by a separate server, not by the Android app itself. Reporting is optional feedback for a saved message that was not scanned or whose scan result seems wrong. The report button is available for safe and flagged messages. Only after the user submits the form does the app send anonymized report metadata to the configured `BASE_URL` over HTTPS.

The default reporting API base URL is `https://apcida-server.onrender.com/api/`.
To enable uploads in a local Android build, add the key configured on the
deployed server to the ignored root `local.properties` file:

```properties
apcida.reportApiKey=<same key configured on the deployed server>
```

For CI, use the `APCIDA_REPORT_API_KEY` environment variable instead. The
optional `apcida.reportBaseUrl` property or `APCIDA_REPORT_BASE_URL` environment
variable can override the default URL; the URL must end in `/api/`. If no key
is supplied, reports remain pending on the device. Do not commit the key.

For the thesis prototype, report submissions should be authenticated with a shared API key and timestamp:

```text
X-API-Key: <shared app/reporting key>
X-Timestamp: <request timestamp>
```

The server checks the API key and rejects stale timestamps before accepting the report. This validates that the report request came from an authorized app client, while the SMS detection decision itself still comes from the on-device keyword and Gemma pipeline.

Recommended hosting for the prototype: **Render or Railway with a small Node/Express REST API and hosted Postgres**. This keeps deployment simple, works naturally with Retrofit, and is enough for accepting optional scan feedback during thesis evaluation. Firebase Cloud Functions + Firestore is a reasonable second option if the team wants less server management, while a university VPS is useful only if access and setup support are readily available.

Report uploads use WorkManager with a network constraint and retry after temporary failures. The app schedules an attempt on startup, after report submission, and when the Reports screen opens. A report stays Pending until the server acknowledges it with `success: true` and a report ID; then the local row changes to Sent. If the server stored a report but the app lost the response, the retry sends the same payload and the server returns its existing report ID. The request timeout is 90 seconds to allow the free server to wake from idle. Builds without a reporting key leave reports Pending.

## Roadmap

- [x] Project setup, Room database, XML seed data
- [x] SMS receiver + Stage 1 keyword/risk pipeline
- [x] Core UI: message list, detail view, alert dialogs
- [x] Gemma integration (Stage 2 on-device validation)
- [ ] Report submission + upload manager polish
- [x] WorkManager-based automatic report retry
- [ ] Safety tips screen + UI polish
- [ ] Full pipeline testing and user evaluation
