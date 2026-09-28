# Invoice Verification System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a multi-user web application that recognizes scanned invoices through Baidu Paddle OCR, verifies normalized fields, blocks definite duplicates, supports human review, and exports accepted invoices.

**Architecture:** Use a Vue 3 SPA and a Spring Boot modular monolith backed by PostgreSQL. Keep provider, verification, deduplication, storage, and ERP concerns behind narrow interfaces so each can be tested independently. Persist task state in PostgreSQL and use Spring application events plus SSE for progress; do not add a queue or Redis in the first release.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Gradle 8.x, PostgreSQL 17, Flyway, Spring Security, WebClient, Apache Tika, Apache POI, Vue 3.5, TypeScript 5.9, Vite 7, Pinia, Vue Router, Vitest, Playwright, Docker Compose.

## Global Constraints

- Accept JPG, JPEG, PNG, and PDF; at most 100 files per batch, 20 MiB per file, and 20 pages per PDF.
- Cover VAT special, VAT ordinary, electronic, railway, flight itinerary, taxi, fixed-amount, and customs-payment invoices.
- Keep every user's credentials, files, invoices, searches, downloads, SSE streams, exports, and ERP operations isolated by `user_id`.
- Encrypt OCR credentials with AES-GCM using `APP_CREDENTIAL_MASTER_KEY`; never return or log a Secret Key.
- Only `PASSED` invoices may enter the ledger, export, or ERP push.
- Retry Paddle timeouts, HTTP 429, and HTTP 5xx three times with exponential backoff; never retry invalid credentials.
- Use the confirmed graphite monochrome UI: neutral canvas, white elevated surfaces, near-black primary actions, blue only for links and focus, status colors only as small indicators.
- Meet WCAG AA contrast and keyboard-focus requirements.
- Keep `.superpowers/`, real credentials, real invoice files, and production keys out of Git.
- The current machine has Java 21 and Node.js 24 but no global Gradle or Docker. Bootstrap the checked-in Gradle Wrapper through Spring Initializr; install and start Docker Desktop before PostgreSQL and Testcontainers verification.

---

## File Structure

- `compose.yaml`: local PostgreSQL service.
- `.env.example`: documented non-secret configuration names.
- `backend/`: Spring Boot application.
- `backend/src/main/java/com/acme/invoice/identity/`: authentication and current-user scope.
- `backend/src/main/java/com/acme/invoice/credential/`: encrypted Paddle credentials.
- `backend/src/main/java/com/acme/invoice/ingestion/`: upload policy, storage, and batches.
- `backend/src/main/java/com/acme/invoice/ocr/`: provider contract and Baidu adapter.
- `backend/src/main/java/com/acme/invoice/invoice/`: normalized invoice model and review operations.
- `backend/src/main/java/com/acme/invoice/verification/`: deterministic validation rules.
- `backend/src/main/java/com/acme/invoice/deduplication/`: exact and suspected duplicate detection.
- `backend/src/main/java/com/acme/invoice/ledger/`: accepted-invoice queries and exports.
- `backend/src/main/java/com/acme/invoice/integration/`: idempotent ERP API.
- `backend/src/main/resources/db/migration/`: Flyway migrations.
- `frontend/`: Vue application.
- `frontend/src/features/auth/`: login and registration.
- `frontend/src/features/settings/`: Paddle credential form.
- `frontend/src/features/imports/`: upload and batch progress.
- `frontend/src/features/review/`: three-column review workbench.
- `frontend/src/features/ledger/`: ledger and exports.
- `e2e/`: Playwright cross-application tests.
- `benchmark/`: synthetic or desensitized expected OCR and rule fixtures.

---

### Task 1: Bootstrap a Testable Monorepo

