# Impekable Converge — Remediation Plan

> **Purpose:** Step-by-step developer guide to address all functional gaps, LLM prompt issues, and technical debt identified in the code review. Each step is a single, reviewable change.
>
> **How to use:** Work through each step sequentially. Complete one step, get it reviewed/approved, then move to the next. Steps within a phase are ordered by dependency — do not skip ahead.
>
> **Scope exclusion:** CRM integration (HubSpot/GoHighLevel) is excluded from this plan.

---

## PHASE 1 — CRITICAL SECURITY FIXES

> These must be done first. The application has production credentials committed to git and open endpoints.

### Step 1.1 — Remove Hardcoded Secrets from Properties Files

**Problem:** `application.properties` contains plaintext API keys for Twilio, OpenAI, ElevenLabs. `application-local.properties` contains the database password. All are committed to git history.

**What to do:**
1. Edit `src/main/resources/application.properties`:
   - Replace `twilio.account-sid=AC248f...` with `twilio.account-sid=${TWILIO_ACCOUNT_SID}`
   - Replace `twilio.auth-token=1a6926e...` with `twilio.auth-token=${TWILIO_AUTH_TOKEN}`
   - Replace `twilio.phone-number=+1641...` with `twilio.phone-number=${TWILIO_PHONE_NUMBER}`
   - Replace `openai.api-key=sk-proj-...` with `openai.api-key=${OPENAI_API_KEY}`
   - Replace `elevenlabs.api-key=6b756...` with `elevenlabs.api-key=${ELEVENLABS_API_KEY}`
   - Replace `elevenlabs.voice-id=21m00...` with `elevenlabs.voice-id=${ELEVENLABS_VOICE_ID}`
   - Replace the hardcoded `twilio.media-stream-url` and `twilio.base-url` with `${TWILIO_MEDIA_STREAM_URL}` and `${TWILIO_BASE_URL}`
2. Edit `src/main/resources/application-local.properties`:
   - Replace `spring.datasource.password=.@{Zt...` with `spring.datasource.password=${DB_PASSWORD}`
   - Replace `spring.datasource.username=postgres` with `spring.datasource.username=${DB_USERNAME:postgres}`
   - Replace the hardcoded datasource URL with `spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/postgres}`
3. Create a `.env.example` file in project root listing all required environment variables (without values) as documentation.
4. Add `*.env` and `application-local.properties` to `.gitignore`.
5. Remove all `@Value` annotations that have hardcoded defaults for secrets. For example, `SttService.java:33` has `@Value("${openai.api-key:${OPENAI_API_KEY:}}")` — simplify to `@Value("${openai.api-key}")`. The app should fail to start if secrets are missing, not silently degrade.
6. Ensure the `Dockerfile` passes environment variables through (it already uses `ENTRYPOINT` with `java -jar`, so env vars flow naturally).

**Files to change:** `application.properties`, `application-local.properties`, `.gitignore`, new `.env.example`

**Verification:** App should fail to start if any secret is missing. No secrets in any properties file.

---

### Step 1.2 — Rotate All Exposed API Keys

**Problem:** Keys have been in git history. Even after removing from files, anyone with repo access can find them in old commits.

**What to do:**
1. Rotate the Twilio Account SID and Auth Token from the Twilio Console.
2. Rotate the OpenAI API key from the OpenAI dashboard.
3. Rotate the ElevenLabs API key from the ElevenLabs dashboard.
4. Change the PostgreSQL database password.
5. Update all deployment environments (Google Cloud Run, local dev) with the new values as environment variables.

**Files to change:** None (external systems only).

**Verification:** Old keys no longer work. New keys are only in environment variables.

---

### Step 1.3 — Add Twilio Webhook Signature Verification

**Problem:** `VoiceController` endpoints accept any POST request without verifying it actually came from Twilio. Anyone can send fake call events.

**What to do:**
1. Add the Twilio SDK's `RequestValidator` as a Spring bean or create a utility class.
2. Create a Spring `HandlerInterceptor` or `Filter` that:
   - Extracts the `X-Twilio-Signature` header from the request.
   - Reconstructs the full request URL (scheme + host + path).
   - Collects all POST parameters.
   - Calls `RequestValidator.validate(url, params, signature)` using the Twilio Auth Token.
   - Returns 403 Forbidden if validation fails.
3. Apply this filter to all `/twilio/*` and `/inbound` endpoints.
4. Do NOT apply it to `/audio/play/{id}` (Twilio fetches this without signing) or `/media-stream` (WebSocket — handled separately).
5. For local development, add a config flag `twilio.validate-signatures=${TWILIO_VALIDATE_SIGNATURES:true}` to disable in dev if needed.

**Files to change:** New `TwilioSignatureFilter.java` (or interceptor), `WebMvcConfig` or `SecurityConfig` to register it. `application.properties` for the flag.

**Verification:** Sending a POST to `/twilio/voice/inbound` without a valid signature returns 403. Twilio calls still work.

---

### Step 1.4 — Restrict WebSocket CORS

**Problem:** `WebSocketConfig.java:23` has `.setAllowedOrigins("*")`, allowing any website to connect to the media stream.

**What to do:**
1. In `WebSocketConfig.java`, change `.setAllowedOrigins("*")` to `.setAllowedOrigins("${websocket.allowed-origins:https://your-production-domain.com}")`.
2. Add `websocket.allowed-origins` to `application.properties` with production domain.
3. For local development, set it to `*` only in `application-local.properties`.
4. Twilio's media streams connect from Twilio's infrastructure — they don't use browser CORS. Twilio sends raw WebSocket connections, so CORS restrictions don't affect Twilio streams. The `.setAllowedOrigins()` only restricts browser-based WebSocket connections.

**Files to change:** `WebSocketConfig.java`, `application.properties`

**Verification:** Browser-based WebSocket from unknown origins is rejected. Twilio media streams still work.

---

### Step 1.5 — Remove PII from Application Logs

**Problem:** Phone numbers, caller speech, and patient names are logged in plaintext.

**What to do:**
1. Create a `LogSanitizer` utility class with methods:
   - `maskPhone(String phone)` — returns `+1***...48` (first 2 + last 2 digits).
   - `maskName(String name)` — returns `J***n` (first + last character).
2. Update `MediaStreamHandler.java`:
   - Line 103: Mask phone number → `log.info("From: {}", LogSanitizer.maskPhone(state.fromNumber))`
   - Line 218: Remove or truncate user speech → `log.info("User utterance received, length={}", userText.length())`
   - Keep full speech logging only at DEBUG level: `log.debug("USER SAID: {}", userText)`
3. Update `VoiceController.java`:
   - Line 68: Mask the `from` parameter.
4. Update `LlmFlowService.java`:
   - Line 96: The raw LLM response may contain patient names — log at DEBUG only.
5. Set production logging level to INFO (not DEBUG) so PII is never logged in production.

**Files to change:** New `LogSanitizer.java` utility, `MediaStreamHandler.java`, `VoiceController.java`, `LlmFlowService.java`

**Verification:** Run the app at INFO level — no phone numbers or full names appear in logs.

---

## PHASE 2 — ARCHITECTURE CLEANUP

> Remove dead code, consolidate duplicates, establish clean boundaries. No new features — just cleaning up.

### Step 2.1 — Remove Dead Code: BookingFlowService

**Problem:** `BookingFlowService.java` (847 lines) is not injected or called from the active flow path (`MediaStreamHandler`). It is legacy dead code.

**What to do:**
1. Verify `BookingFlowService` is not referenced anywhere in active code:
   - Search for `BookingFlowService` in all Java files.
   - Confirm it is NOT injected in `MediaStreamHandler`, `VoiceController`, or any other active component.
   - If it IS referenced somewhere unexpected, trace the call path and determine if that path is also dead.
2. Delete `BookingFlowService.java`.
3. Delete `PendingStateDto.java` (only used by BookingFlowService).
4. Delete `IntentClassifier.java` if it was only used by BookingFlowService (verify first).
5. Delete `IntentPriorityResolverService.java` if it was only used by BookingFlowService (verify first).
6. Delete `ConversationState.java` enum if only used by BookingFlowService.
7. Run the build to confirm no compilation errors.

**Files to delete:** `BookingFlowService.java`, `PendingStateDto.java`, and any services/enums only used by it.

