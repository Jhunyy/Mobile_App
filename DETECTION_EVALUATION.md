# Detection evaluation plan

The app runs rule analysis for each non-duplicate received SMS and queues on-device Gemma analysis. The rule risk is preliminary; `FinalVerdictResolver` combines it with Gemma's result to produce the final SAFE, SUSPICIOUS, or SCAM verdict. In the UI, SAFE means the active policy did not flag the message, not that its sender was verified.

## Ground truth

Build a consented, redacted set of real SMS examples. Remove names, phone numbers, account numbers, OTPs, and other personal data before annotation or model training. Retain the wording, language, link structure, and scam mechanism where possible. Synthetic examples can supplement the set but must be reported separately from real-message performance.

Record for each message: a stable ID, redacted text, language (English, Filipino, or Taglish), scam type, label, evidence span, campaign/group ID, and source/provenance. Two reviewers should label ambiguous messages independently and resolve disagreements before the test set is frozen.

| Label | Annotation rule |
| --- | --- |
| SCAM | Concrete evidence of deceptive identity or a risky request, such as credentials, payment, or an unsafe destination. |
| SUSPICIOUS | Indicators or conflicting evidence, but the SMS alone does not support a firm scam verdict. |
| SAFE | No meaningful scam indicators in the available SMS text. This does not verify sender identity. |

Include hard legitimate examples: routine OTP delivery, bank notices, delivery updates, appointment reminders, and promotional messages. Include scam examples with unfamiliar wording and no seeded keyword. Do not label a message SCAM solely because it has an unfamiliar domain, poor grammar, urgency, or a brand name.

Evaluate the saved-contact verdict override separately. The raw rule score is unchanged. A rule-SUSPICIOUS message can become SAFE only when the sender was a saved contact at intake, Gemma returns LEGITIMATE with HIGH confidence, the rule pass found no strong signal, and the combined score does not require a SUSPICIOUS warning. Strong signals include a credential or payment request, or a URL indicator weighted at least 30. Test routine messages, spoofed numbers, risky requests, and suspicious links from saved contacts. Contact membership does not establish sender authenticity.

Evaluate the 0–100 combined point policy separately from the original keyword score and final verdict. Its 25-point SUSPICIOUS boundary and the 24-hour sender warning are initial policy settings, not calibrated probabilities. Include simple greetings with and without a prior confirmed scam, short requests and links that must remain eligible for scam detection, benign follow-ups to a scam, and conversations that cross the 24-hour and 48-hour boundaries. Split whole sender conversations across training and test sets to avoid leaking earlier texts into both. Check whether the bounded prompt fits the model's token limit and whether history improves recall without raising false alerts excessively.

## Evaluation method

1. Freeze a held-out test set before changing prompts, keyword weights, thresholds, or model weights. Keep messages from the same campaign or near-duplicate template in the same split.
2. Run keyword-only, Gemma-only, and combined decisions on the same messages. Record inference failures and malformed outputs as separate outcomes.
3. Report a three-class confusion matrix, SCAM recall, SCAM precision, legitimate-message false-alert rate, and the count of SUSPICIOUS outcomes. Break results down by language and scam type.
4. Record median and 95th-percentile time from SMS receipt to final verdict on the target phone, plus model load failures and peak memory where measurable.
5. Review missed scams and false alerts. Make one documented change at a time and compare it against the frozen test set. Set release targets only after measuring the baseline and the acceptable alert burden with users.

Gemma's HIGH/MEDIUM/LOW field is self-reported and must not be interpreted as a measured probability. If fine-tuning is attempted, use only the training split; compare the tuned model with the original on the untouched test set and on the actual device before adopting it.

Regression example: `Hi! Just reminding you that our meeting is tomorrow at 10 AM. See you then.` has a rule-SAFE result when its score is zero. The final result is SAFE if Gemma returns LEGITIMATE with HIGH or MEDIUM confidence, but SUSPICIOUS if Gemma returns SCAM with LOW confidence or is unavailable. That SUSPICIOUS result is an alert-policy choice, not evidence that the reminder is a scam. Keep this example in the fixed regression set and measure its false-alert cost alongside varied real reminders.

## Known implementation limit

`SmsReceiver` uses `goAsync()` for rule analysis and queues Gemma through `SmsProcessingWorker`. Verify that rule intake and worker enqueueing finish within the broadcast's allowed processing window on the target phone, including when the app is closed, before claiming reliable detection of every delivered SMS.
