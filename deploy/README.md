# NeuroX production deployment

This directory is a deployable reference topology, not evidence that a production
environment has been accepted. It provides HTTPS termination, a separately built
API and dashboard, controlled migrations, PostgreSQL, Redis-backed rate limits,
a maintenance worker, object storage, OTLP trace collection, Prometheus scraping,
and initial availability/error-rate alerts.

## Required setup

1. Create an operator-owned directory outside the repository, set
   `NEUROX_SECRETS_DIR` to its absolute path, and add mode-`0600` files named
   `database_url`, `redis_url`, `jwt_secret`, `smtp_password`,
   `sms_gateway_token`, `bhashini_api_key`, `postgres_password`,
   `redis_password`, `minio_access_key`, and `minio_secret_key`. Never commit
   this directory. On Kubernetes or another orchestrator, map the same names
   from its managed secret store instead.
2. Set `NEUROX_DOMAIN`, SMTP sender/host, SMS gateway URL, BHASHINI user ID and
   pipeline ID in
   the deployment environment. The URLs stored in the database/Redis secrets must
   match those credentials.
   The Android app sends short authenticated WAV clips to NeuroX; BHASHINI
   credentials remain server-side. Transcription requires the patient's
   `voice_recording` consent, and the API does not retain audio or transcripts.
   Set `COGNITIVE_MODEL_HOST_PATH` to the absolute path of the calibrated model
   artifact and `COGNITIVE_MODEL_SHA256` to its SHA-256 digest. The API refuses
   readiness if the mounted artifact is absent or has a different digest.
3. Validate the compose model, build immutable images, run the one-shot `migrate`
   job, then start the remaining services. Confirm `/health`, `/ready`, and the
   dashboard through the public HTTPS hostname.
4. Connect the OpenTelemetry collector and Prometheus alerts to retained,
   access-controlled observability backends and an owned on-call notification
   route. The bundled collector's debug exporter is for topology verification,
   not production trace retention.

## Backups and recovery

Run `backup.sh` from an external scheduler with a mounted database URL secret, an
`age` recipient, and a dedicated backup directory backed by encrypted object
storage. Monitor both the dump and checksum upload. A successful command alone
does not prove recoverability.

At least monthly, provision a disposable isolated database and run
`restore-drill.sh` with `ALLOW_DESTRUCTIVE_RESTORE_DRILL=yes`. Record the backup
identifier, checksum, start/end time, row-count checks, application smoke result,
and reviewer. Destroy the drill database afterward.

Point-in-time recovery must be enabled and tested on the managed PostgreSQL
service. The local compose database deliberately does not pretend that discarded
WAL files provide PITR. Record the provider retention window and a timestamped
restore exercise before accepting Phase 2.

## Release acceptance

Before pilot traffic, record evidence for HTTPS/TLS, secret isolation, migration
rollback, Redis failover behavior, email and SMS delivery, encrypted backup
restore, PITR, telemetry export, alert delivery, expected-load latency, and Android
device tests for process death/reboot/database migration. The targets in
`PLAN.md` (99.5% availability, read P95 under 500 ms, write P95 under 800 ms, and
Android crash-free sessions above 99.5%) require measured staging/pilot data and
cannot be established by source code or unit tests alone.
