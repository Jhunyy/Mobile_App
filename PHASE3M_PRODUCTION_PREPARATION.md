# Phase 3M: Production integration preparation

Status: **preparation only** (2026-10-28). No automatic real SMS upload, new listener, production enrollment, Cloudflare deployment, or public endpoint was activated. Android instrumentation tests are **deferred**, not passed: the Windows teammate's Java environment is incompatible at present and no device run was performed in this phase. The earlier physical Secure Pilot Test verified manually entered synthetic messages only.

## Implemented

| Component | Change |
| --- | --- |
| Android `ProductionSmsTransport` | Reserved `/api/v1/sms/analyze` protocol, separate from synthetic `/api/sms/analyze`; exact approved HTTPS origin, ordinary platform TLS verification, no redirects, separate bearer credential, stable opaque request ID, bounded timeouts, strict reply validation, generic failure states. It is internal, unwired, and uses the closed production gate by default. |
| Android `ProductionCredentialStore` | Separate preference file and Android Keystore alias from pilot credentials. No production token is provisioned or embedded. No production provisioning UI exists. |
| Backend `src/production/smsContract.js` | Pure strict request/response validation and matching `Idempotency-Key`; not imported by a route or launcher. The reserved route currently returns 404. |
| Tests | Synthetic JVM protocol tests for closed gate, host/HTTP/Quick Tunnel rejection, bearer request, absence of pilot marker, 401 revocation, timeout, offline, malformed reply; backend tests for strict contract and rejected pilot IDs. |

No production API server, database migration, or production credential issuer was created. The existing synthetic pilot enrollment table and its tokens cannot authorize the future production API. Gemma 3 12B, prompts, RAG, rules, verdict policy, and evaluations were not changed.

## Current protected path

The live `SmsReceiver` still calls `SmsController.receiveAndAnalyzeRules`: it deduplicates, stores the message, runs keyword rules immediately, and can post an immediate local SCAM warning. Inbox sync calls that controller with `historicalImport=true` and `alertOnDetection=false`. AI Protection consent is off by default, persists across restarts, and is distinct from research contribution. Revocation increments the consent generation and cancels tagged work. Zero-rule local results are `UNVERIFIED` without completed remote analysis. `RemoteTransportGate.PRODUCTION_READY=false`; the receiver/worker has no reference to `AutomaticRemoteCoordinator` or `ProductionSmsTransport`.

The prepared coordinator checks consent and generation before claim/send and after reply. Room claim/completion are conditional, so duplicate workers cannot both claim or commit. Local and remote warnings use the same message ID as notification ID. Timeout, network error, invalid result, or revoked token preserves the local warning or `UNVERIFIED`, never creates SAFE. A request already accepted by a remote server cannot be retracted on consent revocation; no automatic requests are currently possible.

## Future production API contract (not deployed)

`POST /api/v1/sms/analyze` would accept a server-issued per-install bearer token, an opaque UUID `Idempotency-Key`, and a strict JSON body with the same `messageId`, `sender`, `text`, and ISO `receivedAt`. It would return the same `messageId`, one of SCAM/SAFE/SUSPICIOUS, confidence HIGH/MEDIUM/LOW, and a short rationale. Pilot IDs and `x-apcida-pilot-synthetic` are not part of this contract. Implement a separate production installation table, administrator-approved enrollment, credential rotation/revocation, durable per-install rate limits, an 8 KiB body cap, and a bounded one-inference-per-install/global queue before mounting it. Keep an internal API key private to the server; never ship it in Android.

A production route must fail with HTTP errors on Ollama, embedding, RAG, authentication-store, or database failure. It must never synthesize SAFE. Before retries, design durable `(installation_id, message_id)` idempotency across process restarts. Decide whether a short-lived encrypted response cache is acceptable under the retention policy; duplicate requests must not silently launch another inference. The current pilot rate limiter is process-local and the pilot identity table is not suitable for production.

## Hosting recommendation

