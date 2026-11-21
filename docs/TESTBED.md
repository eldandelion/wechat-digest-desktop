# AI Processing Engine Evaluation & Regression Testbed

The `eval` testbed isolates the entire extraction and translation pipeline (`MessageSanitizer` -> `EventExtractor` Pass 1 Triage -> `Pass2Gatekeeper` -> Pass 3 Synthesis -> `LanguageHelper` + `CardTranslator` -> Concurrency) from the Compose UI and your live `~/Library/Application Support/WeChatDigest/app.db` database.

## 1. Offline Deterministic CI Gate (`evalTest`)

Runs 12 golden regression scenarios (`src/eval/resources/scenarios/REG-01..12*.json`) against content-addressed SHA-256 VCR cassettes (`src/eval/resources/cassettes/*.cassette.json`) using `ReplayLlmClient` (zero network access, fail-closed on prompt drift):

```bash
./gradlew test evalTest --console=plain
```

`evalTest` is wired into `./gradlew check` and emits reports after every run:
- `build/eval-reports/latest-eval-report.json`
- `build/eval-reports/latest-eval-report.md`

## 2. Live Model Validation (`liveEval`)

Run prompt, parser, or model changes against a real local Ollama or cloud OpenAI-compatible endpoint before releasing:

```bash
# Local Ollama (auto-discovers loaded model or specify -Peval.model=...)
./gradlew liveEval -Peval.provider=OLLAMA -Peval.endpoint=http://localhost:11434

# Filter to a single scenario with 3 trials (pass@1, pass@k, pass^k)
./gradlew liveEval -Peval.scenario=REG-07 -Peval.runs=3

# Cloud OpenAI-compatible endpoint (read API key from env, never commit secrets)
EVAL_API_KEY="sk-..." ./gradlew liveEval \
  -Peval.provider=OPENAI_COMPATIBLE \
  -Peval.endpoint=https://api.openai.com/v1 \
  -Peval.model=gpt-4o-mini \
  -Peval.runs=3
```

Exit status is non-zero if any `REGRESSION` scenario fails `pass^k = 1.0`.

## 3. Refreshing VCR Cassettes (`recordCassettes`)

Whenever `ExtractionPrompts` changes intentionally, re-record cassettes against a live endpoint and inspect the diff before committing:

```bash
./gradlew recordCassettes -Peval.provider=OLLAMA -Peval.model=qwen2.5:14b
# Or refresh a single cassette:
./gradlew recordCassettes -Peval.scenario=REG-04
```

## 4. Safe Read-Only Snapshot Replay (`replaySnapshot`)

Test real chat rooms strictly in read-only mode (never mutates `app.db`) or export anonymized scenario skeletons:

```bash
# Dry-run extraction against a decrypted WeChat DB copy in read-only SQLite mode
./gradlew replaySnapshot \
  -Peval.wechatDir=/path/to/decrypted_wechat_copy \
  -Peval.room=123456@chatroom \
  -Peval.limit=30 \
  -Peval.provider=OLLAMA

# Export anonymized scenario skeleton (scrubs emails, student/phone numbers, wxids)
./gradlew replaySnapshot \
  -Peval.wechatDir=/path/to/decrypted_wechat_copy \
  -Peval.room=123456@chatroom \
  -Peval.exportOut=src/eval/resources/scenarios/CAP-01_anonymized_room.json
```

## Scenario Expectations & Graders

Graders in `src/eval/kotlin/com/wechatdigest/eval/EvalGraders.kt` deterministically check:
1. Execution contract & schema completeness (`title`, `summary`, `sourceRoomId`, min sections).
2. Grounding & anti-hallucination (`sourceMessages`, no `[id:]` tag leaks, no placeholder phrases, no unintended `' / '` topic merging).
3. Fact recall (`mustContainFacts`, `forbiddenTitleSubstrings`, `forbiddenContentSubstrings`, `forbiddenSourceSubstrings`).
4. Target-language compliance (`LanguageHelper.checkCardCompliance` across all user-visible fields).
5. Resource & drift budgets (`afterPrefilterCount`, `maxLlmCalls`, `translationRepairCallCount`, `jsonRepairCount`, `baseline.json`).
