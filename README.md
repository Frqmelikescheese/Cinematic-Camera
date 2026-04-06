# 🎥 Cinematic Camera
### ***The Ultimate Smooth-Path Tool for Creators & Server Owners***

> **Transform your server showcases!** Whether you're filming a high-budget trailer, showing off a new spawn, or creating an immersive "intro" for new players, **Cinematic Camera** provides the mathematical precision of professional film gear directly inside Minecraft.

---

## ✨ Key Features

| Feature | Description |
| :--- | :--- |
| **📈 Catmull-Rom Splines** | Uses advanced interpolation to turn waypoints into **ultra-smooth curves**. |
| **🪄 Cinematic Wand** | A specialized Blaze Rod tool for marking 3D waypoints with visual particle feedback. |
| **🤖 Auto-Trigger** | Set paths to **Auto-Play** when a player walks near the starting point. |
| **🏎️ Dynamic Speed** | Choose from presets or define custom multipliers from **0.1x to 5.0x**. |
| **👻 Ghost Mode** | Automatically toggles **Spectator mode** and **Invisibility** for a "clean" lens. |
| **🔒 State Recovery** | Returns players to their **original GameMode** once the sequence ends. |

---

## 🛠 How to Use the Wand

### ***1. Get the Gear***
Run `/cinematicwand` to receive the **Cinematic Wand**.
*   **`LEFT-CLICK`**: Add a waypoint at your current position.
*   **`RIGHT-CLICK`**: View path info and save instructions.
*   **`SHIFT + RIGHT-CLICK`**: Wipe your current selection and start fresh.

### ***2. Save the Path***
Once you have at least 2 waypoints, save your path with a name and speed:
` /cinematic save [name] [speed] `
*(Example: `/cinematic save spawn_tour slow`)*

### ***3. Play & Manage***
Type `/cinematic play [name]` to start the sequence!

---

## 💻 Command Reference

### **Primary Commands**
| Command | Action |
| :--- | :--- |
| `/cinematic play <name>` | ***Starts*** the specified camera path. |
| `/cinematic stop` | ***Emergency Stop*** for your current cinematic. |
| `/cinematic list` | Shows all saved paths in a clean chat menu. |
| `/cinematic toggle <name>` | Enables/Disables **Auto-Play** for that path. |
| `/cinematic delete <name>` | Permanently removes a path. |
| `/cinematic setspeed <n> <s>` | Updates the speed of an existing path. |
| `/cinematic reload` | Reloads the plugin configuration. |

### **Speed Presets**
| Preset | Multiplier | Visual Feel |
| :--- | :--- | :--- |
| `slow` | **0.3x** | *Dramatic, cinematic sweeping shots.* |
| `normal` | **1.0x** | *Standard walking pace.* |
| `fast` | **2.0x** | *Great for action or large build tours.* |
| `veryfast` | **3.5x** | *Rapid traversal or "time-lapse" style.* |

---

## ⚙️ Technical Deep-Dive

### ### **Smooth Look-Ahead Logic**
The camera doesn't just slide; it **anticipates**. The plugin calculates a "Look Target" several points ahead of your current position. This creates a natural, fluid rotation that mimics a real cameraman turning their head, rather than a rigid robotic movement.

### ### **Immersive Experience**
When a cinematic starts, the plugin:
1.  **Saves** your current GameMode.
2.  **Switches** you to `SPECTATOR`.
3.  **Applies** `INVISIBILITY` (no particles/icons).
4.  **Locks** manual movement so you stay perfectly on track.

---

## 🛑 Permissions & Requirements
*   **`cinematic.admin`**: Required for the `/cinematic reload` command.
*   **Platform**: Designed for modern Paper/Spigot servers (1.19+).
*   **Dependencies**: None! Just drag, drop, and record.

***

### ***"The best camera is the one you have in your inventory."*** 
> *Created by frqme* | **[Report a Bug]** | **[Suggest a Feature]**
