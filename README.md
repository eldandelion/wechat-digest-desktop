# WeChat Digest Desktop

A native macOS Compose Multiplatform desktop application that turns noisy WeChat group chats into a clean, structured, searchable digest of announcements, deadlines, and events — powered by built-in local GGUF models (`llama-server`), local Ollama, or OpenAI-compatible LLMs in strict read-only mode.

## Highlights

- **Zero Ban Risk & Local-First Privacy**: Reads local WeChat SQLite shards (`contact.db`, `message_*.db`) in immutable read-only mode with transparent WCDB AES-CBC page decryption.
- **3-Pass AI Extraction & Language Gate**:
  1. **Static Prefiltering & Sender Allowlist**: Strips emoji acknowledgments, stickers, XML cards, and unauthorized senders before touching the LLM.
  2. **Pass 1 Semantic Triage & Consolidation**: Groups multi-message announcements into coherent single-topic chunks.
  3. **Pass 2 Rule & Substance Gatekeeper**: Enforces per-group custom rules, persona relevance, and cross-sync room memory deduplication.
  4. **Pass 3 Grounded Card Synthesis & Target-Language Repair**: Extracts structured sections, priorities (`Critical`, `Important`, `Normal`, `Low`), categories (`Exam`, `Deadline`, `Notice`, `Activity`, `Promotion`, `Other`), and dates, automatically translating non-compliant fields via `CardTranslator`.
- **Master-Detail Split-View UI**:
  - Resizable split view with a compact segmented event list on the left (`16dp` outer / `4dp` inner corner radii, unread dot indicator, read text dimming, inline star buttons, and a scroll-collapsing Sync FAB).
  - Full event detail view on the right with an adaptive top action toolbar (Add to Calendar `.ics` export, on-demand source message translation, mark read/unread, delete) and bottom-right Previous/Next notice navigation.
  - Persistent **Starred** category filter alongside **Priority**, **Group**, and **Date** (`Newest to oldest` / `Oldest to newest`) dropdown filters.

## Installation (macOS)

Download the latest **`WeChatDigest-1.1.0.dmg`** from the [GitHub Releases](../../releases) page, open the disk image, and drag **WeChatDigest.app** into your `/Applications` folder.

## Building & Running from Source

### Prerequisites
- macOS (Apple Silicon or Intel)
- JDK 21
- Optional: Built-in local GGUF model manager (downloads curated models & `llama-server` on first use), [Ollama](https://ollama.com) running locally (`http://localhost:11434`), or an OpenAI-compatible API endpoint

### Run in Development
```bash
./gradlew run
```

### Package macOS `.dmg` Installer
```bash
./gradlew packageDmg
```
The signed/packaged `.dmg` will be generated at `build/compose/binaries/main/dmg/WeChatDigest-1.1.0.dmg`.

## Testing & AI Pipeline Evaluation Testbed

Run the unit test suite, offline SHA-256 VCR cassette regression suite (`12` golden scenarios), and JaCoCo coverage report:

```bash
./gradlew test evalTest jacocoTestReport check
```

See [docs/TESTBED.md](docs/TESTBED.md) for running live model evaluations (`./gradlew liveEval`), recording cassettes (`./gradlew recordCassettes`), and replaying snapshots read-only.