**Files:**
- Create: `.gitignore`
- Create: `.env.example`
- Create: `compose.yaml`
- Create: `backend/settings.gradle.kts`
- Create: `backend/build.gradle.kts`
- Create: `backend/src/main/java/com/acme/invoice/InvoiceApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/acme/invoice/InvoiceApplicationTest.java`
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`
- Create: `frontend/index.html`
- Create: `frontend/src/main.ts`
- Create: `frontend/src/App.vue`
- Create: `frontend/src/App.spec.ts`

**Interfaces:**
- Produces: a backend runnable with `.\gradlew.bat test` and a frontend runnable with `npm test`.

- [ ] **Step 1: Add repository exclusions, local services, and the Gradle Wrapper**

```gitignore
.superpowers/
.env
data/
backend/build/
frontend/node_modules/
frontend/dist/
e2e/test-results/
e2e/playwright-report/
```

Use PostgreSQL 17 in `compose.yaml`, map port `5432`, database `invoice`, user `invoice`, and read the password from `POSTGRES_PASSWORD`.

Generate the backend wrapper and dependency manifest without requiring global Gradle:

```powershell
Invoke-WebRequest "https://start.spring.io/starter.zip?type=gradle-project-kotlin&language=java&bootVersion=3.5.6&baseDir=backend&groupId=com.acme&artifactId=invoice&name=invoice&packageName=com.acme.invoice&packaging=jar&javaVersion=21&dependencies=web,validation,security,data-jpa,webflux,flyway,postgresql,actuator" -OutFile backend.zip
Expand-Archive backend.zip -DestinationPath .
Remove-Item backend.zip
```

- [ ] **Step 2: Write failing backend and frontend smoke tests**

```java
@SpringBootTest
class InvoiceApplicationTest {
    @Test void contextLoads() {}
}
```

```ts
import { mount } from '@vue/test-utils'
import App from './App.vue'

it('renders the product name', () => {
  expect(mount(App).text()).toContain('智能核票')
})
```

- [ ] **Step 3: Run both tests and verify the bootstrap fails**

Run: `cd backend; .\gradlew.bat test`

Expected: FAIL because the application behavior is not complete.

Run: `cd frontend; npm install; npm test -- --run`

Expected: FAIL until `App.vue` renders `智能核票`.

- [ ] **Step 4: Add minimal application entry points and dependency manifests**

Backend dependencies must include Spring Web, Validation, Security, Data JPA, WebFlux, Flyway, PostgreSQL, Actuator, Testcontainers PostgreSQL, and Spring Security Test. Frontend dependencies must include Vue, Pinia, Vue Router, Axios, Vitest, Vue Test Utils, jsdom, TypeScript, and Vite.

```java
@SpringBootApplication
public class InvoiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InvoiceApplication.class, args);
    }
}
```

```vue
<template><main>智能核票</main></template>
```

- [ ] **Step 5: Run smoke tests**

Run: `cd backend; .\gradlew.bat test`

Expected: PASS.

Run: `cd frontend; npm test -- --run`

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add .gitignore .env.example compose.yaml backend frontend
git commit -m "build: bootstrap invoice application"
```

---

### Task 2: Add Authentication and User Isolation

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__identity.sql`
- Create: `backend/src/main/java/com/acme/invoice/identity/User.java`
- Create: `backend/src/main/java/com/acme/invoice/identity/UserRepository.java`
- Create: `backend/src/main/java/com/acme/invoice/identity/CurrentUser.java`
- Create: `backend/src/main/java/com/acme/invoice/identity/AuthService.java`
- Create: `backend/src/main/java/com/acme/invoice/identity/AuthController.java`
- Create: `backend/src/main/java/com/acme/invoice/identity/SecurityConfig.java`
- Test: `backend/src/test/java/com/acme/invoice/identity/AuthControllerTest.java`
- Test: `backend/src/test/java/com/acme/invoice/identity/UserIsolationTest.java`

**Interfaces:**
- Produces: `CurrentUser.requireId(): UUID`.
- Produces: `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`.
- Produces: an HttpOnly `access_token` cookie and CSRF cookie/header support.

- [ ] **Step 1: Write authentication and isolation tests**

```java
mockMvc.perform(post("/api/auth/register")
        .with(csrf())
        .contentType(APPLICATION_JSON)
        .content("""{"email":"a@example.com","password":"ValidPass123!"}"""))
    .andExpect(status().isCreated())
    .andExpect(cookie().httpOnly("access_token", true));
