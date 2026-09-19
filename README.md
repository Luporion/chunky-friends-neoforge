# Chunky Friends — NeoForge 1.21.1 port

A NeoForge 1.21.1 fork/port of **Chunky Friends** by OnTheHill Studios / ExodusOTH.
The upstream project is MIT-licensed; see `LICENSE`.

This port targets the use case the original mod was built for: remember where players have been and let
Chunky progressively pregenerate larger circular areas around recently active players while the server is
empty.

## Target

- Minecraft **1.21.1**
- NeoForge **21.1.x** (development version configured as 21.1.235)
- Java **21**
- Chunky **1.4.23**
- Mod id: `chunky_friends` (NeoForge mod ids cannot use the upstream `chunky-friends` id)

The persisted files intentionally keep the upstream names so an existing state/config can be reused:

- `config/chunky-friends.json`
- `<world>/data/chunky-friends_state.json`

## What is ported

- Player position tracking
- Recent-player eligibility window
- Ring/tier progression
- Linear and quadratic radius curves
- Fair min-tier / oldest-serviced player selection
- Chunky start, pause, continue, progress and completion integration
- Pause when a player joins; resume when the server becomes empty
- Periodic online-player position refresh
- Persistent per-player state
- Config persistence
- Dedicated-server / integrated-server lifecycle wiring via NeoForge events
- `/chunkyfriends status`
- `/chunkyfriends players`
- `/chunkyfriends config`
- `/chunkyfriends config ringcount <1..64>`
- `/chunkyfriends config maxradius <blocks|chunks c>`
- `/chunkyfriends config curve linear|quadratic`
- `/chunkyfriends reset`

Commands require vanilla permission level 2 (or singleplayer). Command text has built-in English fallbacks,
so the mod can be installed server-side without requiring it on every client.

## Deliberately not in this first NeoForge port

The Fabric 26.2 source's optional client configuration GUI, ModMenu integration, terrain map preview and
Fabric custom-payload protocol are not included yet. They are not required for pregeneration; every setting
that controls the scheduling curve is available from the server command and JSON config.

This keeps the first 1.21.1 port server-safe and avoids coupling the core scheduler to a completely different
1.21.1 NeoForge networking/rendering stack. They can be ported as a second step without changing the saved
scheduler state.

## Default configuration

```json
{
  "_maxRadiusChunks": 100,
  "_ringCount": 25,
  "_curveExponent": 2.0,
  "_qualifyingWindowHours": 24,
  "_stallTimeoutTicks": 12000,
  "_checkIntervalTicks": 200,
  "_progressLogIntervalSeconds": 30
}
```

A bare `maxradius` command value is interpreted as **blocks**, matching Chunky's convention. Add `c` for
chunks, for example:

```text
/chunkyfriends config maxradius 8000
/chunkyfriends config maxradius 500c
```

Changing ring count, max radius or curve resets Chunky Friends' *heuristic tier counter* to 0. This does not
regenerate existing terrain: Chunky remains the source of truth and skips chunks it already generated.


> NeoForge 1.21.1 port build revision: `1.1.2+mc1.21.1-neoforge.4`. The `.4` hotfix adds the
> Gson/SLF4J libraries needed only by the standalone JUnit test runtime; they are not packaged into the mod JAR.

## Build

From the project root:

```text
./gradlew build
```

On Windows:

```text
gradlew.bat build
```

The built mod will be in `build/libs/`.

A GitHub Actions workflow is included at `.github/workflows/build.yml`. After pushing this project to your
fork, every push/PR can build the jar on GitHub and expose it as the `ChunkyFriends-NeoForge-1.21.1`
workflow artifact.

The Gradle build uses Chunky's public `org.popcraft:chunky-common:1.4.23` API for compilation and the
NeoForge 1.4.23 Modrinth artifact for development runs.

## Installation

1. Install Minecraft 1.21.1 + NeoForge 21.1.x.
2. Put **Chunky-NeoForge-1.4.23.jar** in the server's `mods` directory.
3. Put the built Chunky Friends NeoForge jar in the same `mods` directory.
4. Start the server once to create `config/chunky-friends.json`.
5. Adjust the config or use `/chunkyfriends config ...`.

The port itself has no required client-side component.

## Notes about the Chunky 1.4.x API

The uploaded upstream source targets Chunky 1.5.3 and uses `ShapeType` / `PatternType`. Chunky 1.4.23 uses the
older API signature:

```java
startTask(world, "circle", centerX, centerZ, radiusX, radiusZ, "concentric")
```

This port intentionally uses that 1.4.x signature. Compiling the 26.2 source unchanged against 1.5.x would
not be runtime-compatible with the Chunky version used by Minecraft 1.21.1.

## License

MIT — see `LICENSE`. Original copyright/attribution is retained.

## 1.21.1 port hotfix

Minecraft 1.21.1 does not expose the newer `LevelResource.DATA` constant used by the upstream 26.2 source.
This fork resolves the state file as `LevelResource.ROOT/data/chunky-friends_state.json`, preserving the
same on-disk location while compiling against 1.21.1.


### Build note for Gradle 9

The NeoForge `.3` hotfix removes a configuration-cache-incompatible LICENSE rename closure that could make `:jar` fail on Gradle 9.2.1 even after Java compilation had succeeded.
