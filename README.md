================================================================
KA-SDS: Smishing Detection Using Keyword Pre-Filtering
and On-Device LLM Contextual Analysis
Android Project Folder Structure (MVC Architecture)
================================================================

PROJECT ROOT: KA_SDS/
│
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/kasds/smishingdetector/
│   │   │   │   │
│   │   │   │   ├── 📁 model/                          ← DATA MODELS (M in MVC)
│   │   │   │   │   ├── entity/
│   │   │   │   │   │   ├── Message.kt                 ← messages table entity
│   │   │   │   │   │   ├── Keyword.kt                 ← keywords table entity
│   │   │   │   │   │   ├── MessageKeyword.kt          ← junction table entity
│   │   │   │   │   │   ├── Report.kt                  ← reports table entity
│   │   │   │   │   │   └── SafetyTip.kt               ← safety_tips table entity
│   │   │   │   │   │
│   │   │   │   │   └── data/
│   │   │   │   │       ├── DetectionResult.kt         ← Stage 1 + Stage 2 combined result
│   │   │   │   │       ├── GemmaResult.kt             ← Gemma output (classification, confidence, rationale)
│   │   │   │   │       └── RiskLevel.kt               ← Enum: SAFE, SUSPICIOUS, SCAM
│   │   │   │   │
│   │   │   │   ├── 📁 view/                           ← UI SCREENS (V in MVC)
│   │   │   │   │   ├── activity/
│   │   │   │   │   │   ├── SplashActivity.kt          ← app entry point
│   │   │   │   │   │   ├── OnboardingActivity.kt      ← permission request screen
│   │   │   │   │   │   └── MainActivity.kt            ← main container activity
│   │   │   │   │   │
│   │   │   │   │   ├── fragment/
│   │   │   │   │   │   ├── MessagesFragment.kt        ← home: SMS inbox list
│   │   │   │   │   │   ├── MessageDetailFragment.kt   ← message detail + detection result
│   │   │   │   │   │   ├── ReportLogsFragment.kt      ← submitted reports history
│   │   │   │   │   │   └── SafetyTipsFragment.kt      ← educational tips screen
│   │   │   │   │   │
│   │   │   │   │   ├── dialog/
│   │   │   │   │   │   ├── ScamAlertDialog.kt         ← scam alert popup with Gemma rationale
│   │   │   │   │   │   ├── SafeNotificationDialog.kt  ← safe message notification
│   │   │   │   │   │   └── ReportFormDialog.kt        ← report submission form
│   │   │   │   │   │
│   │   │   │   │   └── adapter/
│   │   │   │   │       ├── MessageListAdapter.kt      ← RecyclerView adapter for SMS list
│   │   │   │   │       ├── ReportLogAdapter.kt        ← RecyclerView adapter for report logs
│   │   │   │   │       └── SafetyTipAdapter.kt        ← RecyclerView adapter for tips
│   │   │   │   │
│   │   │   │   ├── 📁 controller/                     ← LOGIC CONTROLLERS (C in MVC)
│   │   │   │   │   ├── SmsController.kt               ← coordinates SMS receive → detection pipeline
│   │   │   │   │   ├── DetectionController.kt         ← orchestrates Stage 1 → Stage 2 flow
│   │   │   │   │   ├── ReportController.kt            ← handles report submission logic
│   │   │   │   │   └── SafetyTipController.kt         ← loads and refreshes safety tips
│   │   │   │   │
│   │   │   │   ├── 📁 backend/                        ← BACKEND SERVICES
│   │   │   │   │   │
│   │   │   │   │   ├── detection/
│   │   │   │   │   │   ├── KeywordEngine.kt           ← Stage 1: tokenize, match, score
│   │   │   │   │   │   ├── UrlPatternScanner.kt       ← URL pattern detection and scoring
│   │   │   │   │   │   ├── RiskScorer.kt              ← computes weighted risk score
│   │   │   │   │   │   └── ThresholdEvaluator.kt      ← compares score vs threshold
│   │   │   │   │   │
│   │   │   │   │   ├── gemma/
│   │   │   │   │   │   ├── GemmaValidator.kt          ← Stage 2: invokes Gemma on-device
│   │   │   │   │   │   ├── PromptBuilder.kt           ← builds structured prompt from message + keywords
│   │   │   │   │   │   └── GemmaOutputParser.kt       ← parses Classification/Confidence/Reason
│   │   │   │   │   │
│   │   │   │   │   ├── receiver/
│   │   │   │   │   │   └── SmsReceiver.kt             ← BroadcastReceiver for incoming SMS
│   │   │   │   │   │
│   │   │   │   │   ├── repository/
│   │   │   │   │   │   ├── MessageRepository.kt       ← CRUD for messages table
│   │   │   │   │   │   ├── KeywordRepository.kt       ← CRUD for keywords table
│   │   │   │   │   │   ├── ReportRepository.kt        ← CRUD for reports table
│   │   │   │   │   │   └── SafetyTipRepository.kt     ← CRUD for safety_tips table
│   │   │   │   │   │
│   │   │   │   │   ├── database/
│   │   │   │   │   │   ├── AppDatabase.kt             ← Room database instance
│   │   │   │   │   │   └── DatabaseSeeder.kt          ← loads XML seed data into SQLite on first launch
│   │   │   │   │   │
│   │   │   │   │   └── network/
│   │   │   │   │       ├── ReportApiService.kt        ← Retrofit interface for HTTP POST reports
│   │   │   │   │       └── ReportUploadManager.kt     ← queues and retries failed report uploads
│   │   │   │   │
│   │   │   │   └── 📁 util/
│   │   │   │       ├── Constants.kt                   ← threshold value, score weights, API URLs
│   │   │   │       ├── HashUtil.kt                    ← content_hash generator for duplicate detection
│   │   │   │       ├── PermissionHelper.kt            ← SMS permission request helper
│   │   │   │       └── DateUtil.kt                    ← timestamp formatting helpers
│   │   │   │
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_splash.xml
│   │   │   │   │   ├── activity_onboarding.xml
│   │   │   │   │   ├── activity_main.xml
│   │   │   │   │   ├── fragment_messages.xml
│   │   │   │   │   ├── fragment_message_detail.xml
│   │   │   │   │   ├── fragment_report_logs.xml
│   │   │   │   │   ├── fragment_safety_tips.xml
│   │   │   │   │   ├── dialog_scam_alert.xml
│   │   │   │   │   ├── dialog_safe_notification.xml
│   │   │   │   │   ├── dialog_report_form.xml
│   │   │   │   │   ├── item_message.xml               ← single SMS item in list
│   │   │   │   │   ├── item_report_log.xml
│   │   │   │   │   └── item_safety_tip.xml
│   │   │   │   │
│   │   │   │   ├── xml/
│   │   │   │   │   ├── keywords_seed.xml              ← initial keyword + weight data
│   │   │   │   │   ├── url_patterns_seed.xml          ← initial URL pattern data
│   │   │   │   │   └── safety_tips_seed.xml           ← initial safety tips content
│   │   │   │   │
│   │   │   │   ├── values/
│   │   │   │   │   ├── strings.xml
│   │   │   │   │   ├── colors.xml
│   │   │   │   │   └── themes.xml
│   │   │   │   │
│   │   │   │   └── drawable/
│   │   │   │       ├── ic_warning.xml
│   │   │   │       ├── ic_safe.xml
│   │   │   │       └── ic_report.xml
│   │   │   │
│   │   │   └── AndroidManifest.xml
│   │   │
│   │   └── assets/
│   │       └── gemma/
│   │           └── gemma-2b-it-gpu-int4.bin           ← Gemma 2B INT4 model file (downloaded separately)
│   │
│   └── build.gradle (app level)
│
├── build.gradle (project level)
├── settings.gradle
└── README.md