```

Add a second test proving user A cannot retrieve an entity owned by user B through a repository method scoped as `findByIdAndUserId`.

- [ ] **Step 2: Run focused tests**

Run: `cd backend; .\gradlew.bat test --tests "*AuthControllerTest" --tests "*UserIsolationTest"`

Expected: FAIL because identity classes and migration do not exist.

- [ ] **Step 3: Create schema and implementation**

`V1__identity.sql` must create `users(id uuid primary key, email varchar(320) unique not null, password_hash varchar(100) not null, status varchar(20) not null, created_at timestamptz not null)`.

```java
public interface CurrentUser {
    UUID requireId();
}
```

Configure BCrypt, stateless Spring Security, CSRF cookie tokens, and JWT validation. Read `APP_JWT_SECRET` from configuration and reject startup when it is absent or shorter than 32 bytes.

- [ ] **Step 4: Run authentication tests and the full backend suite**

Run: `cd backend; .\gradlew.bat test --tests "*AuthControllerTest" --tests "*UserIsolationTest"; .\gradlew.bat test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend
git commit -m "feat: add secure user authentication"
```

---

### Task 3: Encrypt and Validate User Paddle Credentials

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__ocr_credentials.sql`
- Create: `backend/src/main/java/com/acme/invoice/credential/OcrCredential.java`
- Create: `backend/src/main/java/com/acme/invoice/credential/OcrCredentialRepository.java`
- Create: `backend/src/main/java/com/acme/invoice/credential/SecretCipher.java`
- Create: `backend/src/main/java/com/acme/invoice/credential/EncryptedSecret.java`
- Create: `backend/src/main/java/com/acme/invoice/credential/PaddleCredential.java`
- Create: `backend/src/main/java/com/acme/invoice/credential/CredentialValidation.java`
- Create: `backend/src/main/java/com/acme/invoice/credential/AesGcmSecretCipher.java`
- Create: `backend/src/main/java/com/acme/invoice/credential/OcrCredentialService.java`
- Create: `backend/src/main/java/com/acme/invoice/credential/OcrCredentialController.java`
- Create: `backend/src/main/java/com/acme/invoice/ocr/PaddleTokenClient.java`
- Test: `backend/src/test/java/com/acme/invoice/credential/OcrCredentialServiceTest.java`
- Test: `backend/src/test/java/com/acme/invoice/credential/OcrCredentialControllerTest.java`

**Interfaces:**
- Produces: `SecretCipher.encrypt(String): EncryptedSecret`.
- Produces: `SecretCipher.decrypt(EncryptedSecret): String`.
- Produces: `PaddleTokenClient.validate(String apiKey, String secretKey): CredentialValidation`.
- Produces: `/api/settings/ocr-credentials` GET, PUT, DELETE and `/test` POST.

- [ ] **Step 1: Write encryption and API-response tests**

```java
var encrypted = cipher.encrypt("secret-value");
assertThat(encrypted.ciphertext()).doesNotContain("secret-value");
assertThat(cipher.decrypt(encrypted)).isEqualTo("secret-value");
```

Controller assertions must prove the JSON contains `configured`, `apiKeyMask`, and `lastValidatedAt`, and never contains `secretKey`, ciphertext, IV, or authentication tag.

- [ ] **Step 2: Run tests and verify failure**

Run: `cd backend; .\gradlew.bat test --tests "*OcrCredential*"`

Expected: FAIL because credential components do not exist.

- [ ] **Step 3: Implement AES-GCM storage and Paddle token validation**

Decode `APP_CREDENTIAL_MASTER_KEY` as a 32-byte Base64 key. Generate a fresh 12-byte IV per encryption and store IV, ciphertext, and tag. `V2__ocr_credentials.sql` must enforce one credential row per user.

```java
public record EncryptedSecret(byte[] iv, byte[] ciphertext) {}
public record PaddleCredential(String apiKey, String secretKey) {}
public record CredentialValidation(boolean valid, String message) {}
```

Call Baidu's OAuth token endpoint with WebClient. Map HTTP 400/401 to `valid=false`; redact query parameters and response bodies from logs.

- [ ] **Step 4: Run focused and full tests**

Run: `cd backend; .\gradlew.bat test --tests "*OcrCredential*"; .\gradlew.bat test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend
git commit -m "feat: store encrypted Paddle credentials"
```

---

### Task 4: Add Secure Batch Upload and File Storage

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__imports.sql`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/UploadPolicy.java`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/FileStorage.java`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/StoredFile.java`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/ImportBatchView.java`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/LocalFileStorage.java`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/ImportBatch.java`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/InvoiceFile.java`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/ImportBatchRepository.java`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/InvoiceFileRepository.java`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/ImportService.java`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/ImportController.java`
- Test: `backend/src/test/java/com/acme/invoice/ingestion/UploadPolicyTest.java`
- Test: `backend/src/test/java/com/acme/invoice/ingestion/ImportControllerTest.java`