**Verification:** `mvn compile` succeeds. Application starts and handles calls normally.

---

### Step 2.2 — Remove Dead Code: LlmService

**Problem:** `LlmService.java` (225 lines) is not injected into the active flow. `LlmFlowService` is the active service. However, `LlmFlowService:127` calls the static method `LlmService.formatSlotsAsRanges()`.

**What to do:**
1. Move `formatSlotsAsRanges()`, `parseTimeToMinutes()`, and `minutesToDisplay()` from `LlmService.java` into a new `SlotFormattingUtil.java` utility class (or a `SlotFormattingService` if you prefer injection).
2. Update `LlmFlowService:127` to call the new utility instead of `LlmService.formatSlotsAsRanges()`.
3. Delete `LlmService.java`.
4. Run the build.

**Files to change:** New `SlotFormattingUtil.java`, `LlmFlowService.java` (update import). Delete `LlmService.java`.

**Verification:** `mvn compile` succeeds. LLM replies still work correctly.

---

### Step 2.3 — Extract Caller Phone Resolution to Shared Utility

**Problem:** Phone resolution logic is duplicated in three places: `LlmFlowService:172-183`, `ConfirmationExecutionService:37-45`, and the now-deleted `BookingFlowService`.

**What to do:**
1. Create `CallerPhoneResolver.java` as a `@Component`:
   ```java
   @Component
   public class CallerPhoneResolver {
       @Value("${caller.anonymous-fallback:+100000000}")
       private String anonymousCallerFallback;

       public String resolve(String fromNumber) {
           if (fromNumber == null || fromNumber.isBlank()
               || fromNumber.startsWith("client:")
               || "anonymous".equalsIgnoreCase(fromNumber.trim())
               || "unknown".equalsIgnoreCase(fromNumber.trim())) {
               return anonymousCallerFallback;
           }
           return fromNumber;
       }
   }
   ```
2. Inject `CallerPhoneResolver` into `LlmFlowService` and `ConfirmationExecutionService`.
3. Replace their private `resolveCallerForLookup()` / `resolveCallerPhone()` methods with calls to the shared component.
4. Remove the `@Value("${caller.anonymous-fallback}")` from both services — it now lives in one place.

**Files to change:** New `CallerPhoneResolver.java`, `LlmFlowService.java`, `ConfirmationExecutionService.java`

**Verification:** Booking, cancel, and reschedule flows still work for both identified and anonymous callers.

---

### Step 2.4 — Extract Conversation Orchestration from MediaStreamHandler

**Problem:** `MediaStreamHandler.processUtteranceAsync()` (lines 201-285) directly coordinates STT, LLM, confirmation, execution, TTS, and farewell detection. This belongs in a dedicated service.

**What to do:**
1. Create `ConversationOrchestrator.java` as a `@Service`:
   ```java
   @Service
   public class ConversationOrchestrator {
       // Inject: SttService, LlmFlowService, PendingActionService,
       //         ConfirmationExecutionService, YesNoClassifierService,
       //         ConversationStore, TwilioService, CallerPhoneResolver

       public void processUtterance(String callSid, String fromNumber, byte[] audio) {
           // Move the entire logic from MediaStreamHandler.processUtteranceAsync() here
           // This includes: transcribe → check pending → yes/no classify →
           //   execute or LLM reply → speak response → detect farewell
       }
   }
   ```
2. Move ALL logic from `MediaStreamHandler.processUtteranceAsync()` into `ConversationOrchestrator.processUtterance()`.
3. `MediaStreamHandler.processUtteranceAsync()` should now simply:
   ```java
   CompletableFuture.runAsync(() -> {
       try {
           orchestrator.processUtterance(callSid, fromNumber, utterance);
       } catch (Exception e) {
           log.error("Pipeline error for call {}", callSid, e);
       } finally {
           state.processing = false;
       }
   });
   ```
4. Move the farewell detection logic (`isFarewellResponse()`) into the orchestrator as well.
5. `MediaStreamHandler` should only be responsible for: WebSocket lifecycle, audio buffering, silence detection, and dispatching to the orchestrator.

**Files to change:** New `ConversationOrchestrator.java`, `MediaStreamHandler.java` (simplify)

**Verification:** Full call flow works as before. MediaStreamHandler is now under 150 lines.

---

### Step 2.5 — Centralize ObjectMapper as a Spring Bean

**Problem:** Six separate `new ObjectMapper()` instances across services.

**What to do:**
1. Create `JacksonConfig.java`:
   ```java
   @Configuration
   public class JacksonConfig {
       @Bean
       public ObjectMapper objectMapper() {
           return new ObjectMapper()
               .setSerializationInclusion(JsonInclude.Include.NON_NULL);
       }
   }
   ```
2. In every service/component that creates `private final ObjectMapper mapper = new ObjectMapper()`:
   - Remove the field initializer.
   - Add `ObjectMapper` as a constructor parameter (or use `@Autowired`).
   - Files: `LlmFlowService`, `MediaStreamHandler`, `SttService`, `ElevenLabsVoiceService`, and any remaining.
3. Fix `LlmFlowService` RestTemplate creation at the same time:
   - Change `private final RestTemplate restTemplate = new RestTemplateBuilder().build();` to constructor injection:
     ```java
     public LlmFlowService(RestTemplateBuilder builder, AppointmentService appointmentService, ObjectMapper mapper) {
         this.restTemplate = builder.build();
         this.mapper = mapper;
         this.appointmentService = appointmentService;
     }
     ```

**Files to change:** New `JacksonConfig.java`, `LlmFlowService.java`, `MediaStreamHandler.java`, `SttService.java`, `ElevenLabsVoiceService.java`

**Verification:** `mvn compile` succeeds. JSON parsing works as before.

---

### Step 2.6 — Extract Magic Numbers to Configuration

**Problem:** Hardcoded values scattered across the codebase with no explanation.

**What to do:**
1. Create `ConversationProperties.java` using `@ConfigurationProperties`:
   ```java
   @Configuration
   @ConfigurationProperties(prefix = "conversation")
   public class ConversationProperties {
       private int silenceFrameThreshold = 25;      // MediaStreamHandler:27
       private int minAudioBytes = 16000;            // MediaStreamHandler:28
       private int maxBufferBytes = 64000;           // MediaStreamHandler:29
       private int silenceEnergyThreshold = 8;       // MediaStreamHandler energy check
       private int recentMessageWindow = 6;          // ConversationStore:89
       private double llmTemperature = 0.2;          // LlmFlowService:82
       private int slotLookAheadDays = 7;            // AppointmentService:36
       // getters and setters
   }
   ```
2. Inject `ConversationProperties` into `MediaStreamHandler`, `LlmFlowService`, `AppointmentService`, `ConversationStore`.
3. Replace all magic numbers with config references.
4. Add default values in `application.properties` with explanatory comments.

**Files to change:** New `ConversationProperties.java`, `MediaStreamHandler.java`, `LlmFlowService.java`, `AppointmentService.java`, `ConversationStore.java`, `application.properties`

**Verification:** Same behavior with defaults. Values can now be tuned via config without code changes.

---

### Step 2.7 — Fix Logging Configuration Mismatch

**Problem:** `application.properties:5` configures logging for `com.example.receptionist` but the actual package is `com.ai.receptionist`.

**What to do:**
1. Change `logging.level.com.example.receptionist=INFO` to `logging.level.com.ai.receptionist=INFO`.
2. Add: `logging.level.com.ai.receptionist.websocket=INFO` (for WebSocket handler).
3. Add: `logging.level.com.ai.receptionist.service=INFO`.

**Files to change:** `application.properties`

**Verification:** Log levels actually take effect now.

---

### Step 2.8 — Fix Malformed GroupId in pom.xml

**Problem:** `pom.xml:6` has `<groupId>com.ai.receptionist.simulatorcom.example</groupId>` — concatenated/malformed.

**What to do:**
1. Change to `<groupId>com.ai.receptionist</groupId>` (or whatever the team standard is).

**Files to change:** `pom.xml`

**Verification:** `mvn compile` succeeds.

---

## PHASE 3 — CONCURRENCY & THREAD SAFETY FIXES

> Fix race conditions and thread safety issues that can cause data corruption under concurrent calls.

### Step 3.1 — Fix Non-Thread-Safe ArrayList in ConversationStore