================================================================
KEY FILE RESPONSIBILITIES — QUICK REFERENCE
================================================================

SmsReceiver.kt
  - Listens for incoming SMS via BroadcastReceiver
  - Passes raw message to SmsController

SmsController.kt
  - Entry point of the detection pipeline
  - Calls DetectionController with message content

DetectionController.kt
  - Runs Stage 1: calls KeywordEngine → RiskScorer → ThresholdEvaluator
  - If score > threshold: runs Stage 2 via GemmaValidator
  - Combines results into DetectionResult
  - Saves to MessageRepository
  - Triggers UI via ScamAlertDialog or SafeNotificationDialog

KeywordEngine.kt
  - Tokenizes message text
  - Queries KeywordRepository for matches
  - Returns list of matched keywords with weights

RiskScorer.kt
  - Sums weights of matched keywords and URL patterns
  - Returns total risk score (float)

ThresholdEvaluator.kt
  - Compares risk score against Constants.RISK_THRESHOLD
  - Returns: BELOW_THRESHOLD or ABOVE_THRESHOLD

GemmaValidator.kt
  - Loads Gemma model via MediaPipe LLM Inference API
  - Calls PromptBuilder to construct structured prompt
  - Runs inference
  - Passes raw output to GemmaOutputParser
  - Returns GemmaResult

