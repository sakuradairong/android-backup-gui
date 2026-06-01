# Long-Term Memory

## Overview
- **Android Backup GUI**: Kotlin/Android project at `/root/github_projects/android-backup-gui`. Complete refactoring done: replaced external scripts with native Kotlin root execution (persistent su session). APK builds successful (debug 9.7MB, release 8.2MB). Custom persistent su process replaced with libsu (`Shell.cmd`) — cleaner, Magisk/KernelSU/APatch compatible.
- **CodeGraph**: Global setup across ~8 GitHub projects. Installed as MCP server; global CLAUDE.md updated to auto-suggest `codegraph init -i` when `.codegraph/` missing.
- **Unresolved query**: Term 'modelRoles' not found in any project or CodeGraph source.

## Workflow Conventions
- **Per-change commits**: every logical change is committed as a separate, atomic commit — no batch commits that mix concerns. Each commit has a descriptive message following Conventional Commits format (feat/fix/refactor/chore/docs/test). Push after each commit to origin/main.

## Android Backup GUI Details
### Build
- Debug: `./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`
- Release: `export ANDROID_HOME=/opt/android-sdk && ./gradlew assembleRelease`, sign with `apksigner sign --ks debug.keystore --ks-pass pass:android ...`

### Architecture (27 files created, then refactored)
- **RootShell.kt**: libsu-based root shell; methods `ensureSession()`, `exec()`. Uses `Shell.cmd(command).exec()` with structured timeout. Old custom `ProcessBuilder`-based persistent su session removed (was `execBinary`, `execStreaming`, sentinel pattern). Extension function `String.shellEscape()` for safe single-quoted shell arguments.
- **BackupConfig.kt**: Read/write `backup_settings.conf` using `java.util.Properties`.
- **AppScanner.kt**: `pm list packages -3` via RootShell, returns `List<AppInfo>`.
- **BackupOperation.kt**: Backup APK, data (`/data/data/<pkg>`), OBB, SSAID, permissions; no temp files.
- **RestoreOperation.kt**: Install APK, restore data, SSAID, permissions.
- **WifiManager.kt**: Backup/restore WiFi credentials for Android 8–14. All exec calls check return values; best-effort operations (chown/chmod/wifi reload) are intentionally unchecked.
- **ResticWrapper** (facade) + 5 sub-modules: `ResticBackup`, `ResticRestore`, `ResticSnapshotOps`, `ResticMaintenance`, `ResticRepoInit`. Restic binary executed via `ProcessBuilder` (not libsu — it's a regular binary, not a su command). SMB/WebDAV remote sync via `RemoteSyncManager` with Mutex-guarded temp repo.
- **RemoteTransport**: Abstract interface; implementations: `WebdavTransport` (Sardine/OkHttp) and `SmbTransport` (jcifs-ng).
- **UI**: `MainActivity.kt` with ViewPager2 + BottomNavigationView, three fragments (Backup, Restore, Config).

### Key Lessons
- libsu (`Shell.cmd`) replaces custom su process management — no more mutex, process lifecycle, stdin/stdout handling.
- Root shell persistence via libsu is automatic (Shell.cmd manages su sessions internally).
- WiFi config file locations vary by Android version (probe common paths).
- SMB/WebDAV operations run on `Dispatchers.IO` through RemoteTransport interface.
- Backup/restore use `Semaphore(3)` / `Semaphore(2)` for controlled concurrency.
- All mutable shared state protected via `@Volatile` + `Mutex`/`synchronized` (see RemoteSyncManager, ResticBinary).

### SMB Debugging (ECONNREFUSED)
- Enhanced error message includes host:port; timeout: 15s connect, 30s socket.
- Server-side: check listening ports (`netstat -tlnp | grep -E '445|139'`), start smbd, check firewall.
- Ports: 445 (direct SMB), 139 (NetBIOS). URL format: `smb://host:port/share`.

### Notes
- `scripts/` directory intended for user-provided shell scripts; currently only `README.md`. Core app uses RootShell natively — no external scripts required.
- `.github/copilot-instructions.md` lists project tasks and progress.
- Dependencies: `kotlin-stdlib:1.9.0`, `libsu:6.0.0`, `jcifs-ng` (SmbTransport), `sardine-android` (WebdavTransport), `compileSdk 34`, `minSdk 24`.

## CodeGraph Setup
- Installation: `curl -fsSL https://raw.githubusercontent.com/colbymchenry/codegraph/main/install.sh | sh` (macOS/Linux) or `npx @colbymchenry/codegraph`.
- Initialize: `cd project && codegraph init -i`.
- Global CLAUDE.md (`~/.claude/CLAUDE.md`) updated with: *If working in a project and `.codegraph/` does not exist, offer to run `codegraph init -i` and explain benefits.*
- Uninstall: `codegraph uninstall`.