The current `*.trycloudflare.com` Quick Tunnel is temporary and has no uptime guarantee. For an approved limited pilot, use a **named Cloudflare Tunnel** with a stable custom hostname under an owned domain, routing only to a localhost-bound production listener. Create and verify a Cloudflare Access policy **before** adding a public hostname; Cloudflare states a tunnel route can otherwise be publicly reachable. Protect the origin with Access token validation or an equivalently reviewed private access design, in addition to ApCiDa per-install authentication. A Cloudflare service token shared in an APK would defeat the per-install goal; choose per-device identity/provisioning or a compatible device-authenticated access method before rollout. Keep Ollama and PostgreSQL on loopback. Do not expose the existing synthetic pilot listener as production.

Cloudflare documentation: [Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/), [Tunnel fundamentals](https://developers.cloudflare.com/tunnel/concepts/), [self-hosted Access applications](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/). No Cloudflare configuration was changed in this phase.

## Privacy and retention review

The prepared request includes raw SMS text, sender, and exact received time because the current classifier accepts these fields. This is **not yet approved for real SMS**. Before activation, decide whether sender can be omitted, pseudonymized, or reduced without harming detection; minimize timestamps where possible. Do not use SMS body, sender, auth token, or model rationale in request/auth logs, URLs, analytics, crash reports, or Cloudflare log fields. Audit only event code, opaque installation ID, and coarse timestamp. Do not persist routine inference payloads or responses by default. Define a short retention period for operational metadata and deletion on revocation/account removal; document Cloudflare/OS backups and access. Keep research dataset contribution under separate consent and storage. Encryption at rest for any durable idempotency record remains a design requirement.

## Reliability and notification gaps before activation

- A MacBook-hosted service can sleep or go offline. A named tunnel does not fix origin availability. Test sleep prevention, model warm-up, timeouts, and a monitored restart procedure; retain keyword-only fallback.
- Current orphan recovery marks AI work older than 120 seconds unavailable, while the prepared HTTP call timeout is 180 seconds. Resolve this timing conflict and crash recovery rules before wiring a worker.
- A crash after a Room verdict commit but before notification posting can omit a remote warning. Current same-ID notification updates avoid duplicates but do not guarantee delivery. A durable notification outbox or reconciler needs design and device testing.
- Consent revocation across SharedPreferences, Room, and an in-flight HTTP call is not a single transaction. Recheck immediately before send and after reply, cancel queued work, and document that already-sent SMS cannot be recalled.
- Review genuine process-death, duplicate broadcast, no-history-upload, notification sound/update, token loss/rotation, HTTP 401/403, 429, timeout, and offline behavior on a physical phone with synthetic fixtures before any real SMS trial.

## Verification performed

- Android: `./gradlew testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest --console=plain` passed; **108 JVM tests, 0 failures**. This compiled the instrumentation APK; it did not run instrumentation on a device.
- Backend: `npm test` passed; **88 tests, 0 failures**. The sandbox-only initial run could not bind loopback test sockets and was rerun with permission; no live service was started.
- Static checks: `PRODUCTION_READY` remains false; no live receiver/worker reference to the production transport; backend has no production route or launcher.

## Controlled real-SMS test prerequisites

1. Resolve and execute Android instrumentation on a physical phone; the Windows Java issue does not block preparation, but its tests remain deferred. Confirm the Phase 3L migration, Room, consent, and notification cases rather than inferring a device pass from compilation.
2. Review and approve the production API, identity store, per-install enrollment and revocation, stable TLS hostname, Cloudflare Access/device authentication, and origin isolation. Deploy only after explicit approval.
3. Approve data minimization, deletion and retention, incident response, consent wording, authentication logging, and availability/fallback behavior.
4. Implement server-side durable idempotency and notification/process-death recovery; add end-to-end synthetic device tests against the separately deployed production contract.
5. Perform a separate security/product decision before connecting the coordinator to a live worker or changing `PRODUCTION_READY` from false.