**Interfaces:**
- Produces: `FileStorage.store(UUID userId, UUID fileId, InputStream): StoredFile`.
- Produces: `ImportService.createBatch(UUID userId, List<MultipartFile>): ImportBatchView`.
- Produces: `POST /api/import-batches`, `GET /api/import-batches/{id}`, and authenticated file preview.

- [ ] **Step 1: Write policy and ownership tests**

Test exactly 100 accepted files and 101 rejected files; 20 MiB accepted and 20 MiB plus one byte rejected; JPEG, PNG, and PDF signatures accepted; extension/MIME mismatches rejected; a 21-page PDF rejected. Prove user B receives 404 for user A's batch and file.

- [ ] **Step 2: Run focused tests**

Run: `cd backend; .\gradlew.bat test --tests "*UploadPolicyTest" --tests "*ImportControllerTest"`

Expected: FAIL because ingestion classes do not exist.

- [ ] **Step 3: Implement storage and batch creation**

Use Apache Tika for content detection and PDFBox for page counting. Store files below `${APP_STORAGE_ROOT}/{userId}/{fileId}` using generated identifiers, never the supplied filename. Compute SHA-256 while streaming.

```java
public record StoredFile(String storageKey, long size, String sha256, String mediaType) {}
public record ImportBatchView(UUID id, int totalFiles, String status) {}
```

Create all batch and file rows in one transaction; if storage fails, delete already-written files and roll back.

- [ ] **Step 4: Run tests**

Run: `cd backend; .\gradlew.bat test --tests "*UploadPolicyTest" --tests "*ImportControllerTest"; .\gradlew.bat test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend
git commit -m "feat: add secure invoice batch uploads"
```

---

### Task 5: Integrate Paddle OCR and Normalize Invoice Fields

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__invoices_and_ocr.sql`
- Create: `backend/src/main/java/com/acme/invoice/ocr/OcrProvider.java`
- Create: `backend/src/main/java/com/acme/invoice/ocr/PaddleOcrProvider.java`
- Create: `backend/src/main/java/com/acme/invoice/ocr/OcrDocument.java`
- Create: `backend/src/main/java/com/acme/invoice/ocr/OcrField.java`
- Create: `backend/src/main/java/com/acme/invoice/ocr/OcrException.java`
- Create: `backend/src/main/java/com/acme/invoice/invoice/Invoice.java`
- Create: `backend/src/main/java/com/acme/invoice/invoice/InvoiceType.java`
- Create: `backend/src/main/java/com/acme/invoice/invoice/InvoiceStatus.java`
- Create: `backend/src/main/java/com/acme/invoice/invoice/NormalizedInvoice.java`
- Create: `backend/src/main/java/com/acme/invoice/invoice/InvoiceNormalizer.java`
- Create: `backend/src/main/java/com/acme/invoice/ocr/OcrProcessingService.java`
- Test: `backend/src/test/java/com/acme/invoice/ocr/PaddleOcrProviderTest.java`
- Test: `backend/src/test/java/com/acme/invoice/invoice/InvoiceNormalizerTest.java`

**Interfaces:**
- Consumes: `OcrCredentialService.decryptForUser(UUID): PaddleCredential`.
- Consumes: `FileStorage.open(UUID userId, String storageKey): InputStream`.
- Produces: `OcrProvider.recognize(PaddleCredential, StoredFile): OcrDocument`.
- Produces: `InvoiceNormalizer.normalize(OcrDocument): NormalizedInvoice`.
- Produces: statuses `QUEUED`, `OCR_PROCESSING`, `REVIEW_REQUIRED`, `PASSED`, `BLOCKED_DUPLICATE`, `FAILED`.

- [ ] **Step 1: Write provider contract tests with WireMock**

Cover successful token acquisition and OCR mapping, HTTP 429 then success, three HTTP 500 responses, HTTP 401 without retry, and timeout. Assert transient failures make at most three attempts and credential errors make one.

- [ ] **Step 2: Write normalization tests**

Use fixed JSON fixtures for all eight invoice types. Assert normalized dates use ISO dates, money uses `BigDecimal`, codes and numbers preserve leading zeroes, and every mapped field retains confidence and source text.

- [ ] **Step 3: Run focused tests**

Run: `cd backend; .\gradlew.bat test --tests "*PaddleOcrProviderTest" --tests "*InvoiceNormalizerTest"`

Expected: FAIL because OCR and normalization components do not exist.

- [ ] **Step 4: Implement provider, persistence, and normalization**

```java
public interface OcrProvider {
    OcrDocument recognize(PaddleCredential credential, StoredFile file);
}

