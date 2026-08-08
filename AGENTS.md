<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **android-backup-gui** (2510 symbols, 4881 relationships, 175 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/android-backup-gui/context` | Codebase overview, check index freshness |
| `gitnexus://repo/android-backup-gui/clusters` | All functional areas |
| `gitnexus://repo/android-backup-gui/processes` | All execution flows |
| `gitnexus://repo/android-backup-gui/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

## Cursor Cloud specific instructions

Single-module Android Gradle project. Standard commands live in `CLAUDE.md` / `README.md`
(`./gradlew :app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`) — use those; don't duplicate them here.

- **JDK 17 is mandatory.** The VM's default JDK is 21, but Gradle 8.2 / AGP 8.2.0 will not run on it.
  JDK 17 is pinned for Gradle via `~/.gradle/gradle.properties` (`org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64`),
  so `./gradlew` uses it regardless of the shell's `JAVA_HOME`. Do not remove that pin.
- **Android SDK** lives at `$HOME/android-sdk` (platform-34, build-tools 34.0.0). `local.properties`
  (`sdk.dir=...`) is gitignored and is regenerated on session startup by the update script — no manual step needed.
- **The app cannot be launched in this VM.** It requires a rooted physical **arm64** device: native libs
  (`librestic.so`, `libtar_bin.so`, `libzstd_bin.so`) are `arm64-v8a` only and the app needs root (`pm`, `dumpsys`, restic).
  The realistic dev loop here is headless **build + lint + unit tests**, which mirrors CI (`.github/workflows/android.yml`).
  To verify a build, inspect the produced APK with
  `$HOME/android-sdk/build-tools/34.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk`.
- **Release builds** (`:app:assembleRelease`) require `app/release.keystore` plus non-empty `KEYSTORE_PASSWORD`
  and `KEY_PASSWORD`; these are not configured in this environment and are not needed for debug/dev work.
