# Detection evaluation plan

The app runs keyword analysis and on-device Gemma on **every received SMS**. Keyword risk is preliminary; `DecisionEngine` produces the final SAFE, SUSPICIOUS, or SCAM result. In the UI, SAFE means **no scam indicators found**, not verified genuine.

## Ground truth

Build a consented, redacted set of real SMS examples. Remove names, phone numbers, account numbers, OTPs, and other personal data before annotation or model training. Retain the wording, language, link structure, and scam mechanism where possible. Synthetic examples can supplement the set but must be reported separately from real-message performance.

Record for each message: a stable ID, redacted text, language (English, Filipino, or Taglish), scam type, label, evidence span, campaign/group ID, and source/provenance. Two reviewers should label ambiguous messages independently and resolve disagreements before the test set is frozen.

| Label | Annotation rule |
| --- | --- |
| SCAM | Concrete evidence of deceptive identity or a risky request, such as credentials, payment, or an unsafe destination. |
| SUSPICIOUS | Indicators or conflicting evidence, but the SMS alone does not support a firm scam verdict. |
| SAFE | No meaningful scam indicators in the available SMS text. This does not verify sender identity. |

Include hard legitimate examples: routine OTP delivery, bank notices, delivery updates, appointment reminders, and promotional messages. Include scam examples with unfamiliar wording and no seeded keyword. Do not label a message SCAM solely because it has an unfamiliar domain, poor grammar, urgency, or a brand name.

## Evaluation method

1. Freeze a held-out test set before changing prompts, keyword weights, thresholds, or model weights. Keep messages from the same campaign or near-duplicate template in the same split.
2. Run keyword-only, Gemma-only, and combined decisions on the same messages. Record inference failures and malformed outputs as separate outcomes.
3. Report a three-class confusion matrix, SCAM recall, SCAM precision, legitimate-message false-alert rate, and the count of SUSPICIOUS outcomes. Break results down by language and scam type.
4. Record median and 95th-percentile time from SMS receipt to final verdict on the target phone, plus model load failures and peak memory where measurable.
5. Review missed scams and false alerts. Make one documented change at a time and compare it against the frozen test set. Set release targets only after measuring the baseline and the acceptable alert burden with users.

Gemma's HIGH/MEDIUM/LOW field is self-reported and must not be interpreted as a measured probability. If fine-tuning is attempted, use only the training split; compare the tuned model with the original on the untouched test set and on the actual device before adopting it.

## Known implementation limit

`SmsReceiver` currently launches a coroutine after receiving a broadcast. A long model run may outlive the receiver's allowed processing window. Move long analysis into durable background work and verify receipt-to-result behavior with the app closed before claiming reliable detection of every delivered SMS.