public record OcrField(String name, String rawValue, BigDecimal confidence) {}
public record OcrDocument(String providerRequestId, String rawJson, List<OcrField> fields) {}
public record NormalizedInvoice(
    InvoiceType type,
    String invoiceCode,
    String invoiceNumber,
    LocalDate issueDate,
    BigDecimal amount,
    BigDecimal tax,
    BigDecimal total,
    Map<String, OcrField> fields
) {}
```

Persist immutable raw OCR output separately from normalized and manually corrected values. Transition each file independently; a failure must not mark sibling files failed.

- [ ] **Step 5: Run focused and full tests**

Run: `cd backend; .\gradlew.bat test --tests "*PaddleOcrProviderTest" --tests "*InvoiceNormalizerTest"; .\gradlew.bat test`

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend
git commit -m "feat: integrate Paddle OCR and normalization"
```

---

### Task 6: Implement Verification and Duplicate Detection

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__verification_and_duplicates.sql`
- Create: `backend/src/main/java/com/acme/invoice/verification/VerificationRule.java`
- Create: `backend/src/main/java/com/acme/invoice/verification/VerificationIssue.java`
- Create: `backend/src/main/java/com/acme/invoice/verification/VerificationEngine.java`
- Create: `backend/src/main/java/com/acme/invoice/verification/RequiredFieldRule.java`
- Create: `backend/src/main/java/com/acme/invoice/verification/AmountConsistencyRule.java`
- Create: `backend/src/main/java/com/acme/invoice/verification/ConfidenceRule.java`
- Create: `backend/src/main/java/com/acme/invoice/verification/DateRangeRule.java`
- Create: `backend/src/main/java/com/acme/invoice/deduplication/ExactDuplicateKeyFactory.java`
- Create: `backend/src/main/java/com/acme/invoice/deduplication/DuplicateScore.java`
- Create: `backend/src/main/java/com/acme/invoice/deduplication/DeduplicationResult.java`
- Create: `backend/src/main/java/com/acme/invoice/deduplication/SuspectedDuplicateScorer.java`
- Create: `backend/src/main/java/com/acme/invoice/deduplication/DeduplicationService.java`
- Test: `backend/src/test/java/com/acme/invoice/verification/VerificationEngineTest.java`
- Test: `backend/src/test/java/com/acme/invoice/deduplication/DeduplicationServiceTest.java`
- Test: `backend/src/test/java/com/acme/invoice/deduplication/ConcurrentDeduplicationTest.java`

**Interfaces:**
- Produces: `VerificationEngine.verify(NormalizedInvoice): List<VerificationIssue>`.
- Produces: `ExactDuplicateKeyFactory.create(NormalizedInvoice): Optional<String>`.
- Produces: `SuspectedDuplicateScorer.score(NormalizedInvoice, NormalizedInvoice): DuplicateScore`.
- Produces: `DeduplicationService.evaluate(UUID userId, Invoice): DeduplicationResult`.

- [ ] **Step 1: Write deterministic rule tests**

Test missing required fields, invalid formats, future and implausibly old dates, confidence below configured threshold, exact amount equality, and one-cent rounding tolerance. Assert unknown types and low-confidence critical fields produce `REVIEW_REQUIRED`, not `FAILED`.

- [ ] **Step 2: Write exact and suspected duplicate tests**

Assert VAT duplicates use type, code, and number; railway duplicates use electronic ticket number; flight duplicates use electronic ticket number; records without official unique numbers never become definite duplicates. Assert suspected matches include an explainable list of matched fields.

- [ ] **Step 3: Write the concurrent insertion test**

Start two transactions inserting the same user and exact key concurrently. Assert one becomes `PASSED` or `REVIEW_REQUIRED`, the other becomes `BLOCKED_DUPLICATE`, and exactly one active unique-key row exists.

- [ ] **Step 4: Run focused tests**

Run: `cd backend; .\gradlew.bat test --tests "*VerificationEngineTest" --tests "*DeduplicationServiceTest" --tests "*ConcurrentDeduplicationTest"`

Expected: FAIL because engines and database constraints do not exist.

- [ ] **Step 5: Implement rules and database-backed deduplication**

```java
public interface VerificationRule {
    List<VerificationIssue> evaluate(NormalizedInvoice invoice);
}