**Problem:** `ConversationStore:68` uses `computeIfAbsent()` which returns an `ArrayList`, then calls `.add()` on it. `ArrayList.add()` is not thread-safe. Two concurrent messages for the same call can corrupt the list.

**What to do:**
1. In `ConversationStore.java`, change the map declaration:
   ```java
   // Before:
   private final Map<String, List<ChatMessage>> conversations = new ConcurrentHashMap<>();

   // After:
   private final Map<String, List<ChatMessage>> conversations = new ConcurrentHashMap<>();
   ```
   And in every place where a new list is created for the map, wrap it:
   ```java
   conversations.computeIfAbsent(callSid, key -> Collections.synchronizedList(new ArrayList<>()));
   ```
2. Also wrap the list returned from DB hydration (around line 42):
   ```java
   conversations.put(callSid, Collections.synchronizedList(new ArrayList<>(fromDb)));
   ```
3. Where the list is iterated (e.g., `getConversationSummary()`), wrap iterations in `synchronized(list)` or create a copy first:
   ```java
   List<ChatMessage> snapshot = new ArrayList<>(history); // copy before iterating
   ```

**Files to change:** `ConversationStore.java`

**Verification:** No `ConcurrentModificationException` under concurrent access. Run a load test with multiple simultaneous calls if possible.

---

### Step 3.2 — Make StreamState Fields Volatile

**Problem:** `MediaStreamHandler.StreamState` has `boolean processing` and `boolean closed` fields that are read/written from different threads without visibility guarantees.

**What to do:**
1. In `MediaStreamHandler.java`, update the `StreamState` inner class:
   ```java
   static class StreamState {
       ByteArrayOutputStream buffer = new ByteArrayOutputStream();
       int silenceFrames = 0;
       volatile boolean processing = false;   // Add volatile
       volatile boolean closed = false;        // Add volatile
       String callSid;
       String fromNumber = "";
       long framesReceived = 0;
   }
   ```

**Files to change:** `MediaStreamHandler.java`

**Verification:** `processing` flag changes are now visible across threads immediately.

---

### Step 3.3 — Add Custom Executor for Async Processing

**Problem:** `CompletableFuture.runAsync()` in `MediaStreamHandler:203` uses `ForkJoinPool.commonPool()` by default. This pool has only CPU-core-count threads. Since the pipeline does blocking I/O (STT, LLM, TTS, Twilio API calls), threads get starved quickly.

**What to do:**
1. Create `AsyncConfig.java`:
   ```java
   @Configuration
   public class AsyncConfig {
       @Bean(name = "voicePipelineExecutor")
       public ExecutorService voicePipelineExecutor() {
           return Executors.newFixedThreadPool(
               Runtime.getRuntime().availableProcessors() * 10,
               new ThreadFactory() {
                   private final AtomicInteger counter = new AtomicInteger(0);
                   public Thread newThread(Runnable r) {
                       Thread t = new Thread(r, "voice-pipeline-" + counter.getAndIncrement());
                       t.setDaemon(true);
                       return t;
                   }
               }
           );
       }
   }
   ```
2. Inject this executor into `MediaStreamHandler` (or into `ConversationOrchestrator` if Step 2.4 is done).
3. Change `CompletableFuture.runAsync(() -> { ... })` to `CompletableFuture.runAsync(() -> { ... }, voicePipelineExecutor)`.
4. Add a timeout:
   ```java
   CompletableFuture.runAsync(() -> { ... }, voicePipelineExecutor)
       .orTimeout(30, TimeUnit.SECONDS)
       .exceptionally(ex -> {
           log.error("Pipeline timed out for call {}", callSid, ex);
           state.processing = false;
           return null;
       });
   ```

**Files to change:** New `AsyncConfig.java`, `MediaStreamHandler.java` (or `ConversationOrchestrator.java`)

**Verification:** Under 10+ concurrent calls, the pipeline doesn't stall. Timeouts fire after 30 seconds.

---

### Step 3.4 — Fix Pending Action Race Condition

**Problem:** `PendingActionService` has TOCTOU race between `hasPendingConfirmation()` (check) and `getPending()` (use). Another thread could clear the pending action between check and use.

**What to do:**
1. Replace the check-then-use pattern with a single atomic operation. In `PendingActionService`, add:
   ```java
   public Optional<PendingActionDto> getIfAwaitingConfirmation(String callSid) {
       PendingActionDto p = pendingByCall.get(callSid);
       if (p != null && p.isAwaitingConfirmation()) {
           return Optional.of(p);
       }
       return Optional.empty();
   }
   ```
2. In the orchestrator (or `MediaStreamHandler`), replace:
   ```java
   // Before:
   PendingActionDto pending = pendingActionService.getPending(callSid);
   if (pending != null && pending.isAwaitingConfirmation() && yesNo == YES) { ... }

   // After:
   Optional<PendingActionDto> pending = pendingActionService.getIfAwaitingConfirmation(callSid);
   if (pending.isPresent() && yesNo == YES) { ... }
   ```
3. Consider making `PendingActionDto` immutable (remove `@Setter`, use `@Builder` only) so the object cannot be mutated after retrieval.

**Files to change:** `PendingActionService.java`, `MediaStreamHandler.java` (or `ConversationOrchestrator.java`), `PendingActionDto.java`

**Verification:** No race condition between checking and using pending actions.

---

### Step 3.5 — Add TTL and Size Limits to In-Memory Caches

**Problem:** Four `ConcurrentHashMap` instances grow indefinitely with no cleanup. Memory leaks over time.

**What to do:**
1. Add Caffeine cache dependency to `pom.xml`:
   ```xml
   <dependency>
       <groupId>com.github.ben-manes.caffeine</groupId>
       <artifactId>caffeine</artifactId>
   </dependency>
   ```
2. Replace `ConcurrentHashMap` in each component with Caffeine caches:

   **ConversationStore:**
   ```java
   private final Cache<String, List<ChatMessage>> conversations = Caffeine.newBuilder()
       .maximumSize(1000)
       .expireAfterAccess(2, TimeUnit.HOURS)
       .build();
   ```

   **PendingActionService:**
   ```java
   private final Cache<String, PendingActionDto> pendingByCall = Caffeine.newBuilder()
       .maximumSize(500)
       .expireAfterWrite(30, TimeUnit.MINUTES)
       .build();
   ```

   **AudioPlaybackCache:**
   ```java
   private final Cache<String, byte[]> cache = Caffeine.newBuilder()
       .maximumSize(200)
       .expireAfterWrite(5, TimeUnit.MINUTES)
       .build();
   ```

   **MediaStreamHandler streams/callFromNumbers:**
   ```java
   private final Cache<String, StreamState> streams = Caffeine.newBuilder()
       .maximumSize(500)
       .expireAfterAccess(1, TimeUnit.HOURS)
       .build();
   ```
3. Update all `.get()`, `.put()`, `.remove()` calls to use Caffeine's API (`.getIfPresent()`, `.put()`, `.invalidate()`).

**Files to change:** `pom.xml`, `ConversationStore.java`, `PendingActionService.java`, `AudioPlaybackCache.java`, `MediaStreamHandler.java`

**Verification:** Entries auto-expire. Memory stays bounded. Existing flow still works.

---

## PHASE 4 — ERROR HANDLING & RESILIENCE

> Make the system handle failures gracefully instead of silently swallowing exceptions.

### Step 4.1 — Add Timeouts to All RestTemplate Calls

**Problem:** No timeout configured on any RestTemplate. If OpenAI hangs, the thread hangs forever.

**What to do:**
1. Create a `RestTemplateConfig.java`:
   ```java
   @Configuration
   public class RestTemplateConfig {
       @Bean
       public RestTemplate restTemplate(RestTemplateBuilder builder) {
           return builder
               .setConnectTimeout(Duration.ofSeconds(5))
               .setReadTimeout(Duration.ofSeconds(30))
               .build();
       }

       @Bean("sttRestTemplate")
       public RestTemplate sttRestTemplate(RestTemplateBuilder builder) {
           return builder
               .setConnectTimeout(Duration.ofSeconds(5))
               .setReadTimeout(Duration.ofSeconds(15))
               .build();
       }

       @Bean("ttsRestTemplate")
       public RestTemplate ttsRestTemplate(RestTemplateBuilder builder) {
           return builder
               .setConnectTimeout(Duration.ofSeconds(5))
               .setReadTimeout(Duration.ofSeconds(10))
               .build();
       }
   }
   ```
