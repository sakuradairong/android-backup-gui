User has two main areas: Android Backup GUI (Kotlin app with native root execution, SMB/WebDAV remote storage, WiFi backup) and CodeGraph global setup (MCP server for code intelligence, auto-init prompt in CLAUDE.md). Key technical lessons: root shell persistence, SMB troubleshooting (ECONNREFUSED), and project build commands. The term 'modelRoles' remains unresolved.

## Workflow conventions

- **Per-change commits**: every logical change is committed as a separate, atomic commit — no batch commits that mix concerns. Each commit has a descriptive message following Conventional Commits format.