public record DuplicateScore(BigDecimal score, List<String> matchedFields) {}
public record DeduplicationResult(
    boolean exactDuplicate,
    UUID matchedInvoiceId,
    DuplicateScore suspectedMatch
) {}
```

Add a partial unique index on `(user_id, exact_duplicate_key)` where the key is not null and the invoice is not a rejected test record. Catch unique-constraint conflicts and resolve the existing invoice for an explainable blocked result.

- [ ] **Step 6: Run focused and full tests**

Run: `cd backend; .\gradlew.bat test --tests "*Verification*" --tests "*Deduplication*"; .\gradlew.bat test`

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend
git commit -m "feat: verify invoices and block duplicates"
```

---

### Task 7: Add Review, Ledger, Export, SSE, and ERP APIs

**Files:**
- Create: `backend/src/main/java/com/acme/invoice/invoice/InvoiceController.java`
- Create: `backend/src/main/java/com/acme/invoice/invoice/InvoiceReviewService.java`
- Create: `backend/src/main/java/com/acme/invoice/ingestion/BatchProgressController.java`
- Create: `backend/src/main/java/com/acme/invoice/ledger/LedgerService.java`
- Create: `backend/src/main/java/com/acme/invoice/ledger/LedgerController.java`
- Create: `backend/src/main/java/com/acme/invoice/ledger/InvoiceExportService.java`
- Create: `backend/src/main/java/com/acme/invoice/integration/ErpController.java`
- Create: `backend/src/main/java/com/acme/invoice/integration/IdempotencyService.java`
- Create: `backend/src/main/java/com/acme/invoice/audit/AuditService.java`
- Test: `backend/src/test/java/com/acme/invoice/invoice/InvoiceReviewControllerTest.java`
- Test: `backend/src/test/java/com/acme/invoice/ledger/InvoiceExportTest.java`
- Test: `backend/src/test/java/com/acme/invoice/integration/ErpControllerTest.java`

**Interfaces:**
- Produces: invoice list/detail/update/retry endpoints and authenticated preview.
- Produces: `GET /api/import-batches/{id}/events` as SSE.
- Produces: ledger query and CSV/XLSX export.
- Produces: idempotent ERP query and push endpoints.

- [ ] **Step 1: Write review and access tests**

Assert manual correction records old and new values in audit data, reruns verification and deduplication, and cannot force a definite duplicate to `PASSED`. Assert cross-user IDs return 404.

- [ ] **Step 2: Write export and ERP tests**

Create one invoice in each status and assert only `PASSED` appears in CSV, XLSX, and ERP responses. Repeat an ERP push with the same idempotency key and assert the original response is returned without a second side effect.

- [ ] **Step 3: Run tests and verify failure**

Run: `cd backend; .\gradlew.bat test --tests "*InvoiceReviewControllerTest" --tests "*InvoiceExportTest" --tests "*ErpControllerTest"`

Expected: FAIL because review, export, and ERP APIs do not exist.

- [ ] **Step 4: Implement APIs and audit boundaries**

Use Apache POI streaming workbooks for XLSX. Sanitize cells beginning with `=`, `+`, `-`, or `@` to prevent spreadsheet-formula injection. Emit progress events containing batch totals and statuses, never credentials or raw OCR JSON.

- [ ] **Step 5: Run tests**

