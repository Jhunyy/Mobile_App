# Community contribution API contract

The Express/PostgreSQL server is not present in this repository. The Android client has a separate Retrofit interface for this feature and does not use the scam report endpoint. Configure an HTTPS `APCIDA_REPORT_BASE_URL` (or `apcida.reportBaseUrl` in `local.properties`) ending in `/api/`, and a separate `APCIDA_COMMUNITY_API_KEY` (or `apcida.communityApiKey`). Without the community key, local scanning/review works, but sharing is disabled.

The packaged API key is only a prototype gate: it can be extracted from an APK. A production service needs real client authentication, rate limits, and abuse controls. Never use a participant UUID alone as proof of identity.

## Endpoints

`GET /api/community/contributions/progress?participantId=<uuid>` with `X-API-Key` returns:

```json
{"scamAccepted": 12, "safeAccepted": 15}
```

`POST /api/community/contributions` with `X-API-Key` receives a separately approved batch:

```json
{
  "batchId": "uuid",
  "participantId": "uuid",
  "submittedAt": 1792790000000,
  "items": [
    {
      "clientId": "sha256-of-original-body",
      "text": "Participant-reviewed and redacted SMS text",
      "predictedCategory": "SCAM",
      "reviewedCategory": "SAFE",
      "reviewedAt": 1792790000000
    }
  ]
}
```

Return HTTP 2xx only after committing the decision for every item. The response is:

```json
{
  "acceptedClientIds": ["sha256-of-original-body"],
  "rejectedClientIds": [],
  "progress": {"scamAccepted": 12, "safeAccepted": 16}
}
```

The client caches only the server's accepted counts. An approved batch is stored in Android's no-backup private directory until acknowledged and can be retried with the same `batchId` and `clientId` values. The server must make repeated submissions idempotent and return the same accepted/rejected decisions. A missing ID remains pending. If the server rejects an item, include it in `rejectedClientIds` so the client stops retrying it. The client never sends sender addresses, contact names, or unrelated inbox messages.

## Server rules

- Authenticate the client and bind the pseudonymous participant ID to an authenticated installation or research enrollment. Apply rate limits.
- Validate type, encoding, and length of every field. Accept only `SCAM`/`SAFE` reviewed and predicted categories. Reject personal identifiers, authentication codes, and empty text. Recheck redaction server side.
- Enforce at most 20 accepted `SCAM`, 20 accepted `SAFE`, and 40 total per participant, using transactions and unique constraints. Count the **reviewed** category for participant limits.
- Deduplicate by participant/client ID and normalized message content, and retain the batch idempotency key. Never count a retry twice.
- Store predicted and reviewed labels separately. Initialize `verificationStatus` to `PENDING`; add a separate researcher verified label and verifier record only after independent review. Do not calculate accuracy from predicted labels.
- Keep research-wide counts for accepted and independently verified Scam/Safe records, duplicate and excluded decisions, participant IDs, and submission dates. The study target is 100 verified Scam and 100 verified Safe messages; these totals are not returned to the participant UI.
- Use HTTPS, restricted database access, retention/deletion rules, and appropriate study consent records. Never log raw SMS request bodies.

The Android client cannot verify historical SMS behavior or successful uploads until the backend implements this contract and the app is tested on a device with an installed Gemma model.

## Android distribution note

`READ_SMS` and `RECEIVE_SMS` are restricted for Google Play distribution. Google Play's [SMS and Call Log policy](https://support.google.com/googleplay/android-developer/answer/10208820) lists an anti-smishing exception, but eligibility requires a documented track record of significant protection and a Play Console declaration/review. This prototype does not claim that approval. The community screen requests `READ_SMS` through Android only after explicit scan consent and handles denial; it cannot bypass platform or store restrictions.