2. Inject the appropriate `RestTemplate` bean into each service using `@Qualifier`.
3. Remove all manual `new RestTemplateBuilder().build()` calls from services.

**Files to change:** New `RestTemplateConfig.java`, `LlmFlowService.java`, `SttService.java`, `ElevenLabsVoiceService.java`, `TwilioService.java`

**Verification:** If an external API hangs, the call times out after the configured duration instead of blocking indefinitely.

---

### Step 4.2 — Add Retry Logic for Transient Failures

**Problem:** Every external API call is single-attempt. A momentary network blip causes complete failure.

**What to do:**
1. Add Spring Retry dependency to `pom.xml`:
   ```xml
   <dependency>
       <groupId>org.springframework.retry</groupId>
       <artifactId>spring-retry</artifactId>
   </dependency>
   <dependency>
       <groupId>org.springframework</groupId>
       <artifactId>spring-aspects</artifactId>
   </dependency>
   ```
2. Add `@EnableRetry` to the main application class.
3. Add `@Retryable` to external API call methods:

   **SttService.transcribe():**
   ```java
   @Retryable(
       retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
       maxAttempts = 2,
       backoff = @Backoff(delay = 500)
   )
   public String transcribe(byte[] audio) { ... }
   ```

   **LlmFlowService.generateReply():**
   ```java
   @Retryable(
       retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
       maxAttempts = 2,
       backoff = @Backoff(delay = 500)
   )
   ```

   **ElevenLabsVoiceService.synthesize():**
   ```java
   @Retryable(
       retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
       maxAttempts = 2,
       backoff = @Backoff(delay = 300)
   )
   ```
4. Do NOT retry on `HttpClientErrorException` (4xx errors like 401, 400) — those are permanent failures.
5. Keep retry attempts low (2 max) to avoid excessive latency in a real-time voice pipeline.

**Files to change:** `pom.xml`, main application class, `SttService.java`, `LlmFlowService.java`, `ElevenLabsVoiceService.java`

**Verification:** Transient 500 errors or connection timeouts are retried once. 401 errors fail immediately.

---

### Step 4.3 — Replace Silent Exception Swallowing with Proper Error Handling

**Problem:** All services return empty strings/arrays on failure. Callers cannot distinguish "no response" from "API failure."

**What to do:**
1. Create custom exception classes:
   ```java
   public class SttException extends RuntimeException { ... }
   public class LlmException extends RuntimeException { ... }
   public class TtsException extends RuntimeException { ... }
   public class TwilioApiException extends RuntimeException { ... }
   ```
2. Update each service to throw typed exceptions instead of returning empty values:

   **SttService:**
   ```java
   // Before:
   catch (Exception ex) {
       log.error("Failed to transcribe audio", ex);
       return "";
   }
   // After:
   catch (HttpClientErrorException.Unauthorized e) {
       throw new SttException("STT authentication failed", e);
   } catch (ResourceAccessException e) {
       throw new SttException("STT service unreachable", e);
   } catch (Exception ex) {
       throw new SttException("STT transcription failed", ex);
   }
   ```

   Apply similar changes to `LlmFlowService`, `ElevenLabsVoiceService`, `TwilioService`.

3. In `ConversationOrchestrator` (or `MediaStreamHandler`), catch these typed exceptions and provide appropriate user feedback:
   ```java
   try {
       String userText = sttService.transcribe(audio);
   } catch (SttException e) {
       log.error("STT failed for call {}", callSid, e);
       twilioService.speakResponse(callSid, "I'm having trouble hearing you. Could you say that again?", false);
       return;
   }

   try {
       response = llmFlowService.generateReply(...);
   } catch (LlmException e) {
       log.error("LLM failed for call {}", callSid, e);
       twilioService.speakResponse(callSid, "I'm having a quick technical moment. Could you repeat that?", false);
       return;
   }
   ```
4. For TTS failure, fall back to Polly (Twilio `<Say>` tag):
   ```java
   try {
       // Try ElevenLabs TTS
   } catch (TtsException e) {
       log.warn("ElevenLabs TTS failed, falling back to Polly for call {}", callSid);
       // Use Polly via TwiML <Say> tag instead
   }
   ```

**Files to change:** New exception classes, `SttService.java`, `LlmFlowService.java`, `ElevenLabsVoiceService.java`, `TwilioService.java`, `ConversationOrchestrator.java`

**Verification:** When OpenAI is unreachable, caller hears a friendly retry message instead of silence. When ElevenLabs fails, Polly fallback kicks in.

---

### Step 4.4 — Add Input Validation

**Problem:** No length limits on user text, no phone format validation, no patient name limits.

**What to do:**
1. In `ConversationOrchestrator` (or `MediaStreamHandler`), after STT returns text:
   ```java
   if (userText.length() > 2000) {
       userText = userText.substring(0, 2000);
       log.warn("User text truncated to 2000 chars for call {}", callSid);
   }
   ```
2. In `AppointmentService.bookAppointment()`:
   - Validate `patientName` length (max 100 chars, matching DB column).
   - Validate `patientPhone` format (basic pattern: digits, dashes, plus sign).
   - Validate `doctorKey` exists in database before proceeding.
   - Validate `date` parses to a valid `LocalDate`.
   - Validate `time` matches expected format.
   - Return `Optional.empty()` with a logged reason for each validation failure.
3. In `MediaStreamHandler`, validate Base64 payload length before decoding:
   ```java
   if (payload.length() > 100000) {  // ~75KB decoded
       log.warn("Oversized media payload, skipping");
       return;
   }
   ```

**Files to change:** `ConversationOrchestrator.java` (or `MediaStreamHandler.java`), `AppointmentService.java`

**Verification:** Oversized inputs are truncated. Invalid appointment data returns clear errors.

---

## PHASE 5 — DATABASE & INFRASTRUCTURE FIXES

> Fix database design issues, add health checks, and improve deployment.

### Step 5.1 — Replace ddl-auto=update with Flyway Migrations

**Problem:** `spring.jpa.hibernate.ddl-auto=update` can cause data loss and cannot be rolled back.

**What to do:**
1. Add Flyway dependency to `pom.xml`:
   ```xml
   <dependency>
       <groupId>org.flywaydb</groupId>
       <artifactId>flyway-core</artifactId>
   </dependency>
   <dependency>
       <groupId>org.flywaydb</groupId>
       <artifactId>flyway-database-postgresql</artifactId>
   </dependency>
   ```
2. Generate the current schema as the baseline migration:
   - Connect to the existing database.
   - Export the schema as SQL.
   - Save as `src/main/resources/db/migration/V1__baseline.sql`.
3. Change `application.properties`:
   ```properties
   spring.jpa.hibernate.ddl-auto=validate
   spring.flyway.baseline-on-migrate=true
   spring.flyway.baseline-version=1
   ```
4. For future schema changes, create new migration files: `V2__add_indexes.sql`, `V3__add_tenant_table.sql`, etc.

**Files to change:** `pom.xml`, `application.properties`, new `db/migration/V1__baseline.sql`

**Verification:** App starts with `ddl-auto=validate`. Flyway runs migrations. Schema matches entities.

---

### Step 5.2 — Add Missing Database Indexes

**Problem:** Frequently queried columns lack explicit indexes, causing full table scans.

**What to do:**
1. Create `src/main/resources/db/migration/V2__add_indexes.sql`:
   ```sql
   CREATE INDEX IF NOT EXISTS idx_appointment_patient_status
       ON appointment (patient_id, status, created_at DESC);

   CREATE INDEX IF NOT EXISTS idx_conversation_history_callsid
       ON conversation_history (call_sid, created_at ASC);

   CREATE INDEX IF NOT EXISTS idx_appointment_slot_lookup
       ON appointment_slot (doctor_id, slot_date, status);

   CREATE INDEX IF NOT EXISTS idx_patient_twilio_phone
       ON patient (twilio_phone);
   ```

**Files to change:** New `V2__add_indexes.sql`

**Verification:** Queries execute faster. `EXPLAIN ANALYZE` shows index scans instead of sequential scans.

---

### Step 5.3 — Fix N+1 Queries in AppointmentService

**Problem:** `getAvailableSlotsForNextWeek()` runs one query per doctor. `getActiveAppointmentSummaries()` may lazy-load Patient, Doctor, Slot per appointment.

