# KA-SDS: Keyword-Augmented Smishing Detection System

## Supervised beta: older SMS analysis

In an explicitly enabled beta build, **Messages → More → Analyze up to 3 older SMS with AI** previews the next three locally screened SMS from before the current AI Protection consent. The confirmation explains that the full text, sender, and date of just those messages will be sent to the private server. The app processes them one at a time and shows progress above the inbox. **More → Stop older SMS analysis** stops the remaining work. To check more older messages, start another batch and confirm it separately. This action requires AI Protection consent, an enrolled phone, and network access. It does not change the automatic handling of new SMS or add messages to RAG.

> **Current supervised beta (November 2026):** Incoming SMS receives local keyword screening. An explicitly enabled beta build can send new SMS to remote Gemma 3 12B + RAG after per-phone enrollment and AI Protection consent. Ordinary debug and release builds keep this route closed. Older SMS remain local unless the user approves a separate small batch below. A zero-rule message without a completed remote result is **UNVERIFIED**, not SAFE. The detector description below documents the earlier on-device prototype and is retained for historical evaluation context.

Android application for detecting SMS phishing ("smishing") messages using a two-stage pipeline: on-device keyword/pattern analysis, followed by contextual validation from an on-device LLM (Gemma). Built as an undergraduate thesis project.

For labeled, on-device detector measurement, see [BATCH_EVALUATION.md](BATCH_EVALUATION.md). The CSV evaluation screen is available only in the isolated debug APK.

## How Detection Works

**Stage 1 — Keyword Analysis**
Incoming SMS text is checked against a seeded keyword/URL-pattern database. Phrase matches use letter/digit boundaries, and URL rules inspect extracted hosts. Broad brand and service terms have no standalone risk weight; ordinary links, shorteners, and top-level domains receive limited weight. Overlapping phrases count once, as does the strongest URL pattern for each host. Runtime weight caps also apply to older installations whose database retains the original seed. The rule score is not reduced for saved contacts; contact membership does not verify sender identity.

**Stage 2 — On-Device LLM Validation**
Every non-duplicate incoming message is queued for Gemma (running fully on-device via MediaPipe's LLM Inference API) along with the matched keywords and up to three earlier incoming SMS from the same sender within 48 hours. Short messages are still analyzed. The prompt asks Gemma to classify only the latest SMS.

**Combined risk points**
The app also records a separate 0–100 point breakdown: up to 25 from current message rules, 60 from a HIGH/MEDIUM-confidence Gemma SCAM result (30 for LOW), up to 25 from a prior scam warning for the same sender, and up to 5 for a concrete institution/sender mismatch. A score of 25 or more keeps an otherwise SAFE message SUSPICIOUS. The point total is not a probability and does not independently turn a message into SCAM. Short neutral conversation without a link, request, or fraud cue receives no Gemma scam points.

When a prior message classified as a scam from the same sender is still within the 24-hour warning window, the prompt asks Gemma to explain the latest SMS in light of that sender history. The message detail screen explains the final verdict first. It shows sender history when relevant and puts the raw AI result, rationale, and self-reported confidence under “Show analysis details.”

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
        FinalVerdictResolver (rule risk + Gemma result + rule-signal evidence)
            │
            ▼
        CombinedRiskScorer (0–100 points; SUSPICIOUS minimum at 25)
            │
            ▼
        DetectionResult (SAFE / SUSPICIOUS / SCAM + rationale)
```

Repeated message text is analyzed when received again outside the short duplicate-broadcast window. Gemma loads on demand and inference is serialized for overlapping messages. If inference fails, the rule verdict remains in effect and the AI status records that analysis was unavailable. A rule-SAFE message is not made suspicious merely because the model failed. A recent scam warning for the same sender can still raise the final verdict to SUSPICIOUS.

Gemma's SCAM answer without independent strong rule evidence produces a SUSPICIOUS warning, while a strong credential/payment request or a high-risk URL can support a definitive SCAM verdict. A HIGH-confidence LEGITIMATE result can clear a weak rule-SUSPICIOUS warning, but strong rule evidence remains flagged. Brief neutral messages without a link, request, or fraud cue can stay SAFE despite an unsupported Gemma SCAM answer. The app logs rule, Gemma, confidence, final verdict, and AI status without logging SMS text. Model-reported confidence is displayed but is not a calibrated probability. See [DETECTION_EVALUATION.md](DETECTION_EVALUATION.md) for the labeling and measurement plan.

SMS classification runs on-device. Optional reports and community contributions can share SMS text after the participant reviews the disclosure; community text is redacted before upload.

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
- The report upload sends the selected issue, optional remarks, observed verdict, rule score, and the SMS text only after the reporter checks one required consent for review and possible redacted RAG reuse. Sender addresses and contact names are not uploaded by the report route.
- New messages store the sender address locally so it can be shown in the inbox and detail screen. Existing messages keep "Unknown Sender" because their saved hashes cannot recover the address.
- Contact names are optional. The inbox offers contact access once and keeps a Show/Hide contact names menu option. The app looks up only saved SMS sender numbers and does not save or upload contact names. Unknown numbers and alphanumeric sender IDs display as received. Contact membership does not change either numeric score; the existing final-verdict policy uses it only under strict conditions.

## Private report submission

Reporting is optional feedback for a saved message that was not scanned or whose scan result seems wrong. The Report form previews the SMS text and requires one unchecked-by-default consent for private admin review and possible inclusion of a redacted, approved version in RAG. Without the message text and consent, Submit stays disabled. Submitting saves a local snapshot, then WorkManager queues an upload to the controlled API at `https://sms.apcida.stream/api/v1/reports`. The app uses that phone's invite-issued installation credential and Cloudflare Access service token, which are stored with Android Keystore. There is no shared report API key in the APK.

The server stores encrypted reports in a table separate from RAG. A report is received for human review; it does not change the AI example library automatically. A reviewer must verify the label, remove personal details, and explicitly approve any RAG candidate. Server report copies expire after 30 days and are purged hourly; an approved RAG example may remain longer. Removal of service access deletes the report sooner. The local copy stays on the phone until deleted there. Reports saved by older builds lack the combined consent and stay local after an upgrade.

Uploads require an enrolled phone and the approved HTTPS host in the build. The Reports screen shows **Pending** while waiting and **Received for review** after the server acknowledges the report ID. Retries reuse the same ID so a lost response does not create a second report. The backend's `PHASE8_SECURE_REPORTS.md` describes the server setup and review workflow.

## Roadmap

- [x] Project setup, Room database, XML seed data
- [x] SMS receiver + Stage 1 keyword/risk pipeline
- [x] Core UI: message list, detail view, alert dialogs
- [x] Gemma integration (Stage 2 on-device validation)
- [ ] Report submission + upload manager polish
- [x] WorkManager-based automatic report retry
- [ ] Safety tips screen + UI polish
- [ ] Full pipeline testing and user evaluation
