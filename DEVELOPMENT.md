# Development

## Build

Needs a JDK 11+ (the RuneLite launcher ships a JRE only — no compiler). The Gradle
wrapper is checked in, so no separate Gradle install is needed.

```powershell
.\gradlew.bat build          # macOS/Linux: ./gradlew build
```

Builds against `net.runelite:client:latest.release`. The compile targets Java 11 via
`options.release`, so any JDK 11+ works — no toolchain auto-provisioning.

## Run a dev client

```powershell
.\gradlew.bat run            # `runClient` is an alias
```

Loads the plugin via `AdvisorPluginLauncher`, which calls
`ExternalPluginManager.loadBuiltin` — launching `net.runelite.client.RuneLite` directly
would start the client *without* this plugin, since external plugins aren't discovered
from the classpath.

The plugin is useless without its companion server listening on the configured Ingest URL
(default `http://localhost:8777/api/ingest`).

## Logging in with a Jagex account

A dev client can't authenticate a Jagex account by itself — the Jagex Launcher holds the
session. Follow the official guide:
https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts

Short version (Windows): run **RuneLite (configure)**, add `--insecure-write-credentials`
to Client arguments, launch OSRS once through the Jagex Launcher; that writes
`~/.runelite/credentials.properties`, which every dev-client launch then reads. The file
grants account access bypassing your password — never share or commit it, and delete it
(or "End sessions" on runescape.com) when you're done.

## Sideloading into a retail client

```powershell
copy build\libs\osrs-advisor-1.1.0.jar $env:USERPROFILE\.runelite\plugins\
```

Sideloaded plugins aren't hub-reviewed and don't auto-update — prefer the Plugin Hub
build once published.

## Gotchas already hit (so you don't re-hit them)

- **`Assertions are not enabled, add '-ea'`** — `loadBuiltin` refuses to run without
  them. The `run` task passes `-ea`.
- **`InaccessibleObjectException` on `java.lang.reflect`** — RuneLite's own
  `ReflectUtil.invalidateAnnotationCaches` on JDK 17. Non-fatal; silenced with
  `--add-opens java.base/java.lang.reflect=ALL-UNNAMED` (the `run` task passes it).
- **`net.runelite.api.gameval.InventoryID`** — correct for current clients. If you ever
  build against a client older than the `gameval` move, switch the import and use
  `InventoryID.BANK.getId()`. `ItemContainerChanged#getContainerId` is stable across both.

## Plugin Hub constraints this repo honors

- No reflection, no dynamic classloading, no extra dependencies beyond the template's
  (lombok, junit). A Quest Helper hand-off that reflectively invoked
  `QuestMenuHandler.startUpQuest` was removed for exactly this rule — do not reintroduce
  it unless Quest Helper ever exposes a sanctioned integration point.
- All HTTP goes through the injected `OkHttpClient` with `enqueue()`; JSON through the
  injected `Gson`.
- Resources load via `ImageUtil.loadImageResource` (jar-safe `getResourceAsStream`).
- The plugin's description (here and in `runelite-plugin.properties`) doubles as the
  hub-required disclosure of what data is sent off-client; keep it accurate when the
  payload grows.