**What to do:**
1. In `AppointmentSlotRepository`, add a single bulk query:
   ```java
   @Query("SELECT s FROM AppointmentSlot s JOIN FETCH s.doctor d " +
          "WHERE s.slotDate BETWEEN :start AND :end AND s.status = :status AND d.active = true " +
          "ORDER BY d.key, s.slotDate, s.startTime")
   List<AppointmentSlot> findAllAvailableSlots(
       @Param("start") LocalDate start,
       @Param("end") LocalDate end,
       @Param("status") AppointmentSlot.Status status);
   ```
2. In `AppointmentService.getAvailableSlotsForNextWeek()`, replace the per-doctor loop with a single call to the new query. Group results in Java:
   ```java
   List<AppointmentSlot> allSlots = slotRepository.findAllAvailableSlots(today, end, AVAILABLE);
   Map<String, Map<String, List<String>>> result = allSlots.stream()
       .collect(Collectors.groupingBy(
           s -> s.getDoctor().getKey(),
           LinkedHashMap::new,
           Collectors.groupingBy(
               s -> s.getSlotDate().toString(),
               LinkedHashMap::new,
               Collectors.mapping(AppointmentSlot::getStartTime, Collectors.toList())
           )
       ));
   ```
3. In `AppointmentRepository`, add a query with eager fetching:
   ```java
   @Query("SELECT a FROM Appointment a JOIN FETCH a.patient p JOIN FETCH a.doctor d JOIN FETCH a.slot s " +
          "WHERE p.twilioPhone = :phone AND a.status = 'CONFIRMED' ORDER BY a.createdAt DESC")
   List<Appointment> findConfirmedByPhoneWithDetails(@Param("phone") String phone);
   ```
4. Update `AppointmentService.getActiveAppointmentSummaries()` to use this query.

**Files to change:** `AppointmentSlotRepository.java`, `AppointmentRepository.java`, `AppointmentService.java`

**Verification:** `getAvailableSlotsForNextWeek()` now executes 1 query instead of N+1. Same results.

---

### Step 5.4 — Fix DataSeeder Time Format Inconsistency

**Problem:** `DataSeeder.java:74-75` has `"04.00PM"` (dots) instead of `"04:00 PM"` (colons + space).

**What to do:**
1. In `DataSeeder.java`, fix the murugan doctor's slots:
   ```java
   // Before:
   String[] muruganSlots = {"02:00 PM", "02:30 PM", "03:00 PM", "03:30 PM", "04.00PM", "04.30PM", "05.00PM", "05.30PM"};
   // After:
   String[] muruganSlots = {"02:00 PM", "02:30 PM", "03:00 PM", "03:30 PM", "04:00 PM", "04:30 PM", "05:00 PM", "05:30 PM"};
   ```

**Files to change:** `DataSeeder.java`

**Verification:** All time slots parse correctly. Dr Murugan's 4-5 PM slots are bookable.

---

### Step 5.5 — Add Spring Boot Actuator Health Checks

**Problem:** No health endpoint. Cloud Run cannot determine if the app is healthy.

