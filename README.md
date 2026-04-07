# 🎬 CinematicCamera Plugin — Full README

> A Minecraft (Paper/Spigot) plugin for creating smooth, cinematic camera paths with rotation, speed zones, keyframe effects, and more.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [How It Works — Core Architecture](#how-it-works--core-architecture)
3. [Commands Reference](#commands-reference)
4. [Removing a Keyframe (removekeyframe)](#removing-a-keyframe)
5. [Keyframe Types — Full Reference](#keyframe-types--full-reference)
6. [Speed Zones](#speed-zones)
7. [Auto-Play Trigger](#auto-play-trigger)
8. [Playback System — How It Works in Code](#playback-system--how-it-works-in-code)
9. [Path Interpolation — Catmull-Rom Splines](#path-interpolation--catmull-rom-splines)
10. [Camera Rotation Interpolation](#camera-rotation-interpolation)
11. [State Management Maps](#state-management-maps)
12. [Full Workflow Example](#full-workflow-example)
13. [Tab Completion](#tab-completion)
14. [showcase](https://youtu.be/XeyuTUPXr4E)

---

## Quick Start

1. Run `/cinematicwand` to get the wand item (a Blaze Rod).
2. Walk to a position and **Left Click** to add a waypoint. Your camera's yaw (horizontal rotation) and pitch (vertical rotation) are saved with each waypoint.
3. Add at least 2+ waypoints along your desired path.
4. Run `/cinematic save mypath normal` to save it.
5. Run `/cinematic play mypath` to watch it.

---

## How It Works — Core Architecture

The plugin is a single class `CinematicCamera` that extends `JavaPlugin` and implements `Listener`. All state is stored in in-memory `HashMap`/`HashSet` fields (no persistence to disk between restarts).

### Core State Maps

| Field | Type | Purpose |
|-------|------|---------|
| `paths` | `Map<String, CinematicPath>` | All saved cinematic paths, keyed by name |
| `editors` | `Map<UUID, PathEditor>` | Active wand sessions per player — holds their unsaved waypoints |
| `playingCinematic` | `Set<UUID>` | Players currently watching a cinematic |
| `originalGameModes` | `Map<UUID, GameMode>` | Stores each player's gamemode before the cinematic starts so it can be restored |
| `activeAnimations` | `Map<UUID, BukkitTask>` | The running `BukkitRunnable` task for each player — used to cancel on stop |
| `savedLocations` | `Map<UUID, Location>` | Player's location before cinematic — teleported back on end |
| `savedFlying` | `Map<UUID, Boolean>` | Whether the player was flying before — restored on end |
| `awaitingClick` | `Set<UUID>` | Players currently paused on a `PAUSE_CLICK` keyframe, waiting for any click |

---

## Commands Reference

### `/cinematicwand`
Gives you the Blaze Rod wand item.
- **Left Click** (air or block): Adds your current position + rotation as a waypoint
- **Right Click**: Shows current waypoint count and how to save
- **Shift + Right Click**: Clears all your current unsaved waypoints

### `/cinematic save <name> <speed>`
Saves your current waypoints as a named path.

**Speed values:**
| Input | Multiplier |
|-------|-----------|
| `slow` or `cinematic` | 0.3x |
| `normal` or `medium` | 1.0x |
| `fast` | 2.0x |
| `veryfast` or `rapid` | 3.5x |
| `0.1` – `5.0` | Custom |

### `/cinematic play <name>`
Starts the cinematic for you. Puts you in SPECTATOR mode, applies invisibility, teleports you to waypoint 0, and begins the animation loop.

### `/cinematic stop`
Immediately stops the cinematic, cancels the task, removes effects, and teleports you back to your saved location.

### `/cinematic list`
Lists all saved paths with their waypoint count, speed, keyframe count, speed zone count, and auto-play status.

### `/cinematic delete <name>`
Removes a saved path entirely.

### `/cinematic setspeed <name> <speed>`
Changes the base speed multiplier of an existing path.

### `/cinematic toggle <name>`
Toggles the auto-play trigger on or off for a path. When on, walking within 1.5 blocks of the first waypoint auto-starts the cinematic.

### `/cinematic clear`
Clears your current unsaved wand waypoints (same as Shift+Right Click).

### `/cinematic settitle <name> <title> [| subtitle]`
Sets the title shown at the beginning of a cinematic.
- Use `|` to separate title and subtitle: `/cinematic settitle mypath Welcome | Enjoy the view`

### `/cinematic addkeyframe <name> <waypointIndex> <type> [args...]`
Adds an effect keyframe that triggers when the camera reaches the given waypoint.

### `/cinematic listkeyframes <name>`
Lists all keyframes on a path with their index, waypoint, type, and text preview.

### `/cinematic removekeyframe <name> <index>`
Removes a keyframe by its list index (shown in `/cinematic listkeyframes`).

### `/cinematic addzone <name> <startWP> <endWP> <speed>`
Adds a speed zone between two waypoint indices. Overrides the base speed for that segment.

### `/cinematic listzones <name>`
Lists all speed zones on a path.

### `/cinematic removezone <name> <index>`
Removes a speed zone by its list index.

---

## Removing a Keyframe

This is a 2-step process:

**Step 1** — Find the keyframe index:
```
/cinematic listkeyframes spawn
```
This shows output like:
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  [0] wp#2 text_title → Welcome
  [1] wp#4 fade_black
  [2] wp#6 sound → ENTITY_WITHER...
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Step 2** — Remove by that index number:
```
/cinematic removekeyframe spawn 1
```
This removes the `fadeblack` at index `[1]`.

> ⚠️ Indexes are **positional** (0, 1, 2...) and shift after a removal. Always re-run `listkeyframes` after removing one if you plan to remove more.

---

## Keyframe Types — Full Reference

Add any of these with `/cinematic addkeyframe <path> <waypointIndex> <type> [args]`

---

### `title`
Shows a title on screen.
```
/cinematic addkeyframe mypath 0 title 10 60 20 Welcome to the Tour | Enjoy the view
```
Args: `<fadeIn ticks> <stay ticks> <fadeOut ticks> <title text> [| subtitle]`

**How it works in code:** Calls `player.showTitle()` using the Adventure API `Title.title()` with `Title.Times`, constructing bold white title text and gray subtitle text.

---

### `actionbar`
Shows text in the action bar (above the hotbar).
```
/cinematic addkeyframe mypath 2 actionbar Look to your left!
```

**How it works:** Calls `player.sendActionBar()` with yellow-tinted text.

---

### `chat`
Sends a ✦ prefixed message to the player's chat.
```
/cinematic addkeyframe mypath 3 chat This is where the story began.
```

---

### `pause`
Pauses the camera movement for a set number of seconds.
```
/cinematic addkeyframe mypath 4 pause 3
```
Args: `<seconds>`

**How it works:** Sets `paused = true` and `pauseTicksLeft = seconds * 20`. The `BukkitRunnable` returns early every tick until the counter hits 0.

---

### `click`
Pauses the camera indefinitely until the player clicks (any mouse button).
```
/cinematic addkeyframe mypath 5 click
```

**How it works:** Adds the player to `awaitingClick` set and shows an action bar prompt. The `PlayerInteractEvent` handler removes them from the set on any click. The animation loop checks `awaitingClick.contains(uuid)` and returns early until cleared.

---

### `sound`
Plays a sound at the player's location.
```
/cinematic addkeyframe mypath 6 sound ENTITY_WITHER_SPAWN 1.0 0.8
```
Args: `<SOUND_NAME> [volume] [pitch]`

**How it works:** Uses `player.playSound()`. Sound name is looked up via `Registry.SOUNDS.get(NamespacedKey.minecraft(...))`. Falls back to `BLOCK_NOTE_BLOCK_PLING` if invalid.

---

### `particle`
Spawns a burst of firework particles around the camera.
```
/cinematic addkeyframe mypath 7 particle
```

**How it works:** Calls `world.spawnParticle(Particle.FIREWORK, location, 40, 1, 1, 1, 0.05)`.

---

### `shake`
Shakes the camera by rapidly teleporting it in tiny random offsets.
```
/cinematic addkeyframe mypath 8 shake
```

**How it works:** Schedules 10 `runTaskLater` calls (1 tick apart) that each add a small random offset (`-0.1` to `+0.1` on each axis) to the player's location and teleport them there.

---

### `fadeblack`
Applies Blindness effect to fade the screen to black.
```
/cinematic addkeyframe mypath 9 fadeblack 2
```
Args: `<duration seconds>` (default: 2)

**How it works:** Applies `PotionEffectType.BLINDNESS` for `duration * 20` ticks.

---

### `fadewhite`
Applies Glowing effect to create a white flash/fade.
```
/cinematic addkeyframe mypath 10 fadewhite 2
```
Args: `<duration seconds>` (default: 2)

**How it works:** Applies `PotionEffectType.GLOWING` for `duration * 20` ticks.

---

### `lightning`
Strikes a visual-only lightning bolt at the camera position.
```
/cinematic addkeyframe mypath 11 lightning
```

**How it works:** Calls `world.strikeLightningEffect(location)` — this is the visual-only version (no fire, no damage).

---

### `explosion`
Creates a visual explosion effect at the camera position.
```
/cinematic addkeyframe mypath 12 explosion
```

**How it works:** Calls `world.createExplosion(location, 0, false, false)` — power 0 means no block damage, no fire.

---

### `time`
Sets the world time instantly.
```
/cinematic addkeyframe mypath 3 time 6000
```
Args: `<0–24000>` (0 = sunrise, 6000 = noon, 12000 = sunset, 18000 = midnight)

**How it works:** Calls `world.setTime((long) kf.duration)`.

---

### `weather`
Changes the world's weather.
```
/cinematic addkeyframe mypath 5 weather rain
/cinematic addkeyframe mypath 8 weather clear
/cinematic addkeyframe mypath 10 weather thunder
```
Args: `clear | rain | thunder`

**How it works:**
- `clear` → `world.setStorm(false)`
- `rain` → `world.setStorm(true)`
- `thunder` → `world.setStorm(true)` + `world.setThundering(true)`

---

## Speed Zones

Speed zones override the base path speed for a specific range of waypoints, letting you slow down for dramatic moments or fast-forward through boring stretches.

```
/cinematic addzone mypath 0 3 0.3
```
This makes waypoints 0–3 play at 0.3x speed (very slow/cinematic).

```
/cinematic addzone mypath 5 9 3.0
```
This makes waypoints 5–9 play at 3x speed.

**How it works in code:** During `generateSmoothPath()`, each segment `i` checks `speedZones` to see if `i >= zone.startWaypoint && i < zone.endWaypoint`. If a match is found, `pointsInSegment = basePoints / segmentSpeed`. A higher speed multiplier means fewer interpolated points between waypoints, so they're traversed faster. Each `CameraFrame` also stores `speedMultiplier` which the animation loop uses to skip frames (`index += (1 + skip)` where `skip = speedMultiplier - 1`).

---

## Auto-Play Trigger

When auto-play is enabled on a path (it's on by default when you save), any player who walks within **1.5 blocks** of the path's first waypoint will automatically start the cinematic.

- Toggle it: `/cinematic toggle mypath`
- Checked in `PlayerMoveEvent` — only triggers if the player is not already in a cinematic

---

## Playback System — How It Works in Code

When `playCinematic(player, path)` is called:

1. The player is added to `playingCinematic`, their gamemode/location/flying state are saved.
2. They're set to `SPECTATOR` mode with infinite `INVISIBILITY`.
3. They're teleported to `waypoints.get(0)`.
4. `path.generateSmoothPath()` runs and produces a `List<CameraFrame>`.
5. A `Map<Integer, Keyframe>` is built that maps each keyframe to its smooth-path index (`waypointIndex * basePointsPerSegment`).
6. A `BukkitRunnable` is scheduled to run **every tick** (1/20th of a second).

### Inside the animation loop (per tick):

```
tick fires
  → if player offline: endCinematic, cancel
  → if timed pause active: decrement counter, return
  → if awaiting click: return
  → if index >= smoothPath.size(): endCinematic, send message, cancel
  → check kfMap for keyframe at current index, apply it
     → if PAUSE_TIMED: set paused=true, set counter, increment index, return
     → if PAUSE_CLICK: add to awaitingClick, increment index, return
  → teleport player to smoothPath.get(index).location (position + yaw/pitch)
  → spawn ambient END_ROD particle every 10 ticks
  → advance index (by 1, or more if speed zone skip > 1)
```

The `endCinematic` method:
- Removes player from all state maps
- Cancels the BukkitTask
- Removes potion effects (Invisibility, Blindness, Glowing)
- Clears title
- Restores original GameMode
- Teleports player back to their saved location
- Restores flying state

---

## Path Interpolation — Catmull-Rom Splines

Raw waypoints are spaced far apart. To get smooth camera movement, the plugin interpolates between them using **Catmull-Rom splines** — a curve that passes *through* each control point (unlike Bezier curves which are pulled toward them).

For each segment from waypoint `i` to `i+1`, 4 control points are used:
- `p0` = previous waypoint (or `p1` if at start)
- `p1` = current waypoint
- `p2` = next waypoint
- `p3` = waypoint after next (or `p2` if at end)

The formula applied per axis (x, y, z):
```
x(t) = 0.5 * (
    2*p1  +
    (-p0 + p2) * t  +
    (2*p0 - 5*p1 + 4*p2 - p3) * t²  +
    (-p0 + 3*p1 - 3*p2 + p3) * t³
)
```

Where `t` goes from 0.0 to 1.0 across the segment. The number of points per segment is:
```
basePointsPerSegment = max(10, 60 / speed)
```
So at `normal` speed (1.0x): 60 points per segment = 60 ticks = 3 seconds per waypoint-to-waypoint. At `fast` (2.0x): 30 points = 1.5 seconds.

---

## Camera Rotation Interpolation

Each waypoint stores the player's **yaw** (horizontal, 0°–360°) and **pitch** (vertical, -90° to +90°) at the time it was placed.

When the wand records a waypoint:
```java
Location loc = player.getLocation().clone(); // includes yaw and pitch
editor.addWaypoint(loc);
```

During smooth path generation, rotation is linearly interpolated between adjacent waypoints:

**Pitch** uses standard linear interpolation:
```java
float pitch = (float) lerp(p1.getPitch(), p2.getPitch(), t);
// lerp(a, b, t) = a + (b - a) * t
```

**Yaw** uses angle-aware lerp to prevent spinning the wrong way around (e.g., 350° → 10° goes forward, not backward 340°):
```java
double lerpAngle(double a, double b, double t) {
    double diff = ((b - a + 180) % 360) - 180;
    return a + diff * t;
}
```
This normalizes the difference to the range `-180` to `+180`, ensuring the rotation always takes the shortest path.

---

## State Management Maps

### Why SPECTATOR mode?
Players in SPECTATOR can be freely teleported each tick without physics interference. `PlayerMoveEvent` blocks X/Y/Z movement (while allowing head rotation) so the player can't fight the cinematic.

### Why save/restore gamemode and location?
When the cinematic ends (normally or via `/cinematic stop`), the player needs to return to exactly where they were with exactly the gamemode they had. These are stored in `originalGameModes` and `savedLocations` at start and retrieved in `endCinematic`.

### Why the `awaitingClick` set?
The animation loop runs every tick independently of player input. The `PAUSE_CLICK` keyframe needs to pause across ticks until a click happens. The set acts as a shared flag: the animation loop checks it, and `PlayerInteractEvent` clears it.

---

## Full Workflow Example

Here's a complete example building a dramatic cinematic flythrough:

```
# 1. Get wand
/cinematicwand

# 2. Walk to starting position, look where you want the camera to face, Left Click
# Add 5+ more waypoints around your scene

# 3. Save at slow speed
/cinematic save myflythrough slow

# 4. Add an intro title at waypoint 0
/cinematic addkeyframe myflythrough 0 title 10 80 20 Welcome | The story begins here

# 5. Play a dramatic sound at waypoint 2
/cinematic addkeyframe myflythrough 2 sound ENTITY_WITHER_SPAWN 1.0 0.6

# 6. Fade to black at waypoint 4
/cinematic addkeyframe myflythrough 4 fadeblack 3

# 7. Set time to sunset at waypoint 5
/cinematic addkeyframe myflythrough 5 time 12000

# 8. Pause the camera at waypoint 3 for 2 seconds
/cinematic addkeyframe myflythrough 3 pause 2

# 9. Make waypoints 1-2 slower (dramatic pan)
/cinematic addzone myflythrough 1 2 0.2

# 10. Make waypoints 5-7 faster (fast flythrough)
/cinematic addzone myflythrough 5 7 3.0

# 11. Set intro title for the path
/cinematic settitle myflythrough The Great Journey | Act I

# 12. Review everything
/cinematic listkeyframes myflythrough
/cinematic listzones myflythrough

# 13. Play it
/cinematic play myflythrough

# 14. If you need to remove keyframe [1] (fadeblack):
/cinematic listkeyframes myflythrough
# → finds index, e.g. [1]
/cinematic removekeyframe myflythrough 1
```

---

## Tab Completion

The plugin provides tab completion on `/cinematic`:
- Arg 1: all subcommand names
- Arg 2 (for path commands): all saved path names
- Arg 3 (for `save`/`setspeed`/`addzone`): speed presets (`slow`, `normal`, `fast`, `veryfast`, `0.5`, `1.0`, etc.)
- Arg 3 (for `addkeyframe`): waypoint index suggestions (`0`–`5`)
- Arg 4 (for `addkeyframe`): all keyframe types
- Arg 5 (for `addkeyframe weather`): `clear`, `rain`, `thunder`
