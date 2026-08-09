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
- **Android SDK** lives at `$HOME/android-sdk` (platform-34, build-tools 34.0.0, platform-tools, emulator).
  `local.properties` (`sdk.dir=...`) is gitignored and is regenerated on session startup by the update script.
- **Default day-to-day loop is headless build + lint + unit tests** (mirrors `.github/workflows/android.yml`).
  Inspect a debug APK with
  `$HOME/android-sdk/build-tools/34.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk`.
- **Release builds** (`:app:assembleRelease`) require `app/release.keystore` plus non-empty `KEYSTORE_PASSWORD`
  and `KEY_PASSWORD`; not configured here and not needed for debug/dev work.

### ARM64 guest on this x86_64 cloud VM (experimental)

Verified: x86 host can run an **ARM64 Android guest via QEMU TCG**, but not with the stock modern Emulator.

| Path | Result |
|------|--------|
| Emulator **37.x** + `system-images;android-*;*;arm64-v8a` | **Blocked**: `Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64 host` |
| Emulator **31.3.13** (build `9189900`) + API **27** `google_apis;arm64-v8a` | **Works** after a one-byte-length patch (see below) |
| Full APK install / Compose UI | Unreliable: guest often never sets `sys.boot_completed`; `pm install` hangs for long periods on TCG |
| Native engine smoke test | **Works**: push `librestic.so` and run `version` → `restic … on linux/arm64` |
| Root | Engineering `su` present (`su 0 id`); this is **not** Magisk/KernelSU — libsu-based app flows may still fail |

**How to boot the experimental ARM64 AVD** (already created as `arm64_api27` when present):

1. Keep Emulator 37 at `$HOME/android-sdk/emulator` (default for x86 images / sdkmanager).
2. Keep patched 31.3.13 at `$HOME/android-sdk/emulator-31.3.13-arm64tcg`.
3. Download 31.3.13 if missing:
   `curl -fL -o /tmp/emu31.zip https://dl.google.com/android/repository/emulator-linux_x64-9189900.zip`
4. **Required binary patch** on `qemu/linux-x86_64/qemu-system-aarch64-headless` (and the non-`-headless` twin):
   replace the android argv token after `virtio-wifi\0` from `-soundhw\0` → `-pidfile\0`
   (same length). Unpatched 31.3.13 dies with `PCI bus not available for hda` on ranchu ARM.
5. Launch with 31.3.13 on `PATH` first, plus:
   `emulator -avd arm64_api27 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -accel off -no-snapshot`
6. Minimal guest check after `adb` shows `device`:
   `adb push app/.../lib/arm64-v8a/librestic.so /data/local/tmp/ && adb shell chmod 755 … && adb shell /data/local/tmp/librestic.so version`

Do **not** treat ARM64-TCG as the primary CI path. Prefer unit tests + rooted physical arm64 for real backup/restore E2E.
`/dev/kvm` may exist on the VM but the `ubuntu` user is often not in the kvm group; ARM64-on-x86 cannot use KVM anyway.
