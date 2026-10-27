# Debug-only evidence experiment

## Scope

The production `PromptBuilder`, Gemma model, CPU backend, keyword rules, `FinalVerdictResolver`, and community policy are unchanged. The debug batch evaluator records the original raw Gemma decision and production final verdict, then computes a separate evidence tier, experimental model decision, and experimental final verdict from the same inference output. No experimental verdict is stored in the SMS database or used by live SMS processing.

## Findings from code inspection

- `GemmaValidator` creates and closes a fresh `LlmInferenceSession` for every generation, including its format retry. A mutex serializes calls to the shared model. App-level conversation history is not retained in a session between CSV rows.
- `EvaluationRunner` supplies history only when a CSV row has both `sender_address` and `received_at`. The 49-row evaluation export recorded `MESSAGE_ONLY` on every row, so sender history was absent.
- The parser requires the three-line contract and returns an unavailable fallback on malformed output. It does not judge whether a reason supports a classification; a contradictory but correctly formatted `SCAM / HIGH` response passes. The prior 49-row export had 49 available outputs, so format failure did not cause those false positives.
- The configured model uses CPU inference with a combined prompt and response budget of 768 tokens. MediaPipe session defaults are topK 40, topP 1.0, temperature 0.8, and seed 0 in the installed 0.10.35 library. Sampling can vary. The app sends a plain prompt through `addQueryChunk`; the `.task` metadata contains Gemma turn markers. We did not change generation settings or insert another manual chat template.
- The prompt asks for concrete evidence, but the model can still output unsupported claims or a reason that contradicts its own `SCAM` label. Whether this reflects model capacity, language coverage, or sampling needs controlled experiments. Native cross-session contamination and token truncation remain unconfirmed; long SMS may require a separate token-budget study.

## Experimental decision

The evidence check uses general fraud mechanisms rather than examples from the 49-message set. An explicit request for a secret, a prize tied to a required payment, or an account threat tied to required payment is strong evidence. A URL, payment mention, promotion, prize mention, or account reference alone is ambiguous. A `SCAM` claim with none of these cues is unsupported. The check also records reason claims about credentials, links, payment, prizes, or account threats when that cue is absent from the SMS. These lexical checks cannot establish truth, verify sender identity, or prove a URL is malicious.

For `AMBIGUOUS` and `UNSUPPORTED` raw SCAM outputs, the debug evaluator recomputes a final decision with the Gemma signal neutralized and the unchanged production rules. Ambiguous outputs have a SUSPICIOUS floor. Strong outputs retain the original decision. Production final verdicts are always exported separately. A model failure is `NOT_APPLICABLE`, not fraud evidence.

## Evaluation protocol

In the debug evaluator, select the same independently labeled CSV and run it once. Export `evaluation_details.csv` and `evaluation_summary.csv`. The paired summary reports raw Gemma SCAM metrics, evidence-model strict and warning metrics, production final three-way/strict/warning metrics, and experimental final three-way/strict/warning metrics. Check both strict scam recall and warning recall alongside false-positive rate and SUSPICIOUS count. A fresh labeled dataset and label review are required before any production adoption. Debug Logcat tag `GemmaRawDebug` contains raw model text that may quote private SMS; keep logs local and clear them after inspection. Release builds use a no-op logger.

## Paired 49-row run, 2026-10-26

The 49 rows included 40 labeled SAFE and 9 labeled SCAM. All 49 inferences succeeded and all had `MESSAGE_ONLY` context. The raw Gemma class, confidence, and production final verdict matched the previous revised-prompt export on every row.

| Output | Accuracy | Scam precision | Scam recall | SAFE false-positive rate | SUSPICIOUS |
|---|---:|---:|---:|---:|---:|
| Raw Gemma, SCAM positive | 51.0% | 25.8% | 88.9% | 57.5% | 0 |
| Evidence model, strict SCAM | 81.6% | N/A | 0.0% | 0.0% | 14 |
| Evidence model, warnings positive | 81.6% | 50.0% | 77.8% | 17.5% | 14 |
| Production final, strict SCAM | 83.7% | 100.0% | 11.1% | 0.0% | 21 |
| Production final, warnings positive | 65.3% | 31.8% | 77.8% | 37.5% | 21 |
| Experimental final, strict SCAM | 83.7% | 100.0% | 11.1% | 0.0% | 13 |
| Experimental final, warnings positive | 81.6% | 50.0% | 77.8% | 17.5% | 13 |

Three-way exact accuracy was 26/49 (53.1%) for production final and 34/49 (69.4%) for experimental final. The evidence model found no strong fraud tier in these nine labeled scams, so its strict SCAM recall was zero. This is a serious limitation: it should not replace raw Gemma or production policy. Production and experimental warnings each caught seven labeled scams, but not the same seven. The experimental path newly warned on a bare Cebuano money request and stopped warning on a bonus-cash message with a redacted `[link]` token. Its URL extractor recognizes real URLs but not that dataset placeholder. Some labels also lack an explicit deceptive request in the SMS text; they need review against external context. These results are exploratory and are not evidence of generalization.
