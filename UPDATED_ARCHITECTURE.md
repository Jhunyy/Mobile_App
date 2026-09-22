# Updated Always-On SMS Detection Architecture

## Previous flow and limitations

The original receiver launched an unmanaged coroutine and ran keyword scoring before conditionally invoking Gemma. Android could finish the broadcast and kill the process before that coroutine completed. The risk threshold therefore acted as an AI gate: a scam that avoided seeded phrases could also avoid contextual analysis. Gemma output overwrote the rule result, content-only hashes suppressed legitimate repeated messages indefinitely, and model lifetime was tied to `MainActivity`.

## New always-on flow

Every non-duplicate incoming SMS follows two independent detection paths whose evidence is fused conservatively:

1. `SmsReceiver` calls `goAsync()`, groups multipart SMS segments, and asks `SmsController` to persist each complete message immediately.
2. The controller hashes the sender and content, checks only a bounded duplicate window, stores `PENDING`, and runs deterministic analysis.
3. Keyword, URL, suspicious-domain, urgency, and impersonation indicators are scored and stored independently as the rule result. Word-boundary matching reduces accidental substring matches, while URL extraction identifies links absent from the seed list.
4. A deterministic `SCAM` result triggers an immediate warning. It does not wait for Gemma.
5. Every new message transitions to `AI_QUEUED` and is appended to one unique WorkManager chain by database ID. Raw SMS content is not copied into WorkManager input.
6. The worker loads the application-scoped Gemma instance, performs one inference at a time, validates strict output, and stores the AI result separately.
7. `FinalVerdictResolver` combines the rule and AI results. The list, detail screen, reporting snapshot, and notification use the combined classification.

WorkManager persists queued requests across normal process death. Gemma is application-scoped and is no longer released when an activity is destroyed during configuration change.

## SMS lifecycle and processing states

| State | Meaning | Next state |
|---|---|---|
| `PENDING` | SMS has been stored; rules have not completed | `RULE_ANALYZED` |
| `RULE_ANALYZED` | Deterministic score, level, and indicators are stored | `AI_QUEUED` |
| `AI_QUEUED` | Persistent AI work has been requested | `AI_ANALYZING` |
| `AI_ANALYZING` | The shared on-device model is evaluating the SMS | `COMPLETED` or `AI_FAILED` |
| `COMPLETED` | A valid AI result and combined result are stored | terminal |
| `AI_FAILED` | AI was unavailable, timed out, failed, or returned malformed output | retry to `AI_ANALYZING`, or remain failed after the retry limit |

The UI shows `ANALYZING` during nonterminal AI states and explicitly shows `AI unavailable` after failure. A failed AI attempt never creates a safe result.

## Rule and AI fusion policy

| Deterministic result | AI result | Combined result |
|---|---|---|
| `SAFE` | `LEGITIMATE` | `SAFE` |
| `SAFE` | `SCAM` | `SCAM` |
| `SAFE` | unavailable/uncertain | `SUSPICIOUS` |
| `SUSPICIOUS` | `LEGITIMATE` | `SUSPICIOUS` |
| `SUSPICIOUS` | `SCAM` | `SCAM` |
| `SUSPICIOUS` | unavailable/uncertain | `SUSPICIOUS` |
| `SCAM` | `LEGITIMATE` | `SUSPICIOUS` |
| `SCAM` | `SCAM` | `SCAM` |
| `SCAM` | unavailable/uncertain | `SCAM` |

Only agreement between rule-safe and AI-legitimate produces `SAFE`. A strong deterministic warning can be reduced to review after contrary AI evidence, but never directly to safe.

## Gemma failure behavior

Inference has a bounded timeout and WorkManager retries transient failures up to three attempts. Model absence, load failure, timeout, inference exceptions, unsupported classification/confidence values, missing fields, extra output, and multiline output all produce an unavailable result. The database retains the deterministic score, deterministic classification, matched indicators, and conservative combined classification. High-risk messages remain visible and flagged.

## Duplicate handling

Duplicate suppression requires the same sender hash and content hash within a ten-minute window. The sender and content are normalized and SHA-256 hashed locally. The bounded window suppresses repeated multipart/broadcast delivery without preventing a sender from legitimately sending the same text later. Different senders with identical content remain distinct.

## Privacy and security

- SMS analysis remains on-device; the Gemma path has no network dependency.
- Raw senders are not stored, and raw SMS content is not emitted to Logcat or placed in WorkManager input.
- SMS text is treated as untrusted data. Control characters, quotes, and delimiter characters are escaped into one JSON-style data string so SMS text cannot manufacture prompt sections.
- The prompt states that SMS instructions have no authority and requests exactly three output fields.
- The parser accepts only `SCAM` or `LEGITIMATE`, only `HIGH`, `MEDIUM`, or `LOW`, a nonempty single-line rationale, and no additional text.
- Reporting behavior is unchanged. Reports are still user-initiated; always-on classification itself does not transmit SMS content.

## Performance and battery tradeoffs

Always-on inference costs more latency, memory, and battery than threshold-gated inference. A single unique WorkManager chain and an inference mutex prevent concurrent model sessions, reducing peak memory and device contention at the cost of queue latency during SMS bursts. The model is retained at application scope to avoid repeated loading; Android can still reclaim the whole process. A 90-second application timeout and bounded retries prevent endless logical processing, although cancellation behavior ultimately depends on the synchronous MediaPipe inference implementation.

## Recommended tests and evaluation metrics

Automated tests should cover eligibility for every rule level, all fusion combinations, malformed output, unavailable AI, prompt-injection-like content, duplicate window boundaries, state transitions, URL extraction, and word-boundary matching. Device/instrumentation tests should additionally cover process death after enqueue, reboot recovery, multipart SMS, database migration, notification permission states, missing/corrupt model files, and activity rotation during inference.

Track at least:

- deterministic, AI, and combined precision/recall/F1 on representative English, Filipino, and Taglish messages;
- false-negative rate for messages with no seeded keywords;
- false-positive rate for legitimate bank, delivery, OTP, and repeated notification messages;
- AI unavailable/malformed/timeout rates and retry success rate;
- intake-to-rule-warning and intake-to-final-verdict latency (p50/p95/p99);
- queue depth, model-load time, inference duration, memory peak, and battery use per message;
- duplicate suppression rate and incorrect-suppression reports.

## Reduction in keyword-bypass risk

Keywords are now evidence rather than a gate. A message with obfuscated wording, a previously unseen domain, or no matching keyword still enters the persistent Gemma queue. URL heuristics add evidence for unseen links, while the conservative fusion policy prevents AI failure or adversarial output from converting the message to safe. This closes the prior path where avoiding the deterministic threshold also avoided contextual inspection.
