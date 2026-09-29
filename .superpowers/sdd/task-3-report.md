# Task 3 Report: Encrypted Paddle OCR Credentials

## Status

Complete. Authenticated users can create, inspect masked status, retest, update,
and idempotently delete their own Paddle credentials. API Key and Secret Key
are independently encrypted at rest with AES-256-GCM.

## Files

- Added `backend/src/main/resources/db/migration/V2__ocr_credentials.sql`.
- Added credential entity, owner-scoped repository, AES-GCM cipher, service,
  controller, request/response records, and exception mapping under
  `backend/src/main/java/com/acme/invoice/credential/`.
- Added Paddle token client, timeout configuration, and unavailable exception
  under `backend/src/main/java/com/acme/invoice/ocr/`.
- Updated `backend/src/main/resources/application.yml`.
- Added focused cipher, client, service, and controller tests.
- Updated Task 2 integration-test configuration with a test-only 32-byte
  Base64 credential master key.

## Semantic RED evidence

No prior verifiable RED record was present. The inherited current state was
therefore tested before further production changes:

`.\gradlew.bat test --tests "*AesGcmSecretCipherTest" --tests "*OcrCredential*" --tests "*BaiduPaddleTokenClientTest"`

Result: `FAILED`, 15 tests executed, 2 failed.

- `OcrCredentialControllerTest.lifecycleNeverReturnsSecretOrCipherFields`
  failed with a `ClassCastException` from the derived `deleteByUserId` method.
- `BaiduPaddleTokenClientTest.acceptsNonblankAccessTokenAndSendsRequiredParameters`
  failed because the success response body was released before parsing and was
  mapped to `PaddleServiceUnavailableException`.

The minimal fixes changed the delete method to return `void`, mapped the
`ClientResponse` without prematurely releasing a successful body, and removed
an unrealistically small cold-start timeout from non-timeout client tests.

## Commands and results

- Focused tests after fixes and added boundary assertions: `BUILD SUCCESSFUL`
  (15 tests, 0 failures).
- Fresh full suite: `.\gradlew.bat test --rerun-tasks` -> `BUILD SUCCESSFUL`
  (30 tests, 0 failures, 0 errors).
- `git diff --check` -> clean.
- IDE diagnostics for changed credential/OCR source and tests -> no errors.
- Test-result canary scan for representative API keys, secrets, OAuth token,
  and raw error body -> no matches.
- Production-source logging scan -> no credential, token, ciphertext, IV, or
  raw-response logging.

## Self-review

- Repository business lookup and delete methods are scoped by `userId`.
- Invalid remote validation occurs before encryption or persistence, preserving
  an existing row.
- Both fields use independent fresh 12-byte IVs; GCM tags remain in ciphertext.
- Responses use a dedicated masked DTO and expose no plaintext, ciphertext, IV,
  OAuth token, or raw upstream response.
- Missing, malformed, and wrong-length master keys fail bean construction
  outside the `smoke` profile.
- Authentication and CSRF behavior, user isolation, missing saved credentials,
  input bounds, storage encryption, and idempotent delete are covered.
- No frontend, invoice recognition, upload, or retry behavior was added.

## Commit

This report is included in commit `feat: store encrypted Paddle credentials`.
The exact commit hash is reported in the final handoff because a commit cannot
contain its own hash without changing that hash.

## Concerns

- Two foreground full-suite command submissions were rejected by the IDE tool
  transport with `Failed to find tool call context`; the same command was then
  started in the background and completed successfully. No Gradle, Docker,
  network, authentication, or dependency-resolution failure remained.