Run: `cd backend; .\gradlew.bat test --tests "*InvoiceReviewControllerTest" --tests "*InvoiceExportTest" --tests "*ErpControllerTest"; .\gradlew.bat test`

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend
git commit -m "feat: add review ledger export and ERP APIs"
```

---

### Task 8: Build the Graphite UI Shell, Authentication, and Settings

**Files:**
- Create: `frontend/src/styles/tokens.css`
- Create: `frontend/src/styles/base.css`
- Create: `frontend/src/router.ts`
- Create: `frontend/src/lib/api.ts`
- Create: `frontend/src/stores/session.ts`
- Create: `frontend/src/components/AppShell.vue`
- Create: `frontend/src/features/auth/LoginView.vue`
- Create: `frontend/src/features/auth/RegisterView.vue`
- Create: `frontend/src/features/settings/OcrCredentialView.vue`
- Test: `frontend/src/features/auth/LoginView.spec.ts`
- Test: `frontend/src/features/settings/OcrCredentialView.spec.ts`

**Interfaces:**
- Consumes: auth and OCR credential APIs from Tasks 2 and 3.
- Produces: authenticated route shell and credential setup flow.

- [ ] **Step 1: Write component tests**

Assert login sends CSRF and credentials, validation errors are announced, the credential form never repopulates the Secret Key, and configured state displays only a mask and validation time.

- [ ] **Step 2: Run focused tests**

Run: `cd frontend; npm test -- --run src/features/auth/LoginView.spec.ts src/features/settings/OcrCredentialView.spec.ts`

Expected: FAIL because views and stores do not exist.

- [ ] **Step 3: Implement design tokens and accessible views**

```css
:root {
  --canvas: #f5f5f7;
  --surface: #ffffff;
  --text: #1d1d1f;
  --muted: #6e6e73;
  --border: rgba(0, 0, 0, 0.08);
  --primary: #1d1d1f;
  --focus: #0071e3;
  --radius-card: 16px;
  --radius-control: 10px;
  --shadow-card: 0 8px 28px rgba(0, 0, 0, 0.06);
}
```

Use visible `:focus-visible` outlines, labels for every input, `aria-live` for API errors, and one solid action per view.

- [ ] **Step 4: Run frontend tests and accessibility checks**

Run: `cd frontend; npm test -- --run; npm run build`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add frontend
git commit -m "feat: add authentication and OCR settings UI"
```

---

### Task 9: Build Upload, Review Workbench, and Ledger UI

**Files:**
- Create: `frontend/src/features/imports/UploadPanel.vue`
- Create: `frontend/src/features/imports/BatchProgress.vue`
- Create: `frontend/src/features/review/ReviewWorkbench.vue`
- Create: `frontend/src/features/review/ReviewQueue.vue`
- Create: `frontend/src/features/review/InvoicePreview.vue`
- Create: `frontend/src/features/review/InvoiceFields.vue`
- Create: `frontend/src/features/review/VerificationIssues.vue`
- Create: `frontend/src/features/review/DuplicateEvidence.vue`
- Create: `frontend/src/features/ledger/LedgerView.vue`
- Create: `frontend/src/features/ledger/ExportMenu.vue`
- Test: `frontend/src/features/imports/UploadPanel.spec.ts`
- Test: `frontend/src/features/review/ReviewWorkbench.spec.ts`
- Test: `frontend/src/features/ledger/LedgerView.spec.ts`

**Interfaces:**
- Consumes: import, SSE, invoice review, duplicate evidence, ledger, and export APIs.
- Produces: the confirmed three-column workflow.

- [ ] **Step 1: Write upload tests**

Assert drag-and-drop and file input share validation, invalid type/size/count errors are shown before network calls, and SSE failure falls back to periodic batch polling.

- [ ] **Step 2: Write workbench tests**

Assert queue keyboard navigation changes the preview and fields; low-confidence fields are labeled; definite duplicate evidence disables approval; saving a correction refreshes issues and duplicate evidence.

- [ ] **Step 3: Write ledger tests**

Assert only accepted records appear, filters map to query parameters, export retains filters, and empty/loading/error states are distinguishable.

- [ ] **Step 4: Run focused tests**

Run: `cd frontend; npm test -- --run src/features/imports src/features/review src/features/ledger`

Expected: FAIL because feature components do not exist.

- [ ] **Step 5: Implement the workflow**

Use CSS Grid columns `220px minmax(420px, 1fr) 340px`, collapse the queue to a drawer below 1100 px, and stack preview above fields below 760 px. Keep selected rows neutral and use a border plus elevation instead of a colored block.

- [ ] **Step 6: Run tests and build**

Run: `cd frontend; npm test -- --run; npm run build`

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add frontend
git commit -m "feat: add invoice review workspace"
```

---

### Task 10: Add End-to-End, Benchmark, and Security Verification

**Files:**
- Create: `e2e/package.json`
- Create: `e2e/playwright.config.ts`
- Create: `e2e/tests/invoice-flow.spec.ts`
- Create: `benchmark/fixtures/expected-invoices.json`
- Create: `backend/src/test/java/com/acme/invoice/benchmark/InvoiceBenchmarkTest.java`
- Create: `backend/src/test/java/com/acme/invoice/security/CrossUserAccessTest.java`
- Create: `backend/src/test/java/com/acme/invoice/security/UploadSecurityTest.java`

**Interfaces:**
- Consumes: all completed frontend and backend behavior.
- Produces: repeatable acceptance evidence for accuracy, recall, duplicate interception, access isolation, and upload safety.

- [ ] **Step 1: Add synthetic benchmark fixtures**

Create at least two legal-to-store synthetic fixtures per supported invoice type, plus labeled missing-field, inconsistent-amount, low-confidence, exact-duplicate, and suspected-duplicate cases. Store expected normalized fields and labels in JSON; do not store real personal or business data.

- [ ] **Step 2: Write benchmark tests**

Calculate field accuracy as correct expected fields divided by all expected fields, anomaly recall as detected labeled anomalies divided by all labeled anomalies, and exact-duplicate interception as blocked duplicates divided by all exact duplicates.

```java
assertThat(metrics.fieldAccuracy()).isGreaterThanOrEqualTo(new BigDecimal("0.98"));
assertThat(metrics.anomalyRecall()).isGreaterThanOrEqualTo(new BigDecimal("0.95"));
assertThat(metrics.duplicateInterception()).isEqualByComparingTo(BigDecimal.ONE);
```

- [ ] **Step 3: Write Playwright and security tests**

The browser test must register two users, configure mocked Paddle credentials, upload a mixed batch, review one item, observe one duplicate block, export the ledger, and verify the second user cannot access the first user's IDs. Backend security tests must cover sequential IDs/UUIDs, file preview, SSE, export, and ERP endpoints.

- [ ] **Step 4: Run acceptance suites**

Run: `cd backend; .\gradlew.bat test`

Expected: PASS with benchmark thresholds met against deterministic fixtures.

Run: `cd frontend; npm test -- --run; npm run build`

Expected: PASS.

Run: `cd e2e; npm install; npx playwright install chromium; npm test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend frontend e2e benchmark
git commit -m "test: verify invoice workflow and acceptance targets"
```

---

### Task 11: Document Configuration and Perform Final Verification

**Files:**
- Create: `README.md`
- Create: `docs/api/erp-integration.md`
- Modify: `.env.example`

**Interfaces:**
- Produces: reproducible local startup, configuration, Paddle setup, backup, export, ERP, and verification instructions.

- [ ] **Step 1: Write operational documentation**

Document required variables `POSTGRES_PASSWORD`, `APP_JWT_SECRET`, `APP_CREDENTIAL_MASTER_KEY`, `APP_STORAGE_ROOT`, backend/frontend origins, and Paddle endpoint configuration. Include PowerShell commands to generate 32-byte secrets, start PostgreSQL, run migrations, launch both applications, and run every test suite.

- [ ] **Step 2: Document ERP contracts**

Specify request/response JSON, authentication, idempotency header, status codes, user scoping, pagination, and the rule that only `PASSED` invoices are visible.

- [ ] **Step 3: Run secret and placeholder scans**

Run: `rg -n "(secretKey|apiKey|password)\\s*[:=]\\s*['\\\"][^$<{]" . -g "!docs/**" -g "!**/node_modules/**" -g "!**/build/**"`

Expected: no hard-coded credential matches.

Run: `rg -n "T[B]D|T[O]DO|FIX[M]E|implement[ ]later" README.md docs backend frontend e2e benchmark`

Expected: no matches.

- [ ] **Step 4: Run final verification**

Run: `docker compose config`

Expected: valid resolved Compose configuration.

Run: `cd backend; .\gradlew.bat clean test`

Expected: PASS.

Run: `cd frontend; npm ci; npm test -- --run; npm run build`

Expected: PASS.

Run: `cd e2e; npm ci; npm test`

Expected: PASS.

- [ ] **Step 5: Check repository state and commit**

Run: `git status --short`

Expected: only intended documentation changes before commit and a clean tree afterward.

```powershell
git add README.md docs .env.example
git commit -m "docs: add setup and integration guide"
```
