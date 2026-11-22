# Consented thesis contribution API

The Android contribution screen uses the enrolled phone's Bearer credential and Cloudflare Access service token at `https://sms.apcida.stream`. There is no shared community API key in the APK. The controlled server's study feature is disabled until its separate PostgreSQL schema, retention period, and expert access have been reviewed and enabled. The normal detection report endpoint is independent.

The phone reads recent inbox SMS locally only after the participant starts a scan and grants SMS permission. It offers up to 20 eligible, deduplicated messages per scan without filtering by the rule suggestion. The participant selects each message to include, edits or redacts its text, and confirms upload. Participants do not label messages; the network expert labels them independently. The cumulative cap is 40 messages per participant. The selected batch is kept in app-private no-backup storage until the server acknowledges every item. A private keyed fingerprint, rather than a plain hash of the original SMS, identifies retries and accepted items.

`GET /api/v1/community/contributions/progress?participantId=<uuid>` returns `totalAccepted` for the authenticated installation. `POST /api/v1/community/contributions` accepts 1–20 items:

```json
{
  "batchId": "uuid",
  "participantId": "uuid",
  "submittedAt": 1792790000000,
  "consentScope": "STUDY_EVALUATION_ONLY",
  "examinedCount": 18,
  "offeredCount": 12,
  "selectionMethod": "RECENT_INBOX_PARTICIPANT_REVIEW",
  "items": [{
    "clientId": "64-hex-character-keyed-fingerprint",
    "text": "Participant-reviewed and redacted SMS text",
    "localSuggestion": "UNVERIFIED",
    "reviewedAt": 1792790000000
  }]
}
```

The response lists `acceptedClientIds`, `rejectedClientIds`, and `progress`. The server's database transaction enforces the 40-message cap, participant binding, duplicate checks, and idempotent batch replay. A partial response is treated as an error so the phone retries the original batch. `DELETE /api/v1/community/contributions` deletes the installation's accepted research records and records the withdrawal time; the phone then clears local pending data and cached counts.

The server stores edited SMS separately from reports and RAG. Research analysis later processes up to ten queued contributions sequentially on the private admin website. A network expert labels the same edited text without seeing the system prediction. The local rule suggestion is not expert ground truth. A result on this participant-selected, redacted set must be described as such in the thesis.

`READ_SMS` and `RECEIVE_SMS` are restricted for Google Play distribution. The community screen requests SMS access only after explicit scan consent and handles denial; it cannot bypass platform or store restrictions.
