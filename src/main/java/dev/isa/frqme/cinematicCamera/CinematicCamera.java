package dev.isa.frqme.cinematicCamera;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;

public class CinematicCamera extends JavaPlugin implements Listener {

    // ─────────────────────────────────────────────────
    // State maps
    // ─────────────────────────────────────────────────
    private final Map<String, CinematicPath> paths           = new HashMap<>();
    private final Map<UUID, PathEditor>      editors         = new HashMap<>();
    private final Set<UUID>                  playingCinematic = new HashSet<>();
    private final Map<UUID, GameMode>        originalGameModes = new HashMap<>();
    private final Map<UUID, BukkitTask>      activeAnimations = new HashMap<>();
    private final Map<UUID, Location>        savedLocations   = new HashMap<>();
    private final Map<UUID, Boolean>         savedFlying      = new HashMap<>();

    // Players waiting for a "click to continue" pause
    private final Set<UUID> awaitingClick = new HashSet<>();

    // ─────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Cinematic Camera enabled! Now with CAMERA ROTATION & SPEED ZONES!");
    }

    @Override
    public void onDisable() {
        for (UUID uuid : new HashSet<>(playingCinematic)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) endCinematic(p);
        }
        getLogger().info("Cinematic Camera disabled!");
    }

    // ─────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        // ── "Click to continue" resume ──────────────────
        if (awaitingClick.contains(player.getUniqueId())) {
            Action a = event.getAction();
            if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK
                    || a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                awaitingClick.remove(player.getUniqueId());
                return;
            }
        }

        // ── Wand logic ───────────────────────────────────
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta.displayName() == null) return;
        String name = ((net.kyori.adventure.text.TextComponent) meta.displayName()).content();
        if (!name.equals("Cinematic Wand")) return;

        event.setCancelled(true);
        Action action = event.getAction();
        Location loc  = player.getLocation().clone(); // NOW INCLUDES PITCH & YAW!

        if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) {
            PathEditor editor = editors.computeIfAbsent(player.getUniqueId(), k -> new PathEditor());
            editor.addWaypoint(loc);
            player.sendMessage(Component.text("✓ Waypoint #" + editor.waypoints.size() + " added at "
                            + fmt(loc.getX()) + ", " + fmt(loc.getY()) + ", " + fmt(loc.getZ())
                            + " (yaw: " + fmt(loc.getYaw()) + "° pitch: " + fmt(loc.getPitch()) + "°)",
                    NamedTextColor.GREEN));
            player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
            player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc, 10, .3, .3, .3);

        } else if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
            if (player.isSneaking()) {
                PathEditor editor = editors.remove(player.getUniqueId());
                if (editor != null) {
                    player.sendMessage(Component.text("✗ " + editor.waypoints.size() + " waypoints cleared!", NamedTextColor.RED));
                    player.playSound(loc, Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                } else {
                    player.sendMessage(Component.text("No waypoints to clear.", NamedTextColor.GRAY));
                }
            } else {
                PathEditor editor = editors.get(player.getUniqueId());
                if (editor == null || editor.waypoints.size() < 2) {
                    player.sendMessage(Component.text("Need at least 2 waypoints! ("
                            + (editor != null ? editor.waypoints.size() : 0) + " set)", NamedTextColor.RED));
                    return;
                }
                player.sendMessage(sep());
                player.sendMessage(Component.text("Path has " + editor.waypoints.size() + " waypoints", NamedTextColor.YELLOW));
                player.sendMessage(Component.text("Use: /cinematic save <name> <speed>", NamedTextColor.GOLD));
                player.sendMessage(Component.text("Speeds: slow | normal | fast | veryfast | 0.1‒5.0", NamedTextColor.GRAY));
                player.sendMessage(sep());
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // Block position movement during cinematic (allow head rotation)
        if (playingCinematic.contains(player.getUniqueId())) {
            Location from = event.getFrom(), to = event.getTo();
            if (to != null &&
                    (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
                event.setCancelled(true);
            }
            return;
        }

        // Auto-play trigger
        Location pLoc = player.getLocation();
        for (CinematicPath path : paths.values()) {
            if (path.autoPlay && path.startLocation != null
                    && isNearby(pLoc, path.startLocation, 1.5)
                    && !playingCinematic.contains(player.getUniqueId())) {
                playCinematic(player, path);
                break;
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        editors.remove(event.getPlayer().getUniqueId());
        endCinematic(event.getPlayer());
    }

    // ─────────────────────────────────────────────────
    // Core playback
    // ─────────────────────────────────────────────────
    public void playCinematic(Player player, CinematicPath path) {
        if (playingCinematic.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("You're already watching a cinematic!", NamedTextColor.RED));
            return;
        }

        playingCinematic.add(player.getUniqueId());
        originalGameModes.put(player.getUniqueId(), player.getGameMode());
        savedLocations.put(player.getUniqueId(), player.getLocation().clone());
        savedFlying.put(player.getUniqueId(), player.isFlying());

        player.setGameMode(GameMode.SPECTATOR);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 999999, 0, false, false, false));
        player.teleport(path.waypoints.get(0));

        List<CameraFrame> smoothPath = path.generateSmoothPath();

        sendTitle(player, path.title, path.subtitle, 10, 40, 20);
        player.sendMessage(Component.text("▶ Cinematic '" + path.name + "' started  (base speed: "
                + String.format("%.1fx", path.speed) + ")", NamedTextColor.AQUA));

        // Build keyframe map
        Map<Integer, Keyframe> kfMap = new HashMap<>();
        for (Keyframe kf : path.keyframes) {
            int wpIdx = Math.min(kf.waypointIndex, path.waypoints.size() - 1);
            int smoothIdx = wpIdx * path.basePointsPerSegment();
            kfMap.put(smoothIdx, kf);
        }

        BukkitRunnable anim = new BukkitRunnable() {
            int index = 0;
            boolean paused = false;
            int pauseTicksLeft = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    endCinematic(player);
                    cancel();
                    return;
                }

                // ── Handle timed pause ──────────────────────
                if (paused && pauseTicksLeft > 0) {
                    pauseTicksLeft--;
                    return;
                }
                if (paused && pauseTicksLeft <= 0) {
                    paused = false;
                }

                // ── Handle click-to-continue pause ──────────
                if (awaitingClick.contains(player.getUniqueId())) {
                    return;
                }

                // ── End check ───────────────────────────────
                if (index >= smoothPath.size()) {
                    endCinematic(player);
                    if (path.endLocation != null) player.teleport(path.endLocation);
                    player.sendMessage(Component.text("■ Cinematic ended.", NamedTextColor.GREEN));
                    cancel();
                    activeAnimations.remove(player.getUniqueId());
                    return;
                }

                // ── Keyframe effects ─────────────────────────
                Keyframe kf = kfMap.get(index);
                if (kf != null) {
                    applyKeyframe(player, kf);
                    if (kf.type == KeyframeType.PAUSE_TIMED) {
                        paused = true;
                        pauseTicksLeft = (int)(kf.duration * 20) - 1;
                        index++;
                        return;
                    }
                    if (kf.type == KeyframeType.PAUSE_CLICK) {
                        awaitingClick.add(player.getUniqueId());
                        index++;
                        return;
                    }
                }

                // ── Move camera WITH ROTATION ────────────────
                CameraFrame frame = smoothPath.get(index);
                player.teleport(frame.location);

                // Ambient particles
                if (index % 10 == 0) {
                    player.getWorld().spawnParticle(Particle.END_ROD, frame.location, 1, 0, 0, 0, 0);
                }

                // Handle per-segment speed zones
                int skip = frame.speedMultiplier > 1.0 ? (int)frame.speedMultiplier - 1 : 0;
                index += (1 + skip);
            }
        };

        BukkitTask task = anim.runTaskTimer(this, 0L, 1L);
        activeAnimations.put(player.getUniqueId(), task);
    }

    private void applyKeyframe(Player player, Keyframe kf) {
        switch (kf.type) {
            case TEXT_TITLE -> sendTitle(player, kf.text, kf.subText,
                    kf.fadeIn, kf.stay, kf.fadeOut);
            case TEXT_ACTIONBAR -> player.sendActionBar(
                    Component.text(kf.text, TextColor.color(0xFFFFAA)));
            case TEXT_CHAT -> player.sendMessage(
                    Component.text("  ✦ " + kf.text, NamedTextColor.YELLOW));
            case SOUND -> player.playSound(player.getLocation(),
                    parseSound(kf.text), kf.duration > 0 ? (float) kf.duration : 1f, kf.pitch);
            case PARTICLE_BURST -> player.getWorld().spawnParticle(
                    Particle.FIREWORK, player.getLocation(), 40, 1, 1, 1, 0.05);
            case PAUSE_TIMED -> {}
            case PAUSE_CLICK -> {
                player.sendActionBar(Component.text("  [ Click to continue ]  ",
                        NamedTextColor.WHITE).decorate(TextDecoration.ITALIC));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
            }
            case CAMERA_SHAKE -> {
                Location l = player.getLocation();
                for (int i = 0; i < 10; i++) {
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        l.add(Math.random() * 0.2 - 0.1, Math.random() * 0.2 - 0.1, Math.random() * 0.2 - 0.1);
                        player.teleport(l);
                    }, i);
                }
            }
            case FADE_BLACK, FADE_WHITE -> {
                PotionEffectType effect = kf.type == KeyframeType.FADE_BLACK
                        ? PotionEffectType.BLINDNESS : PotionEffectType.GLOWING;
                player.addPotionEffect(new PotionEffect(effect,
                        (int)(kf.duration * 20), 1, false, false));
            }
            case LIGHTNING_STRIKE -> player.getWorld().strikeLightningEffect(player.getLocation());
            case EXPLOSION -> player.getWorld().createExplosion(player.getLocation(),
                    0, false, false);
            case TIME_SET -> player.getWorld().setTime((long)kf.duration);
            case WEATHER_SET -> {
                if (kf.text.equalsIgnoreCase("clear")) player.getWorld().setStorm(false);
                else if (kf.text.equalsIgnoreCase("rain")) player.getWorld().setStorm(true);
                else if (kf.text.equalsIgnoreCase("thunder")) {
                    player.getWorld().setStorm(true);
                    player.getWorld().setThundering(true);
                }
            }
        }
    }

    private void sendTitle(Player player, String title, String sub,
                           int fadeIn, int stay, int fadeOut) {
        if (title == null || title.isEmpty()) return;
        player.showTitle(Title.title(
                Component.text(title, NamedTextColor.WHITE).decorate(TextDecoration.BOLD),
                Component.text(sub != null ? sub : "", TextColor.color(0xCCCCCC)),
                Title.Times.times(
                        Duration.ofMillis(fadeIn * 50L),
                        Duration.ofMillis(stay   * 50L),
                        Duration.ofMillis(fadeOut * 50L))
        ));
    }

    private void endCinematic(Player player) {
        UUID uuid = player.getUniqueId();
        playingCinematic.remove(uuid);
        awaitingClick.remove(uuid);

        BukkitTask task = activeAnimations.remove(uuid);
        if (task != null) task.cancel();

        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.GLOWING);
        player.clearTitle();

        GameMode gm = originalGameModes.remove(uuid);
        player.setGameMode(gm != null ? gm : GameMode.SURVIVAL);

        Location saved = savedLocations.remove(uuid);
        if (saved != null) player.teleport(saved);

        Boolean flying = savedFlying.remove(uuid);
        if (flying != null && flying && player.getGameMode() == GameMode.CREATIVE) {
            player.setFlying(true);
        }
    }

    // ─────────────────────────────────────────────────
    // Path editor inner class
    // ─────────────────────────────────────────────────
    private static class PathEditor {
        final List<Location> waypoints = new ArrayList<>();
        void addWaypoint(Location loc) { waypoints.add(loc.clone()); }
    }

    // ─────────────────────────────────────────────────
    // Camera frame (position + rotation)
    // ─────────────────────────────────────────────────
    private static class CameraFrame {
        Location location;
        double speedMultiplier = 1.0;

        CameraFrame(Location loc) {
            this.location = loc;
        }
    }

    // ─────────────────────────────────────────────────
    // Keyframe types & class
    // ─────────────────────────────────────────────────
    private enum KeyframeType {
        TEXT_TITLE,
        TEXT_ACTIONBAR,
        TEXT_CHAT,
        PAUSE_TIMED,
        PAUSE_CLICK,
        SOUND,
        PARTICLE_BURST,
        CAMERA_SHAKE,      // NEW: shake camera
        FADE_BLACK,        // NEW: fade to black
        FADE_WHITE,        // NEW: fade to white
        LIGHTNING_STRIKE,  // NEW: lightning at camera
        EXPLOSION,         // NEW: explosion visual
        TIME_SET,          // NEW: set world time
        WEATHER_SET        // NEW: change weather
    }

    private static class Keyframe {
        KeyframeType type;
        int  waypointIndex;
        String text    = "";
        String subText = "";
        double duration = 0;
        int fadeIn  = 10;
        int stay    = 60;
        int fadeOut = 20;
        float pitch = 1.0f; // for sounds

        Keyframe(KeyframeType type, int waypointIndex) {
            this.type = type;
            this.waypointIndex = waypointIndex;
        }
    }

    // ─────────────────────────────────────────────────
    // Speed zone class
    // ─────────────────────────────────────────────────
    private static class SpeedZone {
        int startWaypoint;
        int endWaypoint;
        double speedMultiplier;

        SpeedZone(int start, int end, double speed) {
            this.startWaypoint = start;
            this.endWaypoint = end;
            this.speedMultiplier = speed;
        }
    }

    // ─────────────────────────────────────────────────
    // CinematicPath
    // ─────────────────────────────────────────────────
    private static class CinematicPath {
        String name;
        List<Location> waypoints;
        List<Keyframe> keyframes = new ArrayList<>();
        List<SpeedZone> speedZones = new ArrayList<>();
        Location startLocation;
        Location endLocation;
        boolean autoPlay;
        double  speed;
        String  title    = "";
        String  subtitle = "";

        CinematicPath(String name, List<Location> waypoints, boolean autoPlay, double speed) {
            this.name          = name;
            this.waypoints     = new ArrayList<>(waypoints);
            this.startLocation = waypoints.get(0).clone();
            this.endLocation   = waypoints.get(waypoints.size() - 1).clone();
            this.autoPlay      = autoPlay;
            this.speed         = Math.max(0.1, Math.min(5.0, speed));
        }

        int basePointsPerSegment() {
            return Math.max(10, (int)(60 / speed));
        }

        /**
         * Generate smooth camera path with ROTATION interpolation
         * and per-segment speed zones
         */
        List<CameraFrame> generateSmoothPath() {
            List<CameraFrame> smooth = new ArrayList<>();
            int basePoints = basePointsPerSegment();

            for (int i = 0; i < waypoints.size() - 1; i++) {
                Location p0 = i > 0 ? waypoints.get(i - 1) : waypoints.get(i);
                Location p1 = waypoints.get(i);
                Location p2 = waypoints.get(i + 1);
                Location p3 = i + 2 < waypoints.size() ? waypoints.get(i + 2) : waypoints.get(i + 1);

                // Check if this segment has a speed zone
                double segmentSpeed = 1.0;
                for (SpeedZone zone : speedZones) {
                    if (i >= zone.startWaypoint && i < zone.endWaypoint) {
                        segmentSpeed = zone.speedMultiplier;
                        break;
                    }
                }

                int pointsInSegment = (int)(basePoints / segmentSpeed);
                if (pointsInSegment < 2) pointsInSegment = 2;

                for (int j = 0; j < pointsInSegment; j++) {
                    double t = (double) j / pointsInSegment;

                    // Position interpolation (Catmull-Rom)
                    Location posLoc = catmullPosition(p0, p1, p2, p3, t);

                    // Rotation interpolation (spherical lerp)
                    float yaw = (float)lerpAngle(p1.getYaw(), p2.getYaw(), t);
                    float pitch = (float)lerp(p1.getPitch(), p2.getPitch(), t);

                    posLoc.setYaw(yaw);
                    posLoc.setPitch(pitch);

                    CameraFrame frame = new CameraFrame(posLoc);
                    frame.speedMultiplier = segmentSpeed;
                    smooth.add(frame);
                }
            }

            CameraFrame lastFrame = new CameraFrame(waypoints.get(waypoints.size() - 1).clone());
            smooth.add(lastFrame);
            return smooth;
        }

        private Location catmullPosition(Location p0, Location p1, Location p2, Location p3, double t) {
            double t2 = t * t, t3 = t2 * t;
            double x = 0.5 * ((2*p1.getX()) + (-p0.getX()+p2.getX())*t
                    + (2*p0.getX()-5*p1.getX()+4*p2.getX()-p3.getX())*t2
                    + (-p0.getX()+3*p1.getX()-3*p2.getX()+p3.getX())*t3);
            double y = 0.5 * ((2*p1.getY()) + (-p0.getY()+p2.getY())*t
                    + (2*p0.getY()-5*p1.getY()+4*p2.getY()-p3.getY())*t2
                    + (-p0.getY()+3*p1.getY()-3*p2.getY()+p3.getY())*t3);
            double z = 0.5 * ((2*p1.getZ()) + (-p0.getZ()+p2.getZ())*t
                    + (2*p0.getZ()-5*p1.getZ()+4*p2.getZ()-p3.getZ())*t2
                    + (-p0.getZ()+3*p1.getZ()-3*p2.getZ()+p3.getZ())*t3);
            return new Location(p1.getWorld(), x, y, z);
        }

        private double lerp(double a, double b, double t) {
            return a + (b - a) * t;
        }

        private double lerpAngle(double a, double b, double t) {
            double diff = ((b - a + 180) % 360) - 180;
            return a + diff * t;
        }
    }

    // ─────────────────────────────────────────────────
    // Commands
    // ─────────────────────────────────────────────────
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {

        if (cmd.getName().equalsIgnoreCase("cinematicwand")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only!"); return true; }
            ItemStack wand = new ItemStack(Material.BLAZE_ROD);
            ItemMeta  meta = wand.getItemMeta();
            meta.displayName(Component.text("Cinematic Wand", NamedTextColor.GOLD));
            meta.lore(List.of(
                    Component.text("Left Click: Add waypoint (saves rotation!)", NamedTextColor.GRAY),
                    Component.text("Right Click: Show info", NamedTextColor.GRAY),
                    Component.text("Shift + Right Click: Clear waypoints", NamedTextColor.GRAY)
            ));
            wand.setItemMeta(meta);
            player.getInventory().addItem(wand);
            player.sendMessage(Component.text("✓ Cinematic Wand given!", NamedTextColor.GREEN));
            return true;
        }

        if (!cmd.getName().equalsIgnoreCase("cinematic")) return false;
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only!"); return true; }
        if (args.length == 0) { sendHelp(player); return true; }

        switch (args[0].toLowerCase()) {

            case "save" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Usage: /cinematic save <name> <speed>", NamedTextColor.RED));
                    return true;
                }
                PathEditor editor = editors.get(player.getUniqueId());
                if (editor == null || editor.waypoints.size() < 2) {
                    player.sendMessage(Component.text("Need at least 2 waypoints!", NamedTextColor.RED));
                    return true;
                }
                String pathName = args[1];
                double speed = parseSpeed(args[2]);
                if (speed < 0) {
                    player.sendMessage(Component.text("Invalid speed!", NamedTextColor.RED));
                    return true;
                }
                CinematicPath path = new CinematicPath(pathName, editor.waypoints, true, speed);
                paths.put(pathName, path);
                editors.remove(player.getUniqueId());
                player.sendMessage(Component.text("✓ Path '" + pathName + "' saved ("
                        + editor.waypoints.size() + " waypoints, " + String.format("%.1fx", path.speed)
                        + ") — rotations preserved!", NamedTextColor.GREEN));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            }

            case "play" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Usage: /cinematic play <name>", NamedTextColor.RED));
                    return true;
                }
                CinematicPath path = paths.get(args[1]);
                if (path == null) {
                    player.sendMessage(Component.text("Path not found!", NamedTextColor.RED));
                    return true;
                }
                playCinematic(player, path);
            }

            case "stop" -> {
                if (!playingCinematic.contains(player.getUniqueId())) {
                    player.sendMessage(Component.text("You're not in a cinematic!", NamedTextColor.RED));
                    return true;
                }
                endCinematic(player);
                player.sendMessage(Component.text("■ Cinematic stopped.", NamedTextColor.GREEN));
            }

            case "delete", "remove" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Usage: /cinematic delete <name>", NamedTextColor.RED));
                    return true;
                }
                if (paths.remove(args[1]) != null)
                    player.sendMessage(Component.text("✓ Path '" + args[1] + "' deleted.", NamedTextColor.GREEN));
                else
                    player.sendMessage(Component.text("Path not found!", NamedTextColor.RED));
            }

            case "list" -> {
                if (paths.isEmpty()) {
                    player.sendMessage(Component.text("No paths saved.", NamedTextColor.GRAY));
                    return true;
                }
                player.sendMessage(sep());
                player.sendMessage(Component.text("  Cinematic Paths", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
                for (CinematicPath p : paths.values()) {
                    player.sendMessage(Component.text("  • " + p.name + " — " + p.waypoints.size() + " pts, "
                            + String.format("%.1fx", p.speed) + ", "
                            + p.keyframes.size() + " kf, "
                            + p.speedZones.size() + " zones, "
                            + (p.autoPlay ? "auto" : "manual"), NamedTextColor.YELLOW));
                }
                player.sendMessage(sep());
            }

            case "setspeed" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Usage: /cinematic setspeed <name> <speed>", NamedTextColor.RED));
                    return true;
                }
                CinematicPath path = paths.get(args[1]);
                if (path == null) {
                    player.sendMessage(Component.text("Path not found!", NamedTextColor.RED));
                    return true;
                }
                double speed = parseSpeed(args[2]);
                if (speed < 0) {
                    player.sendMessage(Component.text("Invalid speed!", NamedTextColor.RED));
                    return true;
                }
                path.speed = speed;
                player.sendMessage(Component.text("✓ Base speed for '" + args[1] + "' → "
                        + String.format("%.1fx", speed), NamedTextColor.GREEN));
            }

            case "toggle" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Usage: /cinematic toggle <name>", NamedTextColor.RED));
                    return true;
                }
                CinematicPath path = paths.get(args[1]);
                if (path == null) {
                    player.sendMessage(Component.text("Path not found!", NamedTextColor.RED));
                    return true;
                }
                path.autoPlay = !path.autoPlay;
                player.sendMessage(Component.text("✓ Auto-play for '" + args[1] + "': "
                        + (path.autoPlay ? "ON" : "OFF"), NamedTextColor.GREEN));
            }

            case "clear" -> {
                editors.remove(player.getUniqueId());
                player.sendMessage(Component.text("✓ Waypoints cleared.", NamedTextColor.GREEN));
            }

            case "settitle" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Usage: /cinematic settitle <path> <title> [| subtitle]", NamedTextColor.RED));
                    return true;
                }
                CinematicPath path = paths.get(args[1]);
                if (path == null) {
                    player.sendMessage(Component.text("Path not found!", NamedTextColor.RED));
                    return true;
                }
                String full = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                String[] parts = full.split("\\|", 2);
                path.title    = parts[0].trim();
                path.subtitle = parts.length > 1 ? parts[1].trim() : "";
                player.sendMessage(Component.text("✓ Intro title set.", NamedTextColor.GREEN));
            }

            // ── NEW: Speed zones ──────────────────────────
            case "addzone", "speedzone" -> {
                // /cinematic addzone <path> <startWP> <endWP> <speed>
                if (args.length < 5) {
                    player.sendMessage(Component.text("Usage: /cinematic addzone <path> <startWP> <endWP> <speed>",
                            NamedTextColor.RED));
                    player.sendMessage(Component.text("Example: /cinematic addzone mypath 0 5 0.5  (slow first half)",
                            NamedTextColor.GRAY));
                    return true;
                }
                CinematicPath path = paths.get(args[1]);
                if (path == null) {
                    player.sendMessage(Component.text("Path not found!", NamedTextColor.RED));
                    return true;
                }
                try {
                    int start = Integer.parseInt(args[2]);
                    int end = Integer.parseInt(args[3]);
                    double zoneSpeed = parseSpeed(args[4]);
                    if (zoneSpeed < 0) throw new NumberFormatException();

                    SpeedZone zone = new SpeedZone(start, end, zoneSpeed);
                    path.speedZones.add(zone);
                    player.sendMessage(Component.text("✓ Speed zone added: WP " + start + "-" + end
                            + " at " + String.format("%.1fx", zoneSpeed), NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    player.sendMessage(Component.text("Invalid numbers!", NamedTextColor.RED));
                }
            }

            case "listzones" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Usage: /cinematic listzones <path>", NamedTextColor.RED));
                    return true;
                }
                CinematicPath path = paths.get(args[1]);
                if (path == null) {
                    player.sendMessage(Component.text("Path not found!", NamedTextColor.RED));
                    return true;
                }
                if (path.speedZones.isEmpty()) {
                    player.sendMessage(Component.text("No speed zones on '" + args[1] + "'.", NamedTextColor.GRAY));
                    return true;
                }
                player.sendMessage(sep());
                for (int i = 0; i < path.speedZones.size(); i++) {
                    SpeedZone z = path.speedZones.get(i);
                    player.sendMessage(Component.text("  [" + i + "] WP " + z.startWaypoint + "-" + z.endWaypoint
                            + " → " + String.format("%.1fx", z.speedMultiplier), NamedTextColor.YELLOW));
                }
                player.sendMessage(sep());
            }

            case "removezone" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Usage: /cinematic removezone <path> <index>", NamedTextColor.RED));
                    return true;
                }
                CinematicPath path = paths.get(args[1]);
                if (path == null) {
                    player.sendMessage(Component.text("Path not found!", NamedTextColor.RED));
                    return true;
                }
                try {
                    int idx = Integer.parseInt(args[2]);
                    path.speedZones.remove(idx);
                    player.sendMessage(Component.text("✓ Speed zone " + idx + " removed.", NamedTextColor.GREEN));
                } catch (Exception e) {
                    player.sendMessage(Component.text("Invalid index!", NamedTextColor.RED));
                }
            }

            case "addkeyframe", "addevent" -> {
                if (args.length < 4) {
                    player.sendMessage(Component.text("Usage: /cinematic addkeyframe <path> <waypointIdx> <type> [args]",
                            NamedTextColor.RED));
                    sendKeyframeHelp(player);
                    return true;
                }
                CinematicPath path = paths.get(args[1]);
                if (path == null) {
                    player.sendMessage(Component.text("Path not found!", NamedTextColor.RED));
                    return true;
                }

                int wpIdx;
                try { wpIdx = Integer.parseInt(args[2]); }
                catch (NumberFormatException e) {
                    player.sendMessage(Component.text("Waypoint index must be a number.", NamedTextColor.RED));
                    return true;
                }

                if (wpIdx < 0 || wpIdx >= path.waypoints.size()) {
                    player.sendMessage(Component.text("Waypoint index out of range (0-"
                            + (path.waypoints.size()-1) + ")", NamedTextColor.RED));
                    return true;
                }

                String kfTypeName = args[3].toLowerCase();
                Keyframe kf = switch (kfTypeName) {
                    case "title" -> {
                        if (args.length < 8) {
                            player.sendMessage(Component.text("Usage: … title <fadeIn> <stay> <fadeOut> <title> [| subtitle]",
                                    NamedTextColor.RED));
                            yield null;
                        }
                        Keyframe k = new Keyframe(KeyframeType.TEXT_TITLE, wpIdx);
                        try {
                            k.fadeIn  = Integer.parseInt(args[4]);
                            k.stay    = Integer.parseInt(args[5]);
                            k.fadeOut = Integer.parseInt(args[6]);
                        } catch (NumberFormatException e) {
                            player.sendMessage(Component.text("fadeIn/stay/fadeOut must be integers (ticks).",
                                    NamedTextColor.RED));
                            yield null;
                        }
                        String full = String.join(" ", Arrays.copyOfRange(args, 7, args.length));
                        String[] parts = full.split("\\|", 2);
                        k.text    = parts[0].trim();
                        k.subText = parts.length > 1 ? parts[1].trim() : "";
                        yield k;
                    }
                    case "actionbar" -> {
                        if (args.length < 5) {
                            player.sendMessage(Component.text("Usage: … actionbar <text>", NamedTextColor.RED));
                            yield null;
                        }
                        Keyframe k = new Keyframe(KeyframeType.TEXT_ACTIONBAR, wpIdx);
                        k.text = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                        yield k;
                    }
                    case "chat" -> {
                        if (args.length < 5) {
                            player.sendMessage(Component.text("Usage: … chat <text>", NamedTextColor.RED));
                            yield null;
                        }
                        Keyframe k = new Keyframe(KeyframeType.TEXT_CHAT, wpIdx);
                        k.text = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                        yield k;
                    }
                    case "pause" -> {
                        if (args.length < 5) {
                            player.sendMessage(Component.text("Usage: … pause <seconds>", NamedTextColor.RED));
                            yield null;
                        }
                        Keyframe k = new Keyframe(KeyframeType.PAUSE_TIMED, wpIdx);
                        try { k.duration = Double.parseDouble(args[4]); }
                        catch (NumberFormatException e) {
                            player.sendMessage(Component.text("Seconds must be a number.", NamedTextColor.RED));
                            yield null;
                        }
                        yield k;
                    }
                    case "click" -> new Keyframe(KeyframeType.PAUSE_CLICK, wpIdx);
                    case "sound" -> {
                        if (args.length < 5) {
                            player.sendMessage(Component.text("Usage: … sound <SOUND_NAME> [volume] [pitch]",
                                    NamedTextColor.RED));
                            yield null;
                        }
                        Keyframe k = new Keyframe(KeyframeType.SOUND, wpIdx);
                        k.text = args[4].toUpperCase();
                        if (args.length >= 6) {
                            try { k.duration = Double.parseDouble(args[5]); }
                            catch (NumberFormatException ignored) {}
                        }
                        if (args.length >= 7) {
                            try { k.pitch = Float.parseFloat(args[6]); }
                            catch (NumberFormatException ignored) {}
                        }
                        yield k;
                    }
                    case "particle", "burst" -> new Keyframe(KeyframeType.PARTICLE_BURST, wpIdx);
                    case "shake" -> {
                        Keyframe k = new Keyframe(KeyframeType.CAMERA_SHAKE, wpIdx);
                        if (args.length >= 5) {
                            try { k.duration = Double.parseDouble(args[4]); }
                            catch (NumberFormatException ignored) {}
                        }
                        yield k;
                    }
                    case "fadeblack" -> {
                        Keyframe k = new Keyframe(KeyframeType.FADE_BLACK, wpIdx);
                        if (args.length >= 5) {
                            try { k.duration = Double.parseDouble(args[4]); }
                            catch (NumberFormatException ignored) { k.duration = 2.0; }
                        } else k.duration = 2.0;
                        yield k;
                    }
                    case "fadewhite" -> {
                        Keyframe k = new Keyframe(KeyframeType.FADE_WHITE, wpIdx);
                        if (args.length >= 5) {
                            try { k.duration = Double.parseDouble(args[4]); }
                            catch (NumberFormatException ignored) { k.duration = 2.0; }
                        } else k.duration = 2.0;
                        yield k;
                    }
                    case "lightning" -> new Keyframe(KeyframeType.LIGHTNING_STRIKE, wpIdx);
                    case "explosion" -> new Keyframe(KeyframeType.EXPLOSION, wpIdx);
                    case "time" -> {
                        if (args.length < 5) {
                            player.sendMessage(Component.text("Usage: … time <0-24000>", NamedTextColor.RED));
                            yield null;
                        }
                        Keyframe k = new Keyframe(KeyframeType.TIME_SET, wpIdx);
                        try { k.duration = Double.parseDouble(args[4]); }
                        catch (NumberFormatException e) {
                            player.sendMessage(Component.text("Time must be 0-24000", NamedTextColor.RED));
                            yield null;
                        }
                        yield k;
                    }
                    case "weather" -> {
                        if (args.length < 5) {
                            player.sendMessage(Component.text("Usage: … weather <clear|rain|thunder>", NamedTextColor.RED));
                            yield null;
                        }
                        Keyframe k = new Keyframe(KeyframeType.WEATHER_SET, wpIdx);
                        k.text = args[4].toLowerCase();
                        yield k;
                    }
                    default -> {
                        player.sendMessage(Component.text("Unknown keyframe type: " + kfTypeName, NamedTextColor.RED));
                        sendKeyframeHelp(player);
                        yield null;
                    }
                };

                if (kf == null) return true;
                path.keyframes.add(kf);
                path.keyframes.sort(Comparator.comparingInt(k -> k.waypointIndex));
                player.sendMessage(Component.text("✓ Keyframe '" + kfTypeName + "' added at waypoint " + wpIdx + ".",
                        NamedTextColor.GREEN));
            }

            case "listkeyframes", "events" -> {
                if (args.length < 2) {
                    player.sendMessage(Component.text("Usage: /cinematic listkeyframes <path>", NamedTextColor.RED));
                    return true;
                }
                CinematicPath path = paths.get(args[1]);
                if (path == null) {
                    player.sendMessage(Component.text("Path not found!", NamedTextColor.RED));
                    return true;
                }
                if (path.keyframes.isEmpty()) {
                    player.sendMessage(Component.text("No keyframes on '" + args[1] + "'.", NamedTextColor.GRAY));
                    return true;
                }
                player.sendMessage(sep());
                for (int i = 0; i < path.keyframes.size(); i++) {
                    Keyframe kf = path.keyframes.get(i);
                    player.sendMessage(Component.text("  [" + i + "] wp#" + kf.waypointIndex
                                    + " " + kf.type.name().toLowerCase()
                                    + (kf.text.isEmpty() ? "" : " → " + kf.text.substring(0, Math.min(kf.text.length(), 30))),
                            NamedTextColor.YELLOW));
                }
                player.sendMessage(sep());
            }

            case "removekeyframe" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Usage: /cinematic removekeyframe <path> <index>",
                            NamedTextColor.RED));
                    return true;
                }
                CinematicPath path = paths.get(args[1]);
                if (path == null) {
                    player.sendMessage(Component.text("Path not found!", NamedTextColor.RED));
                    return true;
                }
                try {
                    int idx = Integer.parseInt(args[2]);
                    path.keyframes.remove(idx);
                    player.sendMessage(Component.text("✓ Keyframe " + idx + " removed.", NamedTextColor.GREEN));
                } catch (Exception e) {
                    player.sendMessage(Component.text("Invalid index.", NamedTextColor.RED));
                }
            }

            case "reload" -> {
                if (!player.hasPermission("cinematic.admin")) {
                    player.sendMessage(Component.text("No permission!", NamedTextColor.RED));
                    return true;
                }
                reloadConfig();
                player.sendMessage(Component.text("✓ Plugin reloaded!", NamedTextColor.GREEN));
            }

            default -> sendHelp(player);
        }
        return true;
    }

    // ─────────────────────────────────────────────────
    // Tab completion
    // ─────────────────────────────────────────────────
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!cmd.getName().equalsIgnoreCase("cinematic")) return null;

        if (args.length == 1)
            return List.of("save","play","stop","delete","list","setspeed","toggle","clear",
                    "settitle","addkeyframe","listkeyframes","removekeyframe","addzone",
                    "listzones","removezone","reload");

        String sub = args[0].toLowerCase();
        List<String> pathNames = new ArrayList<>(paths.keySet());

        if (args.length == 2 && List.of("play","delete","toggle","setspeed","settitle",
                "addkeyframe","listkeyframes","removekeyframe","addzone","listzones","removezone").contains(sub))
            return pathNames;

        if (args.length == 3 && List.of("setspeed","save","addzone").contains(sub))
            return List.of("slow","normal","fast","veryfast","0.5","1.0","2.0","3.5");

        if (args.length == 3 && sub.equals("addkeyframe"))
            return List.of("0","1","2","3","4","5");

        if (args.length == 4 && sub.equals("addkeyframe"))
            return List.of("title","actionbar","chat","pause","click","sound","particle",
                    "shake","fadeblack","fadewhite","lightning","explosion","time","weather");

        if (args.length == 5 && sub.equals("addkeyframe") && args[3].equalsIgnoreCase("weather"))
            return List.of("clear","rain","thunder");

        return null;
    }

    // ─────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────
    private static double parseSpeed(String s) {
        return switch (s.toLowerCase()) {
            case "slow", "cinematic" -> 0.3;
            case "normal", "medium"  -> 1.0;
            case "fast"              -> 2.0;
            case "veryfast", "rapid" -> 3.5;
            default -> {
                try { yield Math.max(0.1, Math.min(5.0, Double.parseDouble(s))); }
                catch (NumberFormatException e) { yield -1; }
            }
        };
    }

    private static Sound parseSound(String name) {
        try {
            return Registry.SOUNDS.get(NamespacedKey.minecraft(name.toLowerCase()));
        } catch (Exception e) {
            return Sound.BLOCK_NOTE_BLOCK_PLING;
        }
    }

    private static boolean isNearby(Location a, Location b, double dist) {
        return a.getWorld().equals(b.getWorld()) && a.distance(b) <= dist;
    }

    private static String fmt(double v) { return String.format("%.1f", v); }

    private static Component sep() {
        return Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY);
    }

    private void sendHelp(Player p) {
        p.sendMessage(sep());
        p.sendMessage(Component.text("  Cinematic Camera PRO", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        p.sendMessage(sep());
        p.sendMessage(Component.text("  /cinematicwand", NamedTextColor.YELLOW)
                .append(Component.text(" — get wand (saves rotation!)", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /cinematic save <n> <speed>", NamedTextColor.YELLOW)
                .append(Component.text(" — save path", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /cinematic play <n>", NamedTextColor.YELLOW)
                .append(Component.text(" — play path", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /cinematic stop", NamedTextColor.YELLOW)
                .append(Component.text(" — stop cinematic", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /cinematic list", NamedTextColor.YELLOW)
                .append(Component.text(" — list paths", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /cinematic setspeed <n> <speed>", NamedTextColor.YELLOW)
                .append(Component.text(" — change base speed", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /cinematic addzone <n> <start> <end> <speed>", NamedTextColor.YELLOW)
                .append(Component.text(" — add speed zone", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /cinematic listzones <n>", NamedTextColor.YELLOW)
                .append(Component.text(" — list speed zones", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /cinematic settitle <n> <title> [| sub]", NamedTextColor.YELLOW)
                .append(Component.text(" — set intro", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /cinematic addkeyframe <n> <wp> <type> […]", NamedTextColor.YELLOW)
                .append(Component.text(" — add effect", NamedTextColor.GRAY)));
        p.sendMessage(Component.text("  /cinematic listkeyframes <n>", NamedTextColor.YELLOW)
                .append(Component.text(" — list effects", NamedTextColor.GRAY)));
        p.sendMessage(sep());
        p.sendMessage(Component.text("  NEW: Camera rotation is preserved per waypoint!", NamedTextColor.AQUA));
        p.sendMessage(Component.text("  NEW: Speed zones let you vary speed during playback!", NamedTextColor.AQUA));
        p.sendMessage(sep());
    }

    private void sendKeyframeHelp(Player p) {
        p.sendMessage(sep());
        p.sendMessage(Component.text("  Keyframe types:", NamedTextColor.GOLD));
        p.sendMessage(Component.text("  title <fadeIn> <stay> <out> <text> [| sub]", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  actionbar <text…>", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  chat <text…>", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  pause <seconds>", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  click", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  sound <NAME> [vol] [pitch]", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  particle", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  shake [intensity]", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  fadeblack <duration>", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  fadewhite <duration>", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  lightning", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  explosion", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  time <0-24000>", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("  weather <clear|rain|thunder>", NamedTextColor.YELLOW));
        p.sendMessage(sep());
    }
}