# KA-SDS: Keyword-Augmented Smishing Detection System

Android application for detecting SMS phishing ("smishing") messages using a two-stage pipeline: on-device keyword/pattern analysis, followed by contextual validation from an on-device LLM (Gemma). Built as an undergraduate thesis project.

## How Detection Works

**Stage 1 — Keyword Analysis**
Incoming SMS text is checked against a seeded keyword/URL-pattern database. Matches are weighted and summed into a preliminary risk score. The score is not reduced for saved contacts. When contact names are enabled, contact membership is stored for the final verdict; it does not verify sender identity.

**Stage 2 — On-Device LLM Validation**
Every non-duplicate incoming message is queued for Gemma (running fully on-device via MediaPipe's LLM Inference API) along with the matched keywords and up to three earlier incoming SMS from the same sender within 48 hours. Short messages are still analyzed. The prompt asks Gemma to classify only the latest SMS.

**Combined risk points**
The app also records a separate 0–100 point breakdown: up to 25 from current message rules, 60 from a HIGH/MEDIUM-confidence Gemma SCAM result (30 for LOW), up to 25 from a Gemma-confirmed sender scam in the previous 24 hours (10 for an earlier LOW-confidence SCAM), and up to 5 for a concrete institution/sender mismatch. A score of 25 or more keeps an otherwise SAFE message SUSPICIOUS. The point total is not a probability and does not independently turn a message into SCAM. Saved-contact membership never subtracts points.

When a confirmed scam from the same sender is still within the 24-hour warning window, the prompt asks Gemma to explain the latest SMS in light of that sender history. The message detail screen leads its AI analysis section with the confirmed-history warning, including if the latest SMS looks harmless alone.

```
SMS received
    │
    ▼
Stage 1: KeywordEngine → RiskScorer → ThresholdEvaluator
    │
    └── every non-duplicate message, regardless of score
            │
            ▼
        Stage 2: GemmaValidator (PromptBuilder → on-device inference → GemmaOutputParser)
            │
            ▼
        FinalVerdictResolver (rule risk + Gemma result + contact and rule-signal evidence)
            │
            ▼
        CombinedRiskScorer (0–100 points; SUSPICIOUS minimum at 25)
            │
            ▼
        DetectionResult (SAFE / SUSPICIOUS / SCAM + rationale)
```

Repeated message text is analyzed when received again outside the short duplicate-broadcast window. Gemma loads on demand and inference is serialized for overlapping messages. If the model is unavailable or inference fails, rule-SCAM remains SCAM, rule-SUSPICIOUS remains SUSPICIOUS, and rule-SAFE is marked SUSPICIOUS for review. The Gemma result records that analysis was unavailable.

Keywords match complete phrases, and URL patterns are matched against extracted hosts. A saved contact can clear a rule-SUSPICIOUS result only when Gemma reports LEGITIMATE with HIGH confidence, no strong rule signal was found, and the combined score does not require a SUSPICIOUS warning. A rule-SCAM result stays flagged when it conflicts with a benign Gemma assessment. Gemma can still identify a scam when no keyword matches. Model-reported confidence is displayed but is not treated as a calibrated probability. See [DETECTION_EVALUATION.md](DETECTION_EVALUATION.md) for the labeling and measurement plan.

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
│   ├── ReportController.kt
│   └── SafetyTipController.kt
│
├── backend/
│   ├── detection/                # KeywordEngine, UrlCandidates, RiskScorer, FinalVerdictResolver
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
| `SmsController.kt` | Saves rule results, queues Gemma work, and persists final verdicts and alerts |
| `KeywordEngine.kt` | Loads active patterns from the keyword DAO and matches message phrases and URL hosts |
| `RiskScorer.kt` | Sums weights of matched keywords/patterns into a risk score |
| `ThresholdEvaluator.kt` | Maps the keyword score to a preliminary risk level; does not gate Gemma |
| `FinalVerdictResolver.kt` | Combines the rule result, Gemma result, and saved-contact evidence into the final verdict |
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
<uses-permission android:name="android.permission.READ_CONTACTS"/>
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
This project uses **Gemma 3 1B IT (INT4)**. Download a MediaPipe-compatible `.task` model, then copy it to the test device instead of bundling it in `assets/`:

```bash
adb shell mkdir -p /data/local/tmp/llm
adb push gemma3-1b-it-int4.task /data/local/tmp/llm/
adb shell ls -lh /data/local/tmp/llm/gemma3-1b-it-int4.task
```

The file name must match `gemma3-1b-it-int4.task`. Gemma is best tested on a physical device; Android emulators are not reliably supported by MediaPipe. To diagnose a fallback result, check Logcat for `GemmaValidator` and confirm that the model is readable at the path above. The separate `.litertlm` test model cannot be loaded by this MediaPipe path. MediaPipe's `maxTokens` covers the prompt **and** the response; the current limit is 768 tokens. The inbox shows the rule result while AI is pending, then updates when Gemma finishes or becomes unavailable. An unavailable analysis can be retried from the message detail screen.

The model file is large — do **not** commit it to GitHub. Add to `.gitignore`:
```
*.task
*.bin
```

## Notes

- Keyword scores determine preliminary risk: 0–29 SAFE, 30–59 SUSPICIOUS, 60+ SCAM. Every score proceeds to Gemma.
- The reporting API sends anonymized metadata; it does not upload SMS content, sender addresses, or contact names.
- New messages store the sender address locally so it can be shown in the inbox and detail screen. Existing messages keep "Unknown Sender" because their saved hashes cannot recover the address.
- Contact names are optional. The inbox offers contact access once and keeps a Show/Hide contact names menu option. The app looks up only saved SMS sender numbers and does not save or upload contact names. Unknown numbers and alphanumeric sender IDs display as received. Contact membership does not change either numeric score; the existing final-verdict policy uses it only under strict conditions.

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