**What to do:**
1. Add Actuator dependency to `pom.xml`:
   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-actuator</artifactId>
   </dependency>
   ```
2. Add to `application.properties`:
   ```properties
   management.endpoints.web.exposure.include=health,info,metrics
   management.endpoint.health.show-details=when-authorized
   management.endpoint.health.probes.enabled=true
   ```
3. Create custom health indicators for external services:
   ```java
   @Component
   public class OpenAiHealthIndicator implements HealthIndicator {
       @Override
       public Health health() {
           // Check if API key is configured
           // Optionally make a lightweight API call
           return Health.up().withDetail("model", openAiModel).build();
       }
   }
   ```
4. Update `Dockerfile` to add a health check:
   ```dockerfile
   HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
       CMD wget -q -O- http://localhost:8080/actuator/health/liveness || exit 1
   ```

**Files to change:** `pom.xml`, `application.properties`, new `OpenAiHealthIndicator.java` (optional), `Dockerfile`

**Verification:** `GET /actuator/health` returns `{"status": "UP"}`. Cloud Run health checks pass.

---

### Step 5.6 — Add Graceful Shutdown

**Problem:** In-flight calls are abruptly terminated on deployment. Active WebSocket streams are cut without warning.

**What to do:**
1. Add to `application.properties`:
   ```properties
   server.shutdown=graceful
   spring.lifecycle.timeout-per-shutdown-phase=30s
   ```
2. This gives Spring 30 seconds to finish in-flight requests before shutting down.

**Files to change:** `application.properties`

**Verification:** During redeployment, active calls complete (up to 30 seconds) before the pod is killed.

---

### Step 5.7 — Configure Database Connection Pool

**Problem:** Default HikariCP pool size (10) may be insufficient for concurrent calls.

**What to do:**
1. Add to `application.properties`:
   ```properties
   spring.datasource.hikari.maximum-pool-size=20
   spring.datasource.hikari.minimum-idle=5
   spring.datasource.hikari.connection-timeout=20000
   spring.datasource.hikari.idle-timeout=300000
   spring.datasource.hikari.max-lifetime=600000
   ```

**Files to change:** `application.properties`

**Verification:** Under 20 concurrent calls, no "connection pool exhausted" errors.

---

## PHASE 6 — LLM PROMPT SYSTEM REDESIGN

> Transform the hardcoded single-tenant prompt system into a configurable, multi-tenant-ready architecture.

### Step 6.1 — Create Tenant Entity and Configuration Schema

**Problem:** No tenant concept exists. Everything is hardcoded for a single medical practice.

**What to do:**
1. Create a Flyway migration `V3__add_tenant_table.sql`:
   ```sql
   CREATE TABLE tenant (
       id              BIGSERIAL PRIMARY KEY,
       name            VARCHAR(200) NOT NULL,
       slug            VARCHAR(100) NOT NULL UNIQUE,
       twilio_phone    VARCHAR(20) NOT NULL UNIQUE,
       timezone        VARCHAR(50) NOT NULL DEFAULT 'America/New_York',
       language        VARCHAR(10) NOT NULL DEFAULT 'en',
       active          BOOLEAN NOT NULL DEFAULT TRUE,
       created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
       updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
   );

   CREATE TABLE tenant_config (
       id              BIGSERIAL PRIMARY KEY,
       tenant_id       BIGINT NOT NULL REFERENCES tenant(id),
       config_key      VARCHAR(100) NOT NULL,
       config_value    TEXT NOT NULL,
       UNIQUE (tenant_id, config_key)
   );
   ```
2. Create the JPA entities: `Tenant.java` and `TenantConfig.java`.
3. Create `TenantRepository.java` with:
   ```java
   Optional<Tenant> findByTwilioPhone(String twilioPhone);
   Optional<Tenant> findBySlug(String slug);
   ```
4. Create `TenantService.java` that loads and caches tenant config.
5. Add a `tenant_id` column to existing tables (Doctor, AppointmentSlot, Appointment, ConversationHistory, Patient) via migration. For now, seed a default tenant and assign all existing data to it.
6. Update the `VoiceController.inboundTwiMl()` to look up the tenant by the `To` phone number from Twilio's request.
7. Pass `tenantId` through the call flow (via the `StreamState` or as a parameter).

**Files to change:** New migration, new `Tenant.java`, `TenantConfig.java`, `TenantRepository.java`, `TenantService.java`. Modify `VoiceController.java`, `MediaStreamHandler.java`.

**Verification:** Default tenant is created. Inbound call resolves to the correct tenant. All existing data still accessible.

---

### Step 6.2 — Create Prompt Template Entity and Storage

**Problem:** The AI persona, personality, business rules, greeting, and conversation instructions are all hardcoded in Java strings inside `LlmFlowService.buildContext()`.

**What to do:**
1. Create a migration `V4__add_prompt_templates.sql`:
   ```sql
   CREATE TABLE prompt_template (
       id              BIGSERIAL PRIMARY KEY,
       tenant_id       BIGINT NOT NULL REFERENCES tenant(id),
       template_key    VARCHAR(50) NOT NULL,
       template_text   TEXT NOT NULL,
       active          BOOLEAN NOT NULL DEFAULT TRUE,
       version         INT NOT NULL DEFAULT 1,
       created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
       updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
       UNIQUE (tenant_id, template_key, version)
   );
   ```
2. Define standard template keys:
   - `system_persona` — Who is the AI? Name, role, personality traits, tone.
   - `system_rules` — Conversation rules (confirm before action, never invent data, etc.).
   - `system_flows` — Business-specific flows (book, cancel, reschedule, FAQ handling).
   - `greeting` — The initial greeting message spoken when a call starts.
   - `farewell` — The goodbye message.
   - `error_fallback` — What to say when something goes wrong.
   - `unclear_input` — What to say when speech is unclear.
3. Create `PromptTemplate.java` entity and `PromptTemplateRepository.java`.
4. Create `PromptService.java` that:
   - Loads templates by tenant and key.
   - Caches templates (Caffeine cache, 10-minute TTL).
   - Provides `getTemplate(tenantId, templateKey)`.
5. Seed default templates based on the current hardcoded values in `LlmFlowService.buildContext()` and `ResponsePhrases.java`.

**Files to change:** New migration, new `PromptTemplate.java`, `PromptTemplateRepository.java`, `PromptService.java`. Seed data.

**Verification:** Templates are stored in DB. `PromptService.getTemplate()` returns the correct template for a tenant.

---

### Step 6.3 — Refactor LlmFlowService to Use Template-Driven Prompts

**Problem:** `LlmFlowService.buildContext()` and `buildOutputFormatInstructions()` contain ~60 lines of hardcoded prompt text.

**What to do:**
1. Refactor `LlmFlowService.buildContext()` to use `PromptService`:
   ```java
   private String buildContext(long tenantId, String fromNumber) {
       StringBuilder ctx = new StringBuilder();

       // Dynamic date
       ctx.append("TODAY'S DATE: ").append(LocalDate.now()).append("\n\n");

       // Tenant-specific persona (from DB)
       String persona = promptService.getTemplate(tenantId, "system_persona");
       ctx.append(persona).append("\n\n");

       // Dynamic data (doctors, slots, appointments) — still fetched from DB
       ctx.append("DATABASE STATE:\n");
       appendDoctors(ctx, tenantId);
       appendAvailableSlots(ctx, tenantId);
       appendCallerAppointments(ctx, fromNumber);

       // Tenant-specific rules (from DB)
       String rules = promptService.getTemplate(tenantId, "system_rules");
       ctx.append("\n").append(rules).append("\n");

       // Tenant-specific flows (from DB)
       String flows = promptService.getTemplate(tenantId, "system_flows");
       ctx.append("\n").append(flows).append("\n");

       return ctx.toString();
   }
   ```
2. The `buildOutputFormatInstructions()` method should remain as code (not in DB) since it defines the JSON contract between the LLM and the backend. But make the action types configurable per tenant (e.g., some tenants may not support RESCHEDULE).
3. Update `generateReply()` to accept `tenantId` as a parameter.
4. Update `ResponsePhrases.java` to load from `PromptService` instead of returning hardcoded strings:
   ```java
   public String greeting(long tenantId) {
       return promptService.getTemplate(tenantId, "greeting");
   }

   public String errorFallback(long tenantId) {
       return promptService.getTemplate(tenantId, "error_fallback");
   }
   ```
5. Update `VoiceController` to pass `tenantId` when generating the greeting.

**Files to change:** `LlmFlowService.java`, `ResponsePhrases.java`, `VoiceController.java`

**Verification:** Change a template in the database → the AI's behavior changes on the next call without code redeployment. Default tenant behaves exactly as before.

---

### Step 6.4 — Support Template Variables / Placeholders

**Problem:** Templates need dynamic data injection (business name, doctor names, supported actions).

**What to do:**
1. Define placeholder syntax in templates. Use `{{variable_name}}`:
   ```
   Hi! Thanks for calling {{business_name}}. I'm {{ai_name}}, your virtual assistant.
   How can I help you today? I can help you {{supported_actions}}.
   ```
2. In `PromptService`, add a `renderTemplate(tenantId, templateKey, Map<String, String> variables)` method:
   ```java
   public String renderTemplate(long tenantId, String templateKey, Map<String, String> vars) {
       String template = getTemplate(tenantId, templateKey);
       for (Map.Entry<String, String> entry : vars.entrySet()) {
           template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
       }
       return template;
   }
   ```
3. Standard variables available per tenant:
   - `{{business_name}}` — from `tenant.name`
   - `{{ai_name}}` — from `tenant_config` (e.g., "Sarah", "Alex")
   - `{{supported_actions}}` — from `tenant_config` (e.g., "book, reschedule, or cancel appointments")
   - `{{business_hours}}` — from `tenant_config`
   - `{{business_address}}` — from `tenant_config`
   - `{{doctor_list}}` — dynamically generated from DB
   - `{{available_slots}}` — dynamically generated from DB
4. Build the variable map in `LlmFlowService.buildContext()` before rendering.

**Files to change:** `PromptService.java`, `LlmFlowService.java`, `TenantService.java`

**Verification:** Template with `{{business_name}}` renders correctly with the tenant's name.

---

### Step 6.5 — Make Farewell Detection Configurable

**Problem:** `MediaStreamHandler.isFarewellResponse()` has hardcoded farewell phrases. Different businesses may need different phrases.

**What to do:**
1. Move farewell detection into `ConversationOrchestrator`.
2. Load farewell phrases from the tenant's `farewell` template or a `farewell_phrases` config key.
3. Instead of hardcoded string matching, check against the configurable list:
   ```java
   List<String> farewellPhrases = tenantService.getFarewellPhrases(tenantId);
   boolean isFarewell = farewellPhrases.stream()
       .anyMatch(phrase -> lowerText.contains(phrase));
   ```
4. Alternatively, ask the LLM to include a `"farewell": true` flag in its JSON response when it detects the conversation is ending. This is more robust than string matching.

**Files to change:** `ConversationOrchestrator.java`, `TenantService.java`

**Verification:** Different tenants can have different farewell triggers. Call ends appropriately.

---

### Step 6.6 — Make Greeting Configurable via TTS

**Problem:** `VoiceController.inboundTwiMl()` plays a hardcoded greeting from `ResponsePhrases.greeting()`.

**What to do:**
1. In `VoiceController.inboundTwiMl()`:
   - Look up `tenantId` from the `To` phone number.
   - Get the greeting from `ResponsePhrases.greeting(tenantId)` (which now reads from DB).
   - Generate TwiML with tenant-specific greeting.
2. The greeting should support the same `{{variable}}` placeholders.

**Files to change:** `VoiceController.java`

**Verification:** Two different tenants on two different phone numbers hear different greetings.

---

### Step 6.7 — Limit Conversation History Sent to LLM

**Problem:** Full conversation history is sent to OpenAI on every turn. Long calls generate 30,000+ tokens per request.

**What to do:**
1. In `LlmFlowService.generateReply()`, limit the history:
   ```java
   // Keep last N messages (configurable via ConversationProperties)
   int maxMessages = conversationProperties.getMaxLlmHistoryMessages(); // default: 20
   List<ChatMessage> recentHistory = history.size() > maxMessages
       ? history.subList(history.size() - maxMessages, history.size())
       : history;
   ```
2. Optionally, for conversations longer than `maxMessages`, prepend a summary of the earlier conversation:
   - At every 20 messages, call the LLM to summarize the first 15 messages into 2-3 sentences.
   - Store the summary.
   - Send: [summary] + [last 15 messages] instead of all messages.
3. Add `conversation.max-llm-history-messages=20` to `ConversationProperties`.

**Files to change:** `LlmFlowService.java`, `ConversationProperties.java`

**Verification:** Token usage per call stays bounded. Long conversations still have context.

---

## PHASE 7 — FUNCTIONAL GAP IMPLEMENTATION

> Add missing features identified in the functional review (excluding CRM integration).

### Step 7.1 — Implement Call Session Tracking

**Problem:** `TwilioCall` and `TwilioCallMaster` entities exist but are never used. No call outcomes, duration, or status are tracked.

**What to do:**
1. Decide whether to use the existing `TwilioCall`/`TwilioCallMaster` entities or create a new clean `CallSession` entity. Recommend creating a clean one:
   ```java
   @Entity
   @Table(name = "call_session")
   public class CallSession {
       @Id @GeneratedValue
       private Long id;
       private Long tenantId;
       private String twilioCallSid;
       @Enumerated(EnumType.STRING)
       private Direction direction;    // INBOUND, OUTBOUND
       private String fromNumber;
       private String toNumber;
       @Enumerated(EnumType.STRING)
       private Status status;          // RINGING, IN_PROGRESS, COMPLETED, FAILED
       @Enumerated(EnumType.STRING)
       private Outcome outcome;        // BOOKED, RESCHEDULED, CANCELLED, INFO_ONLY, FAILED, ABANDONED
       private Instant startedAt;
       private Instant endedAt;
       private Integer durationSeconds;
       @Column(columnDefinition = "TEXT")
       private String summary;
       @Column(columnDefinition = "JSONB")
       private String metadata;

       enum Direction { INBOUND, OUTBOUND }
       enum Status { RINGING, IN_PROGRESS, COMPLETED, FAILED }
       enum Outcome { BOOKED, RESCHEDULED, CANCELLED, INFO_ONLY, FAILED, ABANDONED }
   }
   ```
2. Create `CallSessionRepository` and `CallSessionService`.
3. In `VoiceController.inboundTwiMl()`:
   - Create a `CallSession` record with `status=RINGING`, `startedAt=now()`.
4. In `ConversationOrchestrator`:
   - Update `status=IN_PROGRESS` on first utterance.
   - Track `outcome` based on what actions are executed (BOOKED, CANCELLED, etc.).
5. When the call ends (farewell detected or stream stops):
   - Set `status=COMPLETED`, `endedAt=now()`, calculate `durationSeconds`.
   - Generate a summary using the LLM (see Step 7.2).
6. If the existing `TwilioCall`/`TwilioCallMaster` entities are confirmed unused, delete them.

**Files to change:** New `CallSession.java`, `CallSessionRepository.java`, `CallSessionService.java`, Flyway migration. Modify `VoiceController.java`, `ConversationOrchestrator.java`, `MediaStreamHandler.java`.

**Verification:** After each call, a `call_session` record exists with correct status, outcome, and duration.

---

### Step 7.2 — Implement Call Summary Generation

**Problem:** No end-of-call summary is generated. Conversation messages are stored individually but never aggregated.

**What to do:**
1. In `ConversationOrchestrator`, when a call ends (farewell detected or stream closed):
   ```java
   private String generateCallSummary(String callSid, long tenantId) {
       List<ChatMessage> history = conversationStore.getHistory(callSid);
       String transcript = history.stream()
           .map(m -> m.getRole() + ": " + m.getContent())
           .collect(Collectors.joining("\n"));

       String summaryPrompt = "Summarize this phone call in 2-3 sentences. Include: " +
           "caller name (if known), intent, key details (date/time/doctor), " +
           "outcome (booked/cancelled/rescheduled/info only), and any follow-up needed.\n\n" +
           transcript;

       // Call LLM with a simple summarization request
       return llmFlowService.generateSummary(summaryPrompt);
   }
   ```
2. Add a `generateSummary(String prompt)` method to `LlmFlowService` that makes a simple LLM call without the full context/action system.
3. Store the summary in `CallSession.summary`.
4. This should run asynchronously after the call ends (don't block the call).

**Files to change:** `ConversationOrchestrator.java`, `LlmFlowService.java`, `CallSessionService.java`

**Verification:** After each completed call, the `call_session.summary` field contains a concise, accurate summary.

---

### Step 7.3 — Implement Twilio Status Callback Handling

**Problem:** Twilio sends call status events (ringing, in-progress, completed, failed) but the app doesn't process them.

**What to do:**
1. In `VoiceController`, add a status callback endpoint:
   ```java
   @PostMapping("/twilio/voice/status")
   public ResponseEntity<Void> twilioStatusCallback(@RequestParam Map<String, String> params) {
       String callSid = params.get("CallSid");
       String callStatus = params.get("CallStatus");
       String duration = params.get("CallDuration");
       callSessionService.updateStatus(callSid, callStatus, duration);
       return ResponseEntity.ok().build();
   }
   ```
2. In `CallSessionService.updateStatus()`:
   - Map Twilio statuses to internal statuses (e.g., "completed" → COMPLETED).
   - Update `CallSession.endedAt` and `durationSeconds` when completed.
   - Trigger summary generation on "completed" status.
3. Ensure the Twilio phone number is configured with this status callback URL.

**Files to change:** `VoiceController.java`, `CallSessionService.java`

**Verification:** Call session records show accurate status transitions and duration from Twilio.

---

### Step 7.4 — Implement Slot Auto-Regeneration

**Problem:** `DataSeeder` creates slots for 30 days from startup. After 30 days, all slots expire and the system becomes non-functional.

**What to do:**
1. Create a `@Scheduled` job in a `SlotMaintenanceService`:
   ```java
   @Service
   public class SlotMaintenanceService {
       @Scheduled(cron = "0 0 2 * * *")  // Run at 2 AM daily
       public void regenerateSlots() {
           LocalDate today = LocalDate.now();
           LocalDate endDate = today.plusDays(30);

           for (Doctor doctor : doctorRepository.findByActiveTrue()) {
               // Find the last date that has slots for this doctor
               LocalDate lastSlotDate = slotRepository.findMaxSlotDateByDoctor(doctor.getId());
               LocalDate startDate = (lastSlotDate != null) ? lastSlotDate.plusDays(1) : today;

               // Generate slots from startDate to endDate
               for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                   createSlotsForDoctorOnDate(doctor, date);
               }
           }
       }
   }
   ```
2. Add `@EnableScheduling` to the main application class.
3. Optionally, clean up past slots older than 30 days to keep the table clean.
4. The slot generation logic should use the doctor's `scheduleStart` and `scheduleEnd` to determine slot times (currently in DataSeeder but hardcoded per doctor).

**Files to change:** New `SlotMaintenanceService.java`, main application class (add `@EnableScheduling`)

**Verification:** Slots are always available for the next 30 days. Running the job manually creates new slots.

---

### Step 7.5 — Add Basic Monitoring Endpoint

**Problem:** No way to see call volume, success rates, or system health beyond logs.

**What to do:**
1. Create `MetricsController.java`:
   ```java
   @RestController
   @RequestMapping("/api/metrics")
   public class MetricsController {
       @GetMapping("/calls")
       public Map<String, Object> callMetrics(
               @RequestParam(required = false) Long tenantId,
               @RequestParam(defaultValue = "7") int days) {
           LocalDate since = LocalDate.now().minusDays(days);
           return Map.of(
               "totalCalls", callSessionService.countSince(since, tenantId),
               "byOutcome", callSessionService.countByOutcome(since, tenantId),
               "avgDurationSeconds", callSessionService.avgDuration(since, tenantId),
               "failedCalls", callSessionService.countFailed(since, tenantId)
           );
       }
   }
   ```
2. Add the necessary query methods to `CallSessionRepository` and `CallSessionService`.
3. This endpoint should be protected (basic auth or API key) — even a simple check is fine for POC:
   ```java
   @RequestParam String apiKey
   // Validate against a configured key
   ```

**Files to change:** New `MetricsController.java`, `CallSessionService.java`, `CallSessionRepository.java`

**Verification:** `GET /api/metrics/calls?days=7` returns call statistics.

---

### Step 7.6 — Implement Outbound Call Infrastructure

**Problem:** No outbound calling capability exists. The platform can only receive calls.

**What to do:**
1. Create `OutboundCallService.java`:
   ```java
   @Service
   public class OutboundCallService {
       public String initiateCall(long tenantId, String toNumber, Map<String, String> context) {
           Tenant tenant = tenantService.getById(tenantId);
           // Use Twilio REST API to create a call
           // from: tenant.getTwilioPhone()
           // to: toNumber
           // url: baseUrl + "/twilio/voice/outbound-start?tenantId=" + tenantId
           // statusCallback: baseUrl + "/twilio/voice/status"
           // Return the Twilio CallSid
       }
   }
   ```
2. Add an outbound webhook endpoint in `VoiceController`:
   ```java
   @PostMapping(value = "/twilio/voice/outbound-start", produces = MediaType.APPLICATION_XML_VALUE)
   public ResponseEntity<String> outboundStart(@RequestParam Map<String, String> params) {
       // Similar to inbound, but:
       // - Use outbound-specific greeting template
       // - Pass appointment context to the stream
       return outboundTwiMl(params);
   }
   ```
3. Create an API endpoint for manually triggering outbound calls (for testing and scheduler use):
   ```java
   @PostMapping("/api/outbound/call")
   public ResponseEntity<Map<String, String>> triggerOutboundCall(
           @RequestBody OutboundCallRequest request) {
       String callSid = outboundCallService.initiateCall(
           request.getTenantId(), request.getToNumber(), request.getContext());
       return ResponseEntity.ok(Map.of("callSid", callSid));
   }
   ```
4. In the LLM prompt for outbound calls, include the appointment context so the AI knows why it's calling:
   ```
   You are calling {{patient_name}} to remind them about their appointment
   with {{doctor_name}} on {{date}} at {{time}}. Confirm if they can make it,
   or offer to reschedule.
   ```

**Files to change:** New `OutboundCallService.java`, `VoiceController.java` (add outbound endpoint), new `OutboundCallRequest.java` DTO

**Verification:** `POST /api/outbound/call` initiates a call. The recipient hears the AI introduce the reminder. The call flow works end-to-end.

---

### Step 7.7 — Implement Appointment Reminder Scheduler

**Problem:** No automated reminder system. Outbound calls must be triggered manually.

**What to do:**
1. Create `ReminderSchedulerService.java`:
   ```java
   @Service
   public class ReminderSchedulerService {
       @Scheduled(fixedDelayString = "${reminder.check-interval-ms:600000}")  // Every 10 min
       public void checkAndSendReminders() {
           LocalDate tomorrow = LocalDate.now().plusDays(1);

           // Find all confirmed appointments for tomorrow
           // that haven't been reminded yet
           List<Appointment> upcoming = appointmentService
               .getUnremindedAppointmentsForDate(tomorrow);

           for (Appointment appt : upcoming) {
               try {
                   outboundCallService.initiateCall(
                       appt.getTenantId(),
                       appt.getPatient().getTwilioPhone(),
                       Map.of(
                           "appointmentId", appt.getId().toString(),
                           "doctorName", appt.getDoctor().getName(),
                           "date", appt.getSlot().getSlotDate().toString(),
                           "time", appt.getSlot().getStartTime(),
                           "patientName", appt.getPatient().getName()
                       )
                   );
                   // Mark as reminded
                   appointmentService.markReminded(appt.getId());
               } catch (Exception e) {
                   log.error("Failed to send reminder for appointment {}", appt.getId(), e);
               }
           }
       }
   }
   ```
2. Add a `reminded` boolean column to the `Appointment` entity (via Flyway migration).
3. Add a configurable reminder window: `reminder.hours-before=24` in `application.properties`.
4. Add `getUnremindedAppointmentsForDate()` to `AppointmentService`.

**Files to change:** New `ReminderSchedulerService.java`, `AppointmentService.java`, `AppointmentRepository.java`, Flyway migration for `reminded` column, `application.properties`

**Verification:** Appointments for tomorrow get reminder calls. Each appointment is only reminded once.

---

## PHASE 8 — TESTING

> Add test coverage for critical paths.

### Step 8.1 — Add Unit Tests for ConversationOrchestrator

**What to test:**
- STT failure → caller hears retry message.
- LLM failure → caller hears fallback message.
- Pending action + YES → ConfirmationExecutionService called.
- Pending action + NO → pending cleared, caller hears "No problem."
- LLM returns action → PendingActionService stores it.
- Farewell detected → call ends.

**Approach:** Mock all dependencies (SttService, LlmFlowService, PendingActionService, etc.). Test the orchestration logic in isolation.

**Files to create:** `ConversationOrchestratorTest.java`

---

### Step 8.2 — Add Unit Tests for LlmFlowService Response Parsing

**What to test:**
- Valid JSON with message + action → both parsed.
- Valid JSON with message + null action → message only.
- Malformed JSON → fallback message, no action.
- Markdown-wrapped JSON → unwrapped and parsed.
- Trailing JSON after text → text extracted as message, JSON as action.
- Empty response → default fallback message.

**Files to create:** `LlmFlowServiceTest.java`

---

### Step 8.3 — Add Unit Tests for AppointmentService

**What to test:**
- Book appointment with valid data → appointment created, slot marked BOOKED.
- Book appointment with already-booked slot → returns empty.
- Cancel appointment → slot marked AVAILABLE, appointment CANCELLED.
- Reschedule → old slot freed, new slot booked, appointment updated.
- Get available slots → only AVAILABLE status returned.
- Get upcoming appointments → only future CONFIRMED appointments.

**Files to create:** `AppointmentServiceTest.java` (expand existing)

---

### Step 8.4 — Add Integration Test for End-to-End Call Flow

**What to test:** Simulate an inbound call flow without Twilio:
1. Call `ConversationOrchestrator.processUtterance()` with mock audio.
2. Mock `SttService` to return pre-defined user text.
3. Verify LLM is called with correct context.
4. Mock LLM response with booking action.
5. Simulate user saying "yes".
6. Verify appointment is created in database.
7. Verify call session is created and tracked.

**Approach:** Use `@SpringBootTest` with `@MockBean` for external services (OpenAI, ElevenLabs, Twilio).

**Files to create:** `CallFlowIntegrationTest.java`

---

### Step 8.5 — Add Tests for PromptService and Templates

**What to test:**
- Template loaded correctly for a tenant.
- Template variables (`{{business_name}}`) replaced correctly.
- Missing template returns sensible default.
- Template caching works (second call doesn't hit DB).

**Files to create:** `PromptServiceTest.java`

---

## APPENDIX — Change Dependency Graph

Below is the recommended order, showing which steps must be completed before others:

```
PHASE 1 (Security) — No dependencies, do first
  1.1 → 1.2 (rotate keys after removing from code)
  1.3, 1.4, 1.5 — Independent of each other