PromptBuilder.kt
  - Builds structured prompt string:
    System instruction + Message text + Detected keyword list
  - Enforces strict output format requirement

GemmaOutputParser.kt
  - Parses Gemma text output
  - Extracts: Classification, Confidence, Reason
  - Returns GemmaResult data class
  - Handles malformed output gracefully

DatabaseSeeder.kt
  - Runs on first app launch
  - Reads keywords_seed.xml and url_patterns_seed.xml
  - Populates keywords table in SQLite

ReportUploadManager.kt
  - Checks is_sent = false in reports table
  - Retries HTTP POST when internet available
  - Updates is_sent = true on success

Constants.kt
  - RISK_THRESHOLD = 60 (adjustable during testing)
  - BASE_URL for report server
  - Weight values for quick reference

================================================================
BUILD ORDER RECOMMENDATION
================================================================

Week 1-2  : Setup project, implement database (Room), seed XML files
Week 3-4  : SmsReceiver, KeywordEngine, RiskScorer, ThresholdEvaluator
Week 5-6  : MessagesFragment, MessageDetailFragment, basic alert dialogs
Week 7-8  : GemmaValidator, PromptBuilder, GemmaOutputParser
Week 9-10 : ReportFormDialog, ReportRepository, ReportUploadManager
Week 11-12: SafetyTipsFragment, UI polish, full pipeline testing
Week 13+  : User evaluation, bug fixes, final demo preparation

================================================================
DEPENDENCIES TO ADD IN build.gradle (app)
================================================================

// Room Database
implementation "androidx.room:room-runtime:2.6.1"
kapt "androidx.room:room-compiler:2.6.1"
implementation "androidx.room:room-ktx:2.6.1"

// MediaPipe LLM Inference (Gemma)
implementation "com.google.mediapipe:tasks-genai:0.10.14"

// Networking (Report upload)
implementation "com.squareup.retrofit2:retrofit:2.9.0"
implementation "com.squareup.retrofit2:converter-gson:2.9.0"
implementation "com.squareup.okhttp3:okhttp:4.12.0"
implementation "com.squareup.okhttp3:logging-interceptor:4.12.0"

// Coroutines (async operations)
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
implementation "androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0"

// Navigation Component
implementation "androidx.navigation:navigation-fragment-ktx:2.7.6"
implementation "androidx.navigation:navigation-ui-ktx:2.7.6"

================================================================
PERMISSIONS TO ADD IN AndroidManifest.xml
================================================================

<uses-permission android:name="android.permission.RECEIVE_SMS"/>
<uses-permission android:name="android.permission.READ_SMS"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>

================================================================
NOTES
================================================================

1. Download the Gemma model file separately from:
   https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android
   Place it in: app/src/main/assets/gemma/

2. The Gemma model file (~1.3GB) should NOT be committed to GitHub.
   Add to .gitignore:
   app/src/main/assets/gemma/

3. RISK_THRESHOLD default is 60 but should be calibrated during testing.
   Adjust in Constants.kt.

4. All SMS processing happens on-device. No raw SMS content is ever
   transmitted to the report server — only anonymized metadata.
