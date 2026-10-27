# On-device batch detection evaluation

This developer-only screen evaluates labeled SMS text with the app's active keyword database, rule scorer, CPU MediaPipe Gemma model, and the same final-decision function used by live SMS processing. It does not send SMS, insert evaluation messages into Room, show scam notifications, or call the community or report APIs. The debug APK has a separate package (`com.apcida.smishingdetector.debug`) and its SMS receiver is disabled. The release APK has no evaluation screen.

## Run on an Android phone

1. Prepare a consented, independently labeled UTF-8 CSV. Keep names, phone numbers, OTPs, and other personal details out of research exports. Do not use the app's predicted category or a participant's reviewed category as the verified label.
2. Install the debug build with `./gradlew :app:installDebug`. Open **KA-SDS Evaluation (debug)** from the app launcher.
3. Install a readable `gemma3-1b-it-int4.task` model for the debug package. The existing `GemmaValidator` looks in that package's private `filesDir` or `/data/local/tmp/llm/gemma3-1b-it-int4.task`. The latter can be prepared with `adb shell mkdir -p /data/local/tmp/llm` and `adb push gemma3-1b-it-int4.task /data/local/tmp/llm/`. Confirm that the model is readable on the target phone. The separate `.litertlm` test artifact does not work with this MediaPipe path.
4. Enter a detector version or Git commit identifier, select the CSV with Android's file picker, then tap **Start batch evaluation**. Keep the screen open until it finishes. Processing is sequential; a failed Gemma attempt can retry up to three times, with the app's 90-second limit per attempt.
5. Review the aggregate results. Tap each export button and choose an on-device destination through Android's document picker. Until you export, details and summary exist only in memory and disappear if the activity is closed or the process is killed.

The CSV must contain `message_id`, `message_text`, and `verified_label`. Labels are `SAFE` or `SCAM`. Quoted commas, quotes, and line breaks are accepted. Duplicate IDs, missing text, invalid labels, and malformed timestamps appear as per-row errors when the file can be parsed. Rows with errors are excluded from metrics and sender history.

```csv
message_id,message_text,verified_label
001,"Hello, how are you?",SAFE
002,"Your bank account is locked. Send your OTP to restore access.",SCAM
```

For same-sender context, add optional `sender_address`, `received_at`, and `is_saved_contact` columns. `received_at` must be an ISO 8601 UTC timestamp. Put rows for each sender in chronological order. Only earlier rows in this CSV contribute to the prompt's three-message/48-hour history and the 24-hour scam warning; verified labels are never fed into that history. A row without both sender and time is marked `MESSAGE_ONLY`. `is_saved_contact` is optional and defaults to false; the runner does not read the phone's contacts.

```csv
message_id,message_text,verified_label,sender_address,received_at,is_saved_contact
001,"Please send your OTP",SCAM,SENDER_A,2026-10-25T08:00:00Z,false
002,"Hello",SAFE,SENDER_A,2026-10-25T08:05:00Z,false
```

## Outputs and metric meanings

`evaluation_details.csv` has one row per input message. It includes the run ID, detector version, model name, timestamp, message ID and actual message text, verified label, rule score and verdict, matched indicators, Gemma classification/confidence/reason/status, final verdict, inference time, context mode, and any error. Treat exported message text as sensitive research data. An unavailable Gemma result has no model classification or confidence; its final verdict is still evaluated as a **pipeline** result. `inference_time_ms` covers model loading and retries for that row.

```csv
"evaluation_run_id","detector_version","model_name","evaluation_timestamp","message_id","message_text","verified_label","rule_score","rule_verdict","matched_indicators","gemma_classification","gemma_confidence","gemma_reason","gemma_inference_status","final_verdict","inference_time_ms","context_mode","error"
"run-123","policy-v1","gemma3-1b-it-int4.task","2026-10-25T08:00:00Z","001","Hello, how are you?","SAFE","0.0","SAFE","","LEGITIMATE","HIGH","Greeting","AVAILABLE","SAFE","4000","MESSAGE_ONLY",""
```

`evaluation_summary.csv` has metric rows keyed by run ID, detector version, model, timestamp, scenario, and metric. `THREE_WAY` includes the original 2×3 confusion matrix (verified `SAFE`/`SCAM` by final `SAFE`/`SUSPICIOUS`/`SCAM`), exact accuracy, counts, and errors. `STRICT_SCAM_ONLY` counts only final `SCAM` as a positive detection. `WARNING_SCAM_OR_SUSPICIOUS` counts both warning verdicts as positive. Each binary scenario reports TP, FP, TN, FN, accuracy, scam precision/recall/F1, safe recall, false-positive rate, and false-negative rate. `INFERENCE` reports Gemma successes/failures and mean/median time for successful inference only. An undefined rate is `N/A`, not zero.

```csv
evaluation_run_id,detector_version,model_name,evaluation_timestamp,scenario,metric,value
run-123,policy-v1,gemma3-1b-it-int4.task,2026-10-25T08:00:00Z,THREE_WAY,total_evaluated,200
run-123,policy-v1,gemma3-1b-it-int4.task,2026-10-25T08:00:00Z,STRICT_SCAM_ONLY,scam_recall,0.850000
run-123,policy-v1,gemma3-1b-it-int4.task,2026-10-25T08:00:00Z,WARNING_SCAM_OR_SUSPICIOUS,scam_recall,0.930000
```

These numbers illustrate the format only; they are not measured KA-SDS results. CSV text fields that begin with spreadsheet formula characters receive an apostrophe prefix on export to prevent spreadsheet execution.

## Before-and-after comparison

Run **the same frozen CSV** on both detector versions and give each run a distinct `detector_version` value, preferably its Git commit ID plus a policy label. Export both files before switching builds. The earlier Git implementation is available in repository history (for example, `git log --oneline` and `git worktree add ../apcida-legacy <old-commit>`). The old branch does not have this evaluation screen: backport the debug runner and adapt its shared-decision call to the **old** detector policy. Do not copy the revised prompt, weights, or final-verdict logic into that branch, or the comparison will cease to represent the old detector. Confirm the old runner's verdicts against that branch's live SMS policy before collecting results. No before-and-after accuracy comparison has been run yet.

## Interpretation limits

The unit tests use simulated Gemma outputs. Actual inference, latency, model loading, and accuracy must be measured on the target phone with this debug APK. A dataset preselected by `CommunityPolicy` is biased toward high-confidence predictions and is unsuitable as the sole accuracy set. Include routine and promotional messages, short informal English/Filipino/Cebuano/Bisaya text, scams with unfamiliar wording, and ambiguous cases. Keep whole campaigns and same-sender conversations together when splitting data. A message-only evaluation omits real sender history and contact context. Device-specific inference failures and the first row's model-load time can affect latency results. Preserve the details CSV alongside the summary so false alerts and missed scams can be audited.
