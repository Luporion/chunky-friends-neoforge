# Porting notes: Fabric 26.2 -> NeoForge 1.21.1

## Loader / runtime

- Fabric Loom -> NeoForge ModDevGradle
- Minecraft 26.2 -> 1.21.1
- Java 25 -> Java 21
- `fabric.mod.json` -> `META-INF/neoforge.mods.toml`
- Fabric lifecycle/connection/tick/command callbacks -> NeoForge event bus
- Fabric config path -> `FMLPaths.CONFIGDIR`
- Upstream mod id `chunky-friends` -> NeoForge id `chunky_friends`

## Minecraft API backport

- 26.2 `Identifier`-style dimension access -> 1.21.1 `ResourceLocation` via
  `player.level().dimension().location()`
- Game profile name access uses `getName()` on 1.21.1

## Chunky API backport

- Chunky dependency: 1.5.3 -> 1.4.23
- `ShapeType.CIRCLE` -> `"circle"`
- `PatternType.CONCENTRIC` -> `"concentric"`
- Chunk radius is still converted to blocks before calling Chunky's API.

## Threading / pause correctness

Chunky progress and completion callbacks are marshalled to `MinecraftServer.execute(...)`, preserving the
upstream thread-safety design.

When a player joins, `presencePaused` is set *before* calling `ChunkyAPI.pauseTask`. Chunky 1.4.x emits its
completion event when the stopped worker exits even for a pause. Marking the state first prevents a quickly
queued completion callback from being treated as a genuinely completed ring.

## Client feature scope

The upstream Fabric GUI/networking layer was intentionally excluded from this first port. It depends on
Fabric networking, ModMenu and Minecraft 26.2 client rendering/input APIs and is independent of the server
scheduler. Server commands cover the scheduler configuration/status surface needed to operate the mod.

## Validation performed in this workspace

- No production Fabric imports remain.
- No Chunky 1.5.x `ShapeType` / `PatternType` API remains.
- All production Java sources passed a Java 21 compile check against local API-signature stubs.
- Core scheduling checks passed for:
  - quadratic ring radius
  - tier 0 / max tier / invalid negative tier
  - lowest-tier player selection
  - oldest-serviced tie-break
  - block/chunk radius parsing
- A real Gradle/NeoForge build was attempted, but this execution environment cannot resolve
  `services.gradle.org`, so external Gradle/NeoForge dependencies could not be downloaded here.

## NeoForge 1.21.1 hotfix 2

The first real NeoForge build exposed one remaining 26.x-only Minecraft API reference in
`PlayerStateStore`: `LevelResource.DATA` does not exist in Minecraft 1.21.1. The persisted file must still
live at `<world>/data/chunky-friends_state.json`, so the 1.21.1 port now resolves it from
`LevelResource.ROOT` and appends `data/chunky-friends_state.json` explicitly.

`build-windows.bat` also checks that the active Java runtime is Java 21 before starting Gradle.

## NeoForge 1.21.1 build fixes

- `.2`: replaced the post-1.21.1 `LevelResource.DATA` usage with `LevelResource.ROOT.resolve("data")`.
- `.3`: fixed Gradle 9 configuration-cache incompatibility in the `jar` task. The old LICENSE rename closure referenced `project.name` at task execution time; the JAR now simply contains `LICENSE` at its root.

- `.4`: fixed the plain JUnit test runtime. `ChunkyFriendsConfig` statically references Gson and SLF4J;
  ModDevGradle exposed those libraries while compiling the mod, but the standalone `test` task did not
  have them on its runtime classpath. Gson and an SLF4J no-op provider are now explicit `testRuntimeOnly`
  dependencies. They are test-only and are not bundled into the production JAR.
