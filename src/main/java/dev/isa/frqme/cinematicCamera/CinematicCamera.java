package dev.isa.frqme.cinematicCamera;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class CinematicCamera extends JavaPlugin implements Listener {

    private final Map<String, CinematicPath> paths = new HashMap<>();
    private final Map<UUID, PathEditor> editors = new HashMap<>();
    private final Set<UUID> playingCinematic = new HashSet<>();
    private final Map<UUID, GameMode> originalGameModes = new HashMap<>();
    private final Map<UUID, BukkitRunnable> activeAnimations = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Cinematic Camera plugin enabled!");
    }

    @Override
    public void onDisable() {
        // Stop all active cinematics
        for (UUID uuid : new HashSet<>(playingCinematic)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                endCinematic(player);
            }
        }
        getLogger().info("Cinematic Camera plugin disabled!");
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || item.getType() != Material.BLAZE_ROD) return;
        if (!item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta.displayName() == null) return;

        String displayName = ((net.kyori.adventure.text.TextComponent) meta.displayName()).content();
        if (!displayName.equals("Cinematic Wand")) return;

        event.setCancelled(true);

        Action action = event.getAction();
        Location loc = player.getLocation().clone();

        if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) {
            // Add waypoint
            PathEditor editor = editors.computeIfAbsent(player.getUniqueId(), k -> new PathEditor());
            editor.addWaypoint(loc);
            player.sendMessage(Component.text("✓ Waypoint #" + editor.waypoints.size() + " added at " +
                    String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ()), NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);

            // Visual feedback - spawn temporary particle
            player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc, 10, 0.3, 0.3, 0.3);

        } else if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
            if (player.isSneaking()) {
                // Clear waypoints
                PathEditor editor = editors.remove(player.getUniqueId());
                if (editor != null) {
                    player.sendMessage(Component.text("✗ " + editor.waypoints.size() + " waypoints cleared!", NamedTextColor.RED));
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                } else {
                    player.sendMessage(Component.text("No waypoints to clear!", NamedTextColor.GRAY));
                }
            } else {
                // Preview or save prompt
                PathEditor editor = editors.get(player.getUniqueId());
                if (editor == null || editor.waypoints.size() < 2) {
                    player.sendMessage(Component.text("You need at least 2 waypoints! Current: " +
                            (editor != null ? editor.waypoints.size() : 0), NamedTextColor.RED));
                    return;
                }

                player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GRAY));
                player.sendMessage(Component.text("Current path has " + editor.waypoints.size() + " waypoints", NamedTextColor.YELLOW));
                player.sendMessage(Component.text("Use: /cinematic save <name> <speed>", NamedTextColor.GOLD));
                player.sendMessage(Component.text("Speeds: slow, normal, fast, or custom (0.1-5.0)", NamedTextColor.GRAY));
                player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GRAY));
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // Block movement during cinematic
        if (playingCinematic.contains(player.getUniqueId())) {
            Location from = event.getFrom();
            Location to = event.getTo();

            if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
                event.setCancelled(true);
            }
            return;
        }

        // Check if player stepped on a start location
        Location playerLoc = player.getLocation();
        for (CinematicPath path : paths.values()) {
            if (path.autoPlay && path.startLocation != null && isNearby(playerLoc, path.startLocation, 1.5)) {
                if (!playingCinematic.contains(player.getUniqueId())) {
                    playCinematic(player, path);
                    break;
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        editors.remove(event.getPlayer().getUniqueId());
        endCinematic(event.getPlayer());
    }

    private boolean isNearby(Location loc1, Location loc2, double distance) {
        if (!loc1.getWorld().equals(loc2.getWorld())) return false;
        return loc1.distance(loc2) <= distance;
    }

    public void playCinematic(Player player, CinematicPath path) {
        if (playingCinematic.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("You're already in a cinematic!", NamedTextColor.RED));
            return;
        }

        playingCinematic.add(player.getUniqueId());

        // Store original gamemode
        originalGameModes.put(player.getUniqueId(), player.getGameMode());

        // Set to spectator mode
        player.setGameMode(GameMode.SPECTATOR);

        // Add invisibility effect without particles or icon
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 999999, 0, false, false, false));

        // Generate smooth path based on speed
        List<Location> smoothPath = path.generateSmoothPath();

        player.sendMessage(Component.text("✦ Cinematic started (Speed: " +
                String.format("%.1fx", path.speed) + ")...", NamedTextColor.AQUA));

        BukkitRunnable animation = new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                if (!player.isOnline() || index >= smoothPath.size()) {
                    endCinematic(player);

                    // Teleport to end location
                    if (path.endLocation != null) {
                        player.teleport(path.endLocation);
                    }

                    player.sendMessage(Component.text("✦ Cinematic ended", NamedTextColor.GREEN));
                    cancel();
                    activeAnimations.remove(player.getUniqueId());
                    return;
                }

                Location current = smoothPath.get(index);

                // Look ahead for smoother camera rotation
                int lookAheadDistance = Math.min(8, smoothPath.size() - index - 1);
                Location lookTarget = index + lookAheadDistance < smoothPath.size()
                        ? smoothPath.get(index + lookAheadDistance)
                        : current;

                // Calculate smooth direction
                Vector direction = lookTarget.toVector().subtract(current.toVector());

                if (direction.lengthSquared() > 0.001) {
                    direction.normalize();
                    Location smoothLoc = current.clone();
                    smoothLoc.setDirection(direction);
                    player.teleport(smoothLoc);
                } else {
                    player.teleport(current);
                }

                index++;
            }
        };

        animation.runTaskTimer(this, 0L, 1L);
        activeAnimations.put(player.getUniqueId(), animation);
    }

    private void endCinematic(Player player) {
        UUID uuid = player.getUniqueId();
        playingCinematic.remove(uuid);

        // Cancel animation if running
        BukkitRunnable animation = activeAnimations.remove(uuid);
        if (animation != null) {
            animation.cancel();
        }

        // Remove invisibility effect
        player.removePotionEffect(PotionEffectType.INVISIBILITY);

        // Restore original gamemode
        GameMode originalMode = originalGameModes.remove(uuid);
        if (originalMode != null) {
            player.setGameMode(originalMode);
        } else {
            player.setGameMode(GameMode.SURVIVAL);
        }
    }

    // Inner Classes
    private static class PathEditor {
        List<Location> waypoints = new ArrayList<>();

        void addWaypoint(Location loc) {
            waypoints.add(loc.clone());
        }
    }

    private static class CinematicPath {
        String name;
        List<Location> waypoints;
        Location startLocation;
        Location endLocation;
        boolean autoPlay;
        double speed; // Speed multiplier (0.1 = very slow, 1.0 = normal, 5.0 = very fast)

        CinematicPath(String name, List<Location> waypoints, boolean autoPlay, double speed) {
            this.name = name;
            this.waypoints = new ArrayList<>(waypoints);
            this.startLocation = waypoints.get(0).clone();
            this.endLocation = waypoints.get(waypoints.size() - 1).clone();
            this.autoPlay = autoPlay;
            this.speed = Math.max(0.1, Math.min(5.0, speed)); // Clamp between 0.1 and 5.0
        }

        List<Location> generateSmoothPath() {
            List<Location> smoothPath = new ArrayList<>();

            // Calculate points based on speed
            // Lower speed = more points = slower movement
            // Base: 60 points per segment for smooth movement
            int basePointsPerSegment = 60;
            int pointsPerSegment = (int) (basePointsPerSegment / speed);

            // Ensure minimum smoothness
            pointsPerSegment = Math.max(10, pointsPerSegment);

            for (int i = 0; i < waypoints.size() - 1; i++) {
                Location p0 = i > 0 ? waypoints.get(i - 1) : waypoints.get(i);
                Location p1 = waypoints.get(i);
                Location p2 = waypoints.get(i + 1);
                Location p3 = i + 2 < waypoints.size() ? waypoints.get(i + 2) : waypoints.get(i + 1);

                for (int j = 0; j < pointsPerSegment; j++) {
                    double t = (double) j / pointsPerSegment;
                    Location interpolated = catmullRomSpline(p0, p1, p2, p3, t);
                    smoothPath.add(interpolated);
                }
            }

            smoothPath.add(waypoints.get(waypoints.size() - 1).clone());
            return smoothPath;
        }

        private Location catmullRomSpline(Location p0, Location p1, Location p2, Location p3, double t) {
            double t2 = t * t;
            double t3 = t2 * t;

            double x = 0.5 * ((2 * p1.getX()) +
                    (-p0.getX() + p2.getX()) * t +
                    (2 * p0.getX() - 5 * p1.getX() + 4 * p2.getX() - p3.getX()) * t2 +
                    (-p0.getX() + 3 * p1.getX() - 3 * p2.getX() + p3.getX()) * t3);

            double y = 0.5 * ((2 * p1.getY()) +
                    (-p0.getY() + p2.getY()) * t +
                    (2 * p0.getY() - 5 * p1.getY() + 4 * p2.getY() - p3.getY()) * t2 +
                    (-p0.getY() + 3 * p1.getY() - 3 * p2.getY() + p3.getY()) * t3);

            double z = 0.5 * ((2 * p1.getZ()) +
                    (-p0.getZ() + p2.getZ()) * t +
                    (2 * p0.getZ() - 5 * p1.getZ() + 4 * p2.getZ() - p3.getZ()) * t2 +
                    (-p0.getZ() + 3 * p1.getZ() - 3 * p2.getZ() + p3.getZ()) * t3);

            return new Location(p1.getWorld(), x, y, z);
        }
    }

    // Commands
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("cinematicwand")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can use this command!", NamedTextColor.RED));
                return true;
            }

            ItemStack wand = new ItemStack(Material.BLAZE_ROD);
            ItemMeta meta = wand.getItemMeta();
            meta.displayName(Component.text("Cinematic Wand", NamedTextColor.GOLD));
            meta.lore(List.of(
                    Component.text("Left Click: Add waypoint", NamedTextColor.GRAY),
                    Component.text("Right Click: Show info", NamedTextColor.GRAY),
                    Component.text("Shift + Right Click: Clear waypoints", NamedTextColor.GRAY)
            ));
            wand.setItemMeta(meta);

            player.getInventory().addItem(wand);
            player.sendMessage(Component.text("✓ Cinematic Wand given!", NamedTextColor.GREEN));
            return true;
        }

        if (command.getName().equalsIgnoreCase("cinematic")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can use this command!", NamedTextColor.RED));
                return true;
            }

            if (args.length == 0) {
                sendHelp(player);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "save" -> {
                    if (args.length < 3) {
                        player.sendMessage(Component.text("Usage: /cinematic save <name> <speed>", NamedTextColor.RED));
                        player.sendMessage(Component.text("Speeds: slow (0.3), normal (1.0), fast (2.0), veryfast (3.5)", NamedTextColor.GRAY));
                        player.sendMessage(Component.text("Or use a custom number (0.1 - 5.0)", NamedTextColor.GRAY));
                        return true;
                    }

                    PathEditor editor = editors.get(player.getUniqueId());
                    if (editor == null || editor.waypoints.size() < 2) {
                        player.sendMessage(Component.text("You need at least 2 waypoints!", NamedTextColor.RED));
                        return true;
                    }

                    String pathName = args[1];
                    double speed;

                    // Parse speed parameter
                    switch (args[2].toLowerCase()) {
                        case "slow", "cinematic" -> speed = 0.3;
                        case "normal", "medium" -> speed = 1.0;
                        case "fast" -> speed = 2.0;
                        case "veryfast", "rapid" -> speed = 3.5;
                        default -> {
                            try {
                                speed = Double.parseDouble(args[2]);
                            } catch (NumberFormatException e) {
                                player.sendMessage(Component.text("Invalid speed! Use slow/normal/fast/veryfast or a number (0.1-5.0)", NamedTextColor.RED));
                                return true;
                            }
                        }
                    }

                    CinematicPath path = new CinematicPath(pathName, editor.waypoints, true, speed);
                    paths.put(pathName, path);

                    editors.remove(player.getUniqueId());

                    player.sendMessage(Component.text("✓ Path '" + pathName + "' saved with " +
                            editor.waypoints.size() + " waypoints at " +
                            String.format("%.1fx", path.speed) + " speed!", NamedTextColor.GREEN));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                }

                case "setspeed" -> {
                    if (args.length < 3) {
                        player.sendMessage(Component.text("Usage: /cinematic setspeed <name> <speed>", NamedTextColor.RED));
                        return true;
                    }

                    String pathName = args[1];
                    CinematicPath path = paths.get(pathName);

                    if (path == null) {
                        player.sendMessage(Component.text("Path '" + pathName + "' not found!", NamedTextColor.RED));
                        return true;
                    }

                    double speed;
                    switch (args[2].toLowerCase()) {
                        case "slow", "cinematic" -> speed = 0.3;
                        case "normal", "medium" -> speed = 1.0;
                        case "fast" -> speed = 2.0;
                        case "veryfast", "rapid" -> speed = 3.5;
                        default -> {
                            try {
                                speed = Double.parseDouble(args[2]);
                            } catch (NumberFormatException e) {
                                player.sendMessage(Component.text("Invalid speed!", NamedTextColor.RED));
                                return true;
                            }
                        }
                    }

                    path.speed = Math.max(0.1, Math.min(5.0, speed));
                    player.sendMessage(Component.text("✓ Speed for '" + pathName + "' set to " +
                            String.format("%.1fx", path.speed), NamedTextColor.GREEN));
                }

                case "delete", "remove" -> {
                    if (args.length < 2) {
                        player.sendMessage(Component.text("Usage: /cinematic delete <name>", NamedTextColor.RED));
                        return true;
                    }

                    String pathName = args[1];
                    if (paths.remove(pathName) != null) {
                        player.sendMessage(Component.text("✓ Path '" + pathName + "' deleted!", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Path '" + pathName + "' not found!", NamedTextColor.RED));
                    }
                }

                case "list" -> {
                    if (paths.isEmpty()) {
                        player.sendMessage(Component.text("No cinematic paths saved!", NamedTextColor.GRAY));
                        return true;
                    }

                    player.sendMessage(Component.text("━━━━ Cinematic Paths ━━━━", NamedTextColor.GOLD));
                    for (CinematicPath path : paths.values()) {
                        player.sendMessage(Component.text("• " + path.name + " (" + path.waypoints.size() +
                                " waypoints, " + String.format("%.1fx", path.speed) + " speed) [" +
                                (path.autoPlay ? "Auto" : "Manual") + "]", NamedTextColor.YELLOW));
                    }
                }

                case "play" -> {
                    if (args.length < 2) {
                        player.sendMessage(Component.text("Usage: /cinematic play <name>", NamedTextColor.RED));
                        return true;
                    }

                    String pathName = args[1];
                    CinematicPath path = paths.get(pathName);

                    if (path == null) {
                        player.sendMessage(Component.text("Path '" + pathName + "' not found!", NamedTextColor.RED));
                        return true;
                    }

                    playCinematic(player, path);
                }

                case "stop" -> {
                    if (playingCinematic.contains(player.getUniqueId())) {
                        endCinematic(player);
                        player.sendMessage(Component.text("✓ Cinematic stopped!", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("You're not in a cinematic!", NamedTextColor.RED));
                    }
                }

                case "toggle" -> {
                    if (args.length < 2) {
                        player.sendMessage(Component.text("Usage: /cinematic toggle <name>", NamedTextColor.RED));
                        return true;
                    }

                    String pathName = args[1];
                    CinematicPath path = paths.get(pathName);

                    if (path == null) {
                        player.sendMessage(Component.text("Path '" + pathName + "' not found!", NamedTextColor.RED));
                        return true;
                    }

                    path.autoPlay = !path.autoPlay;
                    player.sendMessage(Component.text("✓ Auto-play for '" + pathName + "' is now " +
                            (path.autoPlay ? "enabled" : "disabled"), NamedTextColor.GREEN));
                }

                case "clear" -> {
                    editors.remove(player.getUniqueId());
                    player.sendMessage(Component.text("✓ Your waypoint selection cleared!", NamedTextColor.GREEN));
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

        return false;
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("━━━━ Cinematic Camera Commands ━━━━", NamedTextColor.GOLD));
        player.sendMessage(Component.text("/cinematicwand - Get the wand", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/cinematic save <name> <speed> - Save path", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/cinematic setspeed <name> <speed> - Change speed", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/cinematic delete <name> - Delete path", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/cinematic list - List all paths", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/cinematic play <name> - Play a path", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/cinematic stop - Stop current cinematic", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/cinematic toggle <name> - Toggle auto-play", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/cinematic clear - Clear waypoints", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Speed presets: slow (0.3x), normal (1.0x), fast (2.0x), veryfast (3.5x)", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Or use any number from 0.1 to 5.0", NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("cinematic")) {
            if (args.length == 1) {
                return List.of("save", "setspeed", "delete", "list", "play", "stop", "toggle", "clear", "reload");
            } else if (args.length == 2 && (args[0].equalsIgnoreCase("delete") ||
                    args[0].equalsIgnoreCase("play") ||
                    args[0].equalsIgnoreCase("toggle") ||
                    args[0].equalsIgnoreCase("setspeed"))) {
                return new ArrayList<>(paths.keySet());
            } else if (args.length == 3 && (args[0].equalsIgnoreCase("save") || args[0].equalsIgnoreCase("setspeed"))) {
                return List.of("slow", "normal", "fast", "veryfast", "0.5", "1.0", "2.0");
            }
        }
        return null;
    }
}