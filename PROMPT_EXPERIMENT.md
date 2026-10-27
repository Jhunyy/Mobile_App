# Gemma prompt experiment: 26 October 2026

This experiment changed only the classification text in `PromptBuilder.kt` and its prompt tests. The model, MediaPipe inference settings, keyword weights, final verdict resolver, and community policies were unchanged. The debug evaluator ran the same ordered 49-row labeled CSV on the same Android emulator with the same `gemma3-1b-it-int4.task` file. All 49 rows completed with Gemma available in each run. The rows had 40 verified `SAFE` and 9 verified `SCAM` labels; message IDs, order, and labels matched between the two detail exports.

| Measure | Previous prompt | Shorter evidence-focused prompt |
| --- | ---: | ---: |
| Raw Gemma SCAM predictions | 42/49 | 31/49 |
| Raw Gemma false positives on SAFE | 33/40 (82.5%) | 23/40 (57.5%) |
| Raw Gemma detections on SCAM | 9/9 (100%) | 8/9 (88.9%) |
| HIGH confidence among Gemma SCAM predictions | 42/42 | 31/31 |
| Final exact SAFE/SUSPICIOUS/SCAM accuracy | 20/49 (40.8%) | 26/49 (53.1%) |
| Final SAFE / SUSPICIOUS / SCAM | 20 / 28 / 1 | 27 / 21 / 1 |
| Final strict SCAM recall | 1/9 (11.1%) | 1/9 (11.1%) |
| Final warning recall, SCAM + SUSPICIOUS | 8/9 (88.9%) | 7/9 (77.8%) |

The new run ID is `19ae51e1-1c29-4b97-a1c0-7f1d8a7acd01`, with `detector_version` `1.0-eval-revised-policy-v1--prompt-v2`. Its exports are `/sdcard/Download/evaluation_details_prompt_v2.csv` and `/sdcard/Download/evaluation_summary_prompt_v2.csv` on the emulator. The prior run ID is `a3be6396-feb0-4cce-9ee4-155ecf0399e2`.

The revised prompt reduced false positives but did not solve the raw model problem. Some ordinary names and personal questions were still classified SCAM with HIGH confidence, sometimes alongside a Reason that explicitly described no fraud indicator. The missed labeled SCAM was a short raffle-win notice with no request, payment instruction, or link in its text; absent external context, that SMS does not reveal a concrete fraud mechanism. This is a real miss against the verified label and also an example of a limit of message-only evidence.

This 49-message community-derived set is small and selected, so its rates are not population accuracy estimates. The same set was inspected to design the new prompt, so this is a development-set comparison; evaluate a fresh, independently labeled holdout set before making a general accuracy claim. `Confidence` is model-generated text, not a calibrated probability. Repeated runs can vary because MediaPipe sampling was unchanged.

To repeat on the target Android phone, install `app/build/outputs/apk/debug/app-debug.apk` over the debug package, keep the same readable `gemma3-1b-it-int4.task` file, select the exact same ordered CSV in **KA-SDS Evaluation (debug)**, enter a distinct `detector_version`, run, and export both CSVs. Compare rows by `message_id` and `verified_label`, then calculate raw Gemma and final-verdict metrics separately. The debug app is offline and does not insert the evaluation SMS into the real inbox or community database.