PHASE 2 (Architecture Cleanup)
  2.1 → 2.2 (remove BookingFlowService before LlmService)
  2.3 — After 2.1 (since BookingFlowService had duplicate code)
  2.4 — After 2.1, 2.2 (clean up before extracting orchestrator)
  2.5, 2.6, 2.7, 2.8 — Independent of each other

PHASE 3 (Concurrency)
  3.1, 3.2 — Independent, can be done in any order
  3.3 — After 2.4 (executor used by orchestrator)
  3.4 — Independent
  3.5 — After 3.4 (Caffeine replaces ConcurrentHashMap)

PHASE 4 (Error Handling)
  4.1 — After 2.5 (RestTemplate config depends on centralized beans)
  4.2 — After 4.1
  4.3 — After 2.4 (orchestrator handles errors)
  4.4 — Independent

PHASE 5 (Database & Infrastructure)
  5.1 — Before any new migrations
  5.2 — After 5.1
  5.3 — Independent
  5.4 — Independent
  5.5, 5.6, 5.7 — Independent

PHASE 6 (LLM Prompts)
  6.1 → 6.2 → 6.3 → 6.4 (sequential — each builds on previous)
  6.5, 6.6 — After 6.3
  6.7 — Independent

PHASE 7 (Functional Gaps)
  7.1 → 7.2 → 7.3 (call session → summary → status callback)
  7.4 — Independent
  7.5 — After 7.1 (metrics query call sessions)
  7.6 → 7.7 (outbound calls → reminder scheduler)

PHASE 8 (Testing)
  8.1 — After 2.4 (tests the orchestrator)
  8.2 — After 2.2 (tests the active LLM service)
  8.3 — Independent
  8.4 — After 7.1 (tests call session tracking)
  8.5 — After 6.2 (tests prompt templates)
```

---

## SUMMARY

| Phase | Steps | Focus |
|-------|-------|-------|
| **Phase 1** | 1.1–1.5 | Critical security fixes |
| **Phase 2** | 2.1–2.8 | Remove dead code, consolidate duplicates |
| **Phase 3** | 3.1–3.5 | Fix concurrency bugs and memory leaks |
| **Phase 4** | 4.1–4.4 | Proper error handling and resilience |
| **Phase 5** | 5.1–5.7 | Database, health checks, deployment |
| **Phase 6** | 6.1–6.7 | Multi-tenant LLM prompt system |
| **Phase 7** | 7.1–7.7 | Missing functional features |
| **Phase 8** | 8.1–8.5 | Test coverage |

**Total steps:** 36 individually reviewable changes.
