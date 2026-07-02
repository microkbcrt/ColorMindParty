package com.verdae.colormindparty;

import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import io.papermc.paper.math.Position;
import org.bukkit.Difficulty;
import org.bukkit.HeightMap;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * ColorMindParty - 小型独立色盲派对 Paper 插件。
 *
 * 文件数量刻意压到最低：主逻辑都在这个 Java 文件里。
 * 目标：Paper 26.2 alpha API；尽量只使用 Bukkit/Paper 稳定 API。
 */
public final class ColorMindPartyPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final int EDIT_PLATFORM_Y = 80;
    private static final int EDIT_PLATFORM_RADIUS = 4;
    private static final ChunkGenerator VOID_CHUNK_GENERATOR = new VoidChunkGenerator();
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<String, GameSession> sessions = new HashMap<>();
    private final Map<UUID, String> editingArena = new HashMap<>();
    private final Map<UUID, String> pendingRespawnArena = new HashMap<>();
    private final List<Palette> palettes = new ArrayList<>();
    private final Random random = new Random();

    private NamespacedKey protectionKey;
    private Location survivalLocation;

    private int minPlayers;
    private int countdownSeconds;
    private double protectionChance;
    private int recoverySeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        protectionKey = new NamespacedKey(this, "color_protection");

        minPlayers = Math.max(1, getConfig().getInt("settings.min-players", 5));
        countdownSeconds = Math.max(5, getConfig().getInt("settings.countdown-seconds", 30));
        protectionChance = Math.max(0.0, Math.min(1.0, getConfig().getDouble("settings.protection-chance", 0.22)));
        recoverySeconds = Math.max(1, getConfig().getInt("settings.recovery-seconds", 3));

        loadPalettes();
        loadData();

        Objects.requireNonNull(getCommand("cm"), "plugin.yml missing /cm").setExecutor(this);
        Objects.requireNonNull(getCommand("cm"), "plugin.yml missing /cm").setTabCompleter(this);
        Objects.requireNonNull(getCommand("cmforce"), "plugin.yml missing /cmforce").setExecutor(this);
        Objects.requireNonNull(getCommand("cmforce"), "plugin.yml missing /cmforce").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("ColorMindParty enabled. Arenas loaded: " + arenas.size());
    }

    @Override
    public void onDisable() {
        for (GameSession session : sessions.values()) {
            if (session.state != GameState.IDLE) {
                forceStopSession(session, "服务器正在关闭");
            }
        }
        saveData();
    }

    // ---------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("cmforce")) {
            return handleForce(sender, args);
        }
        return handleCm(sender, args);
    }

    private boolean handleCm(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> {
                if (!(sender instanceof Player player)) return onlyPlayer(sender);
                if (args.length < 2) return usage(sender, "/cm join <地图名称>");
                Arena arena = arenas.get(normalize(args[1]));
                if (arena == null) return error(sender, "地图不存在。先由管理员 /cm create <地图名称> 创建。 ");
                joinArena(player, arena);
                return true;
            }
            case "quit" -> {
                if (!(sender instanceof Player player)) return onlyPlayer(sender);
                quitToSurvival(player, true, true);
                return true;
            }
            case "create" -> {
                if (!isAdmin(sender)) return noPerm(sender);
                if (!(sender instanceof Player player)) return onlyPlayer(sender);
                if (args.length < 2) return usage(sender, "/cm create <地图名称>");
                createArena(player, args[1]);
                return true;
            }
            case "edit" -> {
                if (!isAdmin(sender)) return noPerm(sender);
                if (!(sender instanceof Player player)) return onlyPlayer(sender);
                if (args.length < 2) return usage(sender, "/cm edit <地图名称>");
                editArena(player, args[1]);
                return true;
            }
            case "save" -> {
                if (!isAdmin(sender)) return noPerm(sender);
                if (args.length < 2) return usage(sender, "/cm save <地图名称>");
                Arena arena = arenas.get(normalize(args[1]));
                if (arena == null) return error(sender, "地图不存在。 ");
                List<String> errors = validateArena(arena, false);
                if (!errors.isEmpty()) {
                    sender.sendMessage(prefix() + ChatColor.RED + "保存失败：");
                    errors.forEach(e -> sender.sendMessage(ChatColor.RED + "- " + e));
                    return true;
                }
                World world = arena.world();
                if (world != null) world.save();
                saveData();
                sender.sendMessage(prefix() + ChatColor.GREEN + "地图 " + arena.displayName + " 已保存。 ");
                return true;
            }
            case "register" -> {
                if (!isAdmin(sender)) return noPerm(sender);
                if (args.length < 2) return usage(sender, "/cm register <地图名称>");
                Arena arena = arenas.get(normalize(args[1]));
                if (arena == null) return error(sender, "地图不存在。 ");
                List<String> errors = validateArena(arena, true);
                if (!errors.isEmpty()) {
                    sender.sendMessage(prefix() + ChatColor.RED + "注册失败：");
                    errors.forEach(e -> sender.sendMessage(ChatColor.RED + "- " + e));
                    return true;
                }
                arena.registered = true;
                saveData();
                sender.sendMessage(prefix() + ChatColor.GREEN + "地图 " + arena.displayName + " 已注册为可用。 ");
                return true;
            }
            case "unregister" -> {
                if (!isAdmin(sender)) return noPerm(sender);
                if (args.length < 2) return usage(sender, "/cm unregister <地图名称>");
                Arena arena = arenas.get(normalize(args[1]));
                if (arena == null) return error(sender, "地图不存在。 ");
                arena.registered = false;
                saveData();
                sender.sendMessage(prefix() + ChatColor.YELLOW + "地图 " + arena.displayName + " 已取消注册。 ");
                return true;
            }
            case "set" -> {
                if (!isAdmin(sender)) return noPerm(sender);
                if (!(sender instanceof Player player)) return onlyPlayer(sender);
                if (args.length < 2) return usage(sender, "/cm set survival 或 /cm set deathspawn");
                String target = args[1].toLowerCase(Locale.ROOT);
                if (target.equals("survival")) {
                    survivalLocation = player.getLocation().clone();
                    World w = survivalLocation.getWorld();
                    if (w != null) w.setGameRule(GameRule.KEEP_INVENTORY, true);
                    saveData();
                    sender.sendMessage(prefix() + ChatColor.GREEN + "已设置生存主世界标准传送点。 ");
                    return true;
                }
                if (target.equals("deathspawn")) {
                    Arena arena = requireEditingArena(player);
                    if (arena == null) return true;
                    arena.deathSpawn = player.getLocation().clone();
                    saveData();
                    sender.sendMessage(prefix() + ChatColor.GREEN + "已设置死亡后复活地点。 ");
                    return true;
                }
                return usage(sender, "/cm set survival 或 /cm set deathspawn");
            }
            case "setblock" -> {
                if (!isAdmin(sender)) return noPerm(sender);
                if (!(sender instanceof Player player)) return onlyPlayer(sender);
                if (args.length < 2) return usage(sender, "/cm setblock <start|end>");
                Arena arena = requireEditingArena(player);
                if (arena == null) return true;
                Location blockLoc = new Location(player.getWorld(), player.getLocation().getBlockX(), player.getLocation().getBlockY(), player.getLocation().getBlockZ());
                if (args[1].equalsIgnoreCase("start")) {
                    arena.floorStart = blockLoc;
                    saveData();
                    sender.sendMessage(prefix() + ChatColor.GREEN + "已设置地板第一个选点。 ");
                    return true;
                }
                if (args[1].equalsIgnoreCase("end")) {
                    arena.floorEnd = blockLoc;
                    saveData();
                    sender.sendMessage(prefix() + ChatColor.GREEN + "已设置地板第二个选点。 ");
                    return true;
                }
                return usage(sender, "/cm setblock <start|end>");
            }
            case "setlobby" -> {
                if (!isAdmin(sender)) return noPerm(sender);
                if (!(sender instanceof Player player)) return onlyPlayer(sender);
                Arena arena = requireEditingArena(player);
                if (arena == null) return true;
                arena.lobby = player.getLocation().clone();
                saveData();
                sender.sendMessage(prefix() + ChatColor.GREEN + "已设置等待大厅出生点。 ");
                return true;
            }
            case "setspawn" -> {
                if (!isAdmin(sender)) return noPerm(sender);
                if (!(sender instanceof Player player)) return onlyPlayer(sender);
                Arena arena = requireEditingArena(player);
                if (arena == null) return true;
                arena.spawn = player.getLocation().clone();
                saveData();
                sender.sendMessage(prefix() + ChatColor.GREEN + "已设置游戏开始出生点。 ");
                return true;
            }
            default -> {
                sendHelp(sender);
                return true;
            }
        }
    }

    private boolean handleForce(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) return noPerm(sender);
        if (args.length == 0) return usage(sender, "/cmforce <start|stop> [地图名称]");
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("start")) {
            GameSession session = resolveSessionForForce(sender, args.length >= 2 ? args[1] : null, false);
            if (session == null) return true;
            if (session.state == GameState.RUNNING || session.state == GameState.RECOVERING) {
                return error(sender, "该地图正在游戏中或恢复中，不能强制开始。 ");
            }
            if (onlineLobbyPlayers(session).isEmpty()) {
                return error(sender, "该地图 lobby 内没有在线玩家。先让玩家 /cm join。 ");
            }
            startCountdown(session, true);
            sender.sendMessage(prefix() + ChatColor.GREEN + "已强制开始倒计时：" + session.arena.displayName);
            return true;
        }
        if (sub.equals("stop")) {
            if (args.length >= 2) {
                GameSession session = resolveSessionForForce(sender, args[1], true);
                if (session == null) return true;
                forceStopSession(session, "管理员强制终止");
                sender.sendMessage(prefix() + ChatColor.YELLOW + "已终止：" + session.arena.displayName);
                return true;
            }
            int stopped = 0;
            for (GameSession session : sessions.values()) {
                if (session.state != GameState.IDLE) {
                    forceStopSession(session, "管理员强制终止");
                    stopped++;
                }
            }
            sender.sendMessage(prefix() + ChatColor.YELLOW + "已终止 " + stopped + " 个正在活动的游戏。 ");
            return true;
        }
        return usage(sender, "/cmforce <start|stop> [地图名称]");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "======== ColorMindParty ========");
        sender.sendMessage(ChatColor.AQUA + "/cm join <地图>" + ChatColor.GRAY + " 加入地图 lobby");
        sender.sendMessage(ChatColor.AQUA + "/cm quit" + ChatColor.GRAY + " 退出并回到生存主世界");
        if (isAdmin(sender)) {
            sender.sendMessage(ChatColor.YELLOW + "/cm set survival");
            sender.sendMessage(ChatColor.YELLOW + "/cm create <地图>  /cm edit <地图>  /cm save <地图>");
            sender.sendMessage(ChatColor.YELLOW + "/cm setblock <start|end>  /cm setlobby  /cm setspawn  /cm set deathspawn");
            sender.sendMessage(ChatColor.YELLOW + "/cm register <地图>  /cm unregister <地图>");
            sender.sendMessage(ChatColor.YELLOW + "/cmforce start [地图]  /cmforce stop [地图]");
        }
    }

    // ---------------------------------------------------------------------
    // Arena setup
    // ---------------------------------------------------------------------

    private void createArena(Player player, String rawName) {
        String key = normalize(rawName);
        if (key.isBlank() || !key.matches("[a-z0-9_\\-]{1,32}")) {
            error(player, "地图名称只能包含英文、数字、下划线、短横线，长度 1-32。 ");
            return;
        }
        if (arenas.containsKey(key)) {
            error(player, "地图已存在。使用 /cm edit " + rawName + " 进入编辑。 ");
            return;
        }

        String worldName = "cm_" + key;
        World world = Bukkit.createWorld(voidWorldCreator(worldName));
        if (world == null) {
            error(player, "世界创建失败。请检查服务端日志。 ");
            return;
        }

        configureArenaWorld(world);
        createEditPlatform(world);

        Arena arena = new Arena(key, rawName, worldName);
        arenas.put(key, arena);
        sessions.put(key, new GameSession(arena));
        editingArena.put(player.getUniqueId(), key);
        saveData();

        Location target = editPlatformSpawn(world);
        player.teleport(target);
        player.setGameMode(GameMode.CREATIVE);
        player.sendMessage(prefix() + ChatColor.GREEN + "已创建虚空地图 " + rawName + " 并进入编辑世界：" + worldName);
        player.sendMessage(prefix() + ChatColor.GRAY + "已在原点生成 9x9 小平台。搭好场地后依次设置 setblock/start/end、setlobby、setspawn、set deathspawn。 ");
    }

    private void editArena(Player player, String rawName) {
        Arena arena = arenas.get(normalize(rawName));
        if (arena == null) {
            error(player, "地图不存在。 ");
            return;
        }
        World world = arena.world();
        if (world == null) {
            error(player, "地图世界无法加载：" + arena.worldName);
            return;
        }
        GameSession session = session(arena);
        if (session.state == GameState.RUNNING || session.state == GameState.RECOVERING) {
            error(player, "该地图正在游戏中或恢复中，暂时不能编辑。 ");
            return;
        }
        editingArena.put(player.getUniqueId(), arena.key);
        Location target = arena.spawn != null ? arena.spawn : world.getSpawnLocation().add(0.5, 1.0, 0.5);
        player.teleport(target);
        player.setGameMode(GameMode.CREATIVE);
        player.sendMessage(prefix() + ChatColor.GREEN + "已进入地图编辑：" + arena.displayName);
    }

    private Arena requireEditingArena(Player player) {
        String key = editingArena.get(player.getUniqueId());
        if (key == null) {
            Arena byWorld = arenaByWorld(player.getWorld());
            if (byWorld != null) {
                editingArena.put(player.getUniqueId(), byWorld.key);
                return byWorld;
            }
            error(player, "你不在任何地图编辑状态。请先 /cm edit <地图名称>。 ");
            return null;
        }
        Arena arena = arenas.get(key);
        if (arena == null) {
            editingArena.remove(player.getUniqueId());
            error(player, "编辑地图不存在。 ");
            return null;
        }
        return arena;
    }

    private List<String> validateArena(Arena arena, boolean requireSurvival) {
        List<String> errors = new ArrayList<>();
        if (requireSurvival && survivalLocation == null) errors.add("未设置生存主世界传送点：/cm set survival");
        if (arena.world() == null) errors.add("地图世界无法加载：" + arena.worldName);
        if (arena.floorStart == null) errors.add("未设置地板第一个选点：/cm setblock start");
        if (arena.floorEnd == null) errors.add("未设置地板第二个选点：/cm setblock end");
        if (arena.lobby == null) errors.add("未设置等待大厅出生点：/cm setlobby");
        if (arena.spawn == null) errors.add("未设置游戏开始出生点：/cm setspawn");
        if (arena.deathSpawn == null) errors.add("未设置死亡后复活点：/cm set deathspawn");
        if (arena.floorStart != null && arena.floorEnd != null) {
            if (!sameWorld(arena.floorStart, arena.floorEnd)) errors.add("两个地板选点不在同一世界。 ");
            if (arena.floorStart.getBlockY() != arena.floorEnd.getBlockY()) errors.add("地板选区必须是单层：两个选点 Y 坐标必须相同。 ");
            int width = Math.abs(arena.floorStart.getBlockX() - arena.floorEnd.getBlockX()) + 1;
            int length = Math.abs(arena.floorStart.getBlockZ() - arena.floorEnd.getBlockZ()) + 1;
            if (width % 2 == 0 || length % 2 == 0) errors.add("色盲派对地板长宽必须都是单数。当前：" + width + "x" + length);
            if (width < 3 || length < 3) errors.add("地板选区至少需要 3x3。当前：" + width + "x" + length);
        }
        return errors;
    }

    // ---------------------------------------------------------------------
    // Join / quit / countdown / game loop
    // ---------------------------------------------------------------------

    private void joinArena(Player player, Arena arena) {
        if (!arena.registered) {
            error(player, "该地图未注册为可用状态。 ");
            return;
        }
        List<String> errors = validateArena(arena, true);
        if (!errors.isEmpty()) {
            error(player, "该地图配置不完整，请联系管理员。 ");
            if (player.hasPermission("colormindparty.admin")) {
                errors.forEach(e -> player.sendMessage(ChatColor.RED + "- " + e));
            }
            return;
        }

        GameSession session = session(arena);
        if (session.state == GameState.RUNNING) {
            error(player, "该地图游戏正在进行中，不能加入。 ");
            return;
        }
        if (session.state == GameState.RECOVERING) {
            error(player, "该地图正在恢复中，请稍后再加入。 ");
            return;
        }

        // 若玩家已经在其它游戏/lobby 中，先退出。
        GameSession old = sessionByPlayer(player.getUniqueId()).orElse(null);
        if (old != null && old != session) {
            removeFromSession(old, player.getUniqueId(), false);
        }

        session.lobbyPlayers.add(player.getUniqueId());
        session.participants.add(player.getUniqueId());
        session.state = session.state == GameState.IDLE ? GameState.LOBBY : session.state;

        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(arena.lobby);
        player.sendMessage(prefix() + ChatColor.GREEN + "已加入 " + arena.displayName + " 的等待大厅。 ");
        broadcast(session, ChatColor.AQUA + player.getName() + " 加入了色盲派对。当前人数：" + onlineLobbyPlayers(session).size());

        if (onlineLobbyPlayers(session).size() >= minPlayers && session.countdownTask == null) {
            startCountdown(session, false);
        }
    }

    private void startCountdown(GameSession session, boolean forced) {
        cancelTask(session.countdownTask);
        session.countdownTask = null;
        session.state = GameState.COUNTDOWN;
        session.forcedCountdown = forced;
        session.countdownLeft = countdownSeconds;

        prefillFloor(session.arena);
        broadcast(session, ChatColor.GREEN + "人数已满足，" + countdownSeconds + " 秒后开始游戏！");

        session.countdownTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (session.state != GameState.COUNTDOWN) {
                cancelTask(session.countdownTask);
                session.countdownTask = null;
                return;
            }
            List<Player> online = onlineLobbyPlayers(session);
            if (!session.forcedCountdown && online.size() < minPlayers) {
                broadcast(session, ChatColor.RED + "人数不足，倒计时已取消。 ");
                cancelTask(session.countdownTask);
                session.countdownTask = null;
                session.state = GameState.LOBBY;
                return;
            }
            if (session.countdownLeft <= 0) {
                cancelTask(session.countdownTask);
                session.countdownTask = null;
                startGame(session);
                return;
            }

            for (Player p : online) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, session.countdownLeft <= 10 ? 1.8f : 1.0f);
                p.sendActionBar(Component.text("游戏将在 " + session.countdownLeft + " 秒后开始", NamedTextColor.YELLOW));
                if (session.countdownLeft <= 10) {
                    p.showTitle(Title.title(
                            Component.text(String.valueOf(session.countdownLeft), NamedTextColor.RED),
                            Component.text("准备开始！", NamedTextColor.GOLD),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(850), Duration.ofMillis(100))
                    ));
                }
            }
            session.countdownLeft--;
        }, 0L, 20L);
    }

    private void startGame(GameSession session) {
        List<Player> players = onlineLobbyPlayers(session);
        if (players.isEmpty()) {
            resetToIdle(session);
            return;
        }

        session.state = GameState.RUNNING;
        session.lobbyPlayers.clear();
        session.participants.clear();
        session.alive.clear();
        session.eliminatedOrder.clear();
        session.killOnJoin.clear();
        session.round = 0;
        session.finalCheckStarted = false;
        session.currentTarget = null;
        session.currentTargetName = "";
        session.pvpEnabled = false;

        for (Player p : players) {
            session.participants.add(p.getUniqueId());
            session.alive.add(p.getUniqueId());
            p.getInventory().clear();
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(session.arena.spawn);
            p.showTitle(Title.title(
                    Component.text("色盲派对开始！", NamedTextColor.LIGHT_PURPLE),
                    Component.text("跑起来！", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1000), Duration.ofMillis(250))
            ));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        }

        session.initialAliveCount = Math.max(1, session.alive.size());
        session.pvpTitleShown = false;
        createAliveBossBar(session);
        updateAliveBossBar(session);

        updateScoreboards(session);
        updateAliveBossBar(session);
        startRound(session);
    }

    private void startRound(GameSession session) {
        if (session.state != GameState.RUNNING) return;
        if (session.alive.size() <= 1) {
            checkEndCondition(session);
            return;
        }

        cancelTask(session.roundTask);
        cancelTask(session.actionbarTask);
        session.roundTask = null;
        session.actionbarTask = null;

        session.round++;

        boolean wasPvpEnabled = session.pvpEnabled;
        session.pvpEnabled = session.round >= 11;
        session.finalCheckStarted = false;

        int maxColors = maxColorsForRound(session.round);
        RoundPlan plan = createRoundPlan(maxColors);
        session.currentTarget = plan.target.material;
        session.currentTargetName = plan.target.display;
        session.currentPaletteName = plan.palette.name;
        fillPattern(session.arena, plan);

        for (Player p : onlineAlivePlayers(session)) {
            p.getInventory().clear();
            p.sendActionBar(Component.empty());
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.2f);

            if (!wasPvpEnabled && session.pvpEnabled && !session.pvpTitleShown) {
                p.showTitle(Title.title(
                        Component.text("PVP 已开启！", NamedTextColor.RED),
                        Component.text("可以击退其他玩家", NamedTextColor.GOLD),
                        Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1800), Duration.ofMillis(350))
                ));
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
            }
        }

        if (!wasPvpEnabled && session.pvpEnabled) {
            session.pvpTitleShown = true;
        }

        updateScoreboards(session);
        updateAliveBossBar(session);

        long freeTicks = secondsToTicks(freeRunSeconds(session.round));
        if (freeTicks > 0) {
            session.roundTask = Bukkit.getScheduler().runTaskLater(this, () -> startColorCountdown(session), freeTicks);
        } else {
            startColorCountdown(session);
        }
    }

    private void startColorCountdown(GameSession session) {
        if (session.state != GameState.RUNNING || session.currentTarget == null) return;
        double seconds = colorCountdownSeconds(session.round);
        int totalTicks = (int) Math.max(20, Math.round(seconds * 20.0));
        session.phaseTicksLeft = totalTicks;

        for (Player p : onlineAlivePlayers(session)) {
            fillInventoryWithTarget(p, session.currentTarget);
            if (random.nextDouble() < protectionChance) {
                p.getInventory().setItem(8, protectionItem());
                p.sendMessage(prefix() + ChatColor.AQUA + "你获得了道具：颜色保护。右键使用可保护脚下 3x3。 ");
            }
        }

        cancelTask(session.actionbarTask);
        session.actionbarTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (session.state != GameState.RUNNING) {
                cancelTask(session.actionbarTask);
                session.actionbarTask = null;
                return;
            }
            double left = Math.max(0.0, session.phaseTicksLeft / 20.0);
            String text = String.format(Locale.CHINA, "方块将在 %.1f 秒后消失", left);
            for (Player p : onlineAlivePlayers(session)) {
                p.sendActionBar(Component.text(text, NamedTextColor.GOLD));
                if (session.phaseTicksLeft % 10 == 0) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_SNARE, 0.65f, 1.0f);
                }
            }
            if (session.phaseTicksLeft <= 0) {
                cancelTask(session.actionbarTask);
                session.actionbarTask = null;
                for (Player p : onlineAlivePlayers(session)) p.sendActionBar(Component.empty());
                removeNonTargetBlocks(session.arena, session.currentTarget);
                checkFallEliminations(session);
                if (session.state == GameState.RUNNING) {
                    session.roundTask = Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (session.state == GameState.RUNNING) startRound(session);
                    }, 200L); // 保持 10 秒
                }
                return;
            }
            session.phaseTicksLeft -= 2;
        }, 0L, 2L);
    }

    private void checkEndCondition(GameSession session) {
        if (session.state != GameState.RUNNING) return;
        if (session.alive.isEmpty()) {
            cancelTask(session.finalCheckTask);
            session.finalCheckTask = null;
            endGame(session, null, true);
            return;
        }
        if (session.alive.size() == 1 && !session.finalCheckStarted) {
            session.finalCheckStarted = true;
            UUID possibleWinner = session.alive.iterator().next();
            Player p = Bukkit.getPlayer(possibleWinner);
            if (p != null) {
                p.showTitle(Title.title(
                        Component.text("最后 5 秒！", NamedTextColor.GOLD),
                        Component.text("坚持住就是胜利", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1200), Duration.ofMillis(200))
                ));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
            }
            session.finalCheckTask = Bukkit.getScheduler().runTaskLater(this, () -> {
                if (session.state != GameState.RUNNING) return;
                if (session.alive.size() == 1) {
                    UUID winner = session.alive.iterator().next();
                    endGame(session, winner, false);
                } else if (session.alive.isEmpty()) {
                    endGame(session, null, true);
                }
            }, 100L);
        }
        updateScoreboards(session);
        updateAliveBossBar(session);
    }

    private void endGame(GameSession session, UUID winner, boolean noSurvivor) {
        if (session.state == GameState.RECOVERING || session.state == GameState.IDLE) return;
        session.state = GameState.RECOVERING;
        cancelGameTasks(session);
        clearAliveBossBar(session);

        String winnerName = winner == null ? null : playerName(winner);
        String mainTitle = noSurvivor || winner == null ? "无人幸存" : winnerName + " 获胜！";
        NamedTextColor color = noSurvivor || winner == null ? NamedTextColor.RED : NamedTextColor.GOLD;

        for (UUID uuid : new LinkedHashSet<>(session.participants)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            sendToSurvival(p, true, true);
            p.showTitle(Title.title(
                    Component.text(mainTitle, color),
                    Component.text("色盲派对结束", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500))
            ));
            p.playSound(p.getLocation(), noSurvivor ? Sound.ENTITY_WITHER_DEATH : Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, noSurvivor ? 0.8f : 1.2f);
        }

        if (noSurvivor || winner == null) {
            List<String> top = topThreeNoSurvivor(session);
            Bukkit.broadcastMessage(prefix() + ChatColor.RED + "无人幸存！前三名：" + (top.isEmpty() ? "无" : String.join(ChatColor.GRAY + " / " + ChatColor.YELLOW, top)));
        } else {
            Bukkit.broadcastMessage(prefix() + ChatColor.GOLD + winnerName + " 赢得了色盲派对地图 " + session.arena.displayName + "！");
        }

        prefillFloor(session.arena);
        Bukkit.getScheduler().runTaskLater(this, () -> resetToIdle(session), recoverySeconds * 20L);
    }

    private void forceStopSession(GameSession session, String reason) {
        if (session.state == GameState.IDLE) return;
        session.state = GameState.RECOVERING;
        cancelGameTasks(session);
        clearAliveBossBar(session);
        for (UUID uuid : new LinkedHashSet<>(session.participants)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            sendToSurvival(p, true, true);
            p.showTitle(Title.title(
                    Component.text("游戏已终止", NamedTextColor.RED),
                    Component.text(reason, NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1400), Duration.ofMillis(250))
            ));
        }
        prefillFloor(session.arena);
        Bukkit.getScheduler().runTaskLater(this, () -> resetToIdle(session), recoverySeconds * 20L);
    }

    private void quitToSurvival(Player player, boolean clearInventory, boolean message) {
        UUID uuid = player.getUniqueId();
        Optional<GameSession> optional = sessionByPlayer(uuid);
        if (optional.isPresent()) {
            GameSession session = optional.get();
            if (session.state == GameState.RUNNING && session.alive.contains(uuid)) {
                eliminate(session, uuid, "退出游戏", false);
            }
            removeFromSession(session, uuid, false);
        }
        editingArena.remove(uuid);
        sendToSurvival(player, clearInventory, true);
        if (message) player.sendMessage(prefix() + ChatColor.GREEN + "已返回生存主世界。 ");
    }

    private void sendToSurvival(Player player, boolean clearInventory, boolean survivalMode) {
        if (clearInventory) player.getInventory().clear();
        if (survivalMode) player.setGameMode(GameMode.SURVIVAL);
        World targetWorld = survivalLocation != null ? survivalLocation.getWorld() : null;
        if (targetWorld != null) targetWorld.setGameRule(GameRule.KEEP_INVENTORY, true);
        Location target = survivalLocation != null ? survivalLocation : Bukkit.getWorlds().get(0).getSpawnLocation();
        player.teleport(target);
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        player.sendActionBar(Component.empty());

        for (GameSession session : sessions.values()) {
            if (session.aliveBossBar != null) {
                session.aliveBossBar.removePlayer(player);
            }
        }
    }

    private void removeFromSession(GameSession session, UUID uuid, boolean broadcastLeave) {
        boolean removed = session.lobbyPlayers.remove(uuid) | session.participants.remove(uuid) | session.alive.remove(uuid) | session.killOnJoin.remove(uuid);
        if (removed && broadcastLeave) broadcast(session, ChatColor.YELLOW + playerName(uuid) + " 离开了色盲派对。 ");
        if (session.state == GameState.COUNTDOWN && !session.forcedCountdown && onlineLobbyPlayers(session).size() < minPlayers) {
            broadcast(session, ChatColor.RED + "人数不足，倒计时已取消。 ");
            cancelTask(session.countdownTask);
            session.countdownTask = null;
            session.state = onlineLobbyPlayers(session).isEmpty() ? GameState.IDLE : GameState.LOBBY;
        }
        if (session.state == GameState.LOBBY && onlineLobbyPlayers(session).isEmpty()) {
            resetToIdle(session);
        }
        updateScoreboards(session);
    }

    private void createAliveBossBar(GameSession session) {
        clearAliveBossBar(session);
        session.aliveBossBar = Bukkit.createBossBar("幸存玩家", BarColor.PURPLE, BarStyle.SOLID);
        session.aliveBossBar.setVisible(true);

        for (UUID uuid : session.participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                session.aliveBossBar.addPlayer(p);
            }
        }
    }

    private void updateAliveBossBar(GameSession session) {
        if (session.aliveBossBar == null) return;

        int alive = session.alive.size();
        int total = Math.max(1, session.initialAliveCount);
        double progress = Math.max(0.0, Math.min(1.0, alive / (double) total));

        session.aliveBossBar.setTitle("幸存玩家 " + alive + " / " + total);
        session.aliveBossBar.setProgress(progress);
        session.aliveBossBar.setVisible(session.state == GameState.RUNNING);

        for (UUID uuid : session.participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                session.aliveBossBar.addPlayer(p);
            }
        }
    }

    private void clearAliveBossBar(GameSession session) {
        if (session.aliveBossBar != null) {
            session.aliveBossBar.removeAll();
            session.aliveBossBar.setVisible(false);
            session.aliveBossBar = null;
        }
    }
    
    private void eliminate(GameSession session, UUID uuid, String reason, boolean teleportSpectator) {
        if (!session.alive.remove(uuid)) return;
        if (!session.eliminatedOrder.contains(uuid)) session.eliminatedOrder.add(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.getInventory().clear();
            player.sendActionBar(Component.empty());
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.8f, 0.7f);
            if (teleportSpectator && session.arena.deathSpawn != null) {
                player.setGameMode(GameMode.SPECTATOR);
                player.teleport(session.arena.deathSpawn);
            }
            player.sendMessage(prefix() + ChatColor.RED + "你已失败：" + reason);
        }
        updateScoreboards(session);
        updateAliveBossBar(session);
        checkEndCondition(session);
    }

    // ---------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = playerDamager(event.getDamager());
        if (attacker == null) return;
        Optional<GameSession> victimSession = sessionByPlayer(victim.getUniqueId());
        Optional<GameSession> attackerSession = sessionByPlayer(attacker.getUniqueId());
        if (victimSession.isEmpty() || attackerSession.isEmpty() || victimSession.get() != attackerSession.get()) return;
        GameSession session = victimSession.get();
        if (session.state != GameState.RUNNING || !session.pvpEnabled) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Optional<GameSession> optional = sessionByPlayer(player.getUniqueId());
        if (optional.isEmpty()) return;
        GameSession session = optional.get();
        if (session.state != GameState.RUNNING) return;

        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        pendingRespawnArena.put(player.getUniqueId(), session.arena.key);
        eliminate(session, player.getUniqueId(), "死亡", false);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        String key = pendingRespawnArena.remove(event.getPlayer().getUniqueId());
        if (key == null) return;
        Arena arena = arenas.get(key);
        if (arena == null || arena.deathSpawn == null) return;
        event.setRespawnLocation(arena.deathSpawn);
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = event.getPlayer();
            p.setGameMode(GameMode.SPECTATOR);
            p.getInventory().clear();
            p.teleport(arena.deathSpawn);
        });
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Optional<GameSession> optional = sessionByPlayer(player.getUniqueId());
        if (optional.isEmpty()) return;
        GameSession session = optional.get();
        if (session.state != GameState.RUNNING || !session.alive.contains(player.getUniqueId())) return;
        if (event.getTo() == null) return;
        int floorY = session.arena.floorY();
        if (event.getTo().getY() < floorY - 5.0) {
            eliminate(session, player.getUniqueId(), "掉出场地", true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        editingArena.remove(uuid);
        Optional<GameSession> optional = sessionByPlayer(uuid);
        if (optional.isEmpty()) return;
        GameSession session = optional.get();
        if (session.state == GameState.RUNNING) {
            if (session.alive.contains(uuid)) {
                session.killOnJoin.add(uuid);
                eliminate(session, uuid, "中途离线", false);
            }
        } else {
            removeFromSession(session, uuid, true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (GameSession session : sessions.values()) {
            if (session.killOnJoin.remove(player.getUniqueId())) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (session.state == GameState.RUNNING && session.arena.deathSpawn != null) {
                        player.getInventory().clear();
                        player.setGameMode(GameMode.SPECTATOR);
                        player.teleport(session.arena.deathSpawn);
                        player.sendMessage(prefix() + ChatColor.RED + "你在游戏中途离线，已判定失败。 ");
                    }
                }, 2L);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        Optional<GameSession> optional = sessionByPlayer(player.getUniqueId());
        if (optional.isEmpty()) return;
        GameSession session = optional.get();
        if (session.state != GameState.RUNNING || !session.alive.contains(player.getUniqueId())) return;

        ItemStack item = event.getItem();
        if (!isProtectionItem(item)) return;
        event.setCancelled(true);
        if (session.currentTarget == null) return;

        protect3x3(session, player.getLocation(), session.currentTarget);
        item.setAmount(item.getAmount() - 1);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.6f);
        player.sendMessage(prefix() + ChatColor.AQUA + "已使用颜色保护。 ");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (sessionByPlayer(event.getPlayer().getUniqueId()).filter(s -> s.state == GameState.RUNNING || s.state == GameState.COUNTDOWN || s.state == GameState.LOBBY).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (sessionByPlayer(player.getUniqueId()).filter(s -> s.state == GameState.RUNNING || s.state == GameState.COUNTDOWN || s.state == GameState.LOBBY).isPresent()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (sessionByPlayer(player.getUniqueId()).filter(s -> s.state == GameState.RUNNING || s.state == GameState.COUNTDOWN || s.state == GameState.LOBBY).isPresent()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (sessionByPlayer(event.getPlayer().getUniqueId()).filter(s -> s.state == GameState.RUNNING || s.state == GameState.COUNTDOWN || s.state == GameState.LOBBY).isPresent()) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------------
    // Floor generation and patterns
    // ---------------------------------------------------------------------

    private void prefillFloor(Arena arena) {
        if (arena.floorStart == null || arena.floorEnd == null || arena.world() == null) return;
        List<Material> preset = List.of(
                safeMaterial("WHITE_CONCRETE", Material.WHITE_WOOL),
                safeMaterial("LIGHT_BLUE_CONCRETE", Material.LIGHT_BLUE_WOOL),
                safeMaterial("YELLOW_CONCRETE", Material.YELLOW_WOOL),
                safeMaterial("PINK_CONCRETE", Material.PINK_WOOL)
        );
        forEachFloor(arena, (x, y, z) -> arena.world().getBlockAt(x, y, z).setType(preset.get(Math.floorMod(x + z, preset.size())), false));
    }

    private RoundPlan createRoundPlan(int maxColors) {
        List<Palette> usable = palettes.stream()
                .filter(p -> p.entries.size() >= 2)
                .collect(Collectors.toCollection(ArrayList::new));
        int desiredMinimum = Math.min(maxColors, 16);
        List<Palette> largeEnough = usable.stream().filter(p -> p.entries.size() >= desiredMinimum).collect(Collectors.toList());
        if (!largeEnough.isEmpty()) usable = largeEnough;
        Palette palette = usable.get(random.nextInt(usable.size()));

        List<PaletteEntry> entries = new ArrayList<>(palette.entries);
        Collections.shuffle(entries, random);
        int count = Math.max(2, Math.min(maxColors, entries.size()));
        entries = new ArrayList<>(entries.subList(0, count));
        PaletteEntry target = entries.get(random.nextInt(entries.size()));
        PatternType pattern = PatternType.values()[random.nextInt(PatternType.values().length)];
        return new RoundPlan(palette, entries, target, pattern);
    }

    private void fillPattern(Arena arena, RoundPlan plan) {
        switch (plan.pattern) {
            case RANDOM_MIX -> patternRandom(arena, plan);
            case STRIPES -> patternStripes(arena, plan, false);
            case CIRCLES -> patternCircles(arena, plan);
            case AREAS_3X3 -> patternAreas(arena, plan);
            case DIAGONALS -> patternDiagonals(arena, plan);
            case FIXED_CORNERS -> patternFixedCorners(arena, plan);
        }
    }

    private void patternRandom(Arena arena, RoundPlan plan) {
        forEachFloor(arena, (x, y, z) -> arena.world().getBlockAt(x, y, z).setType(randomEntry(plan.colors).material, false));
    }

    private void patternStripes(Arena arena, RoundPlan plan, boolean preferX) {
        boolean alongX = preferX || random.nextBoolean();
        int stripes = alongX ? arena.width() : arena.length();
        Set<Integer> targetRows = pickTwoIndexes(stripes);
        Map<Integer, Material> stripeMaterials = new HashMap<>();
        for (int i = 0; i < stripes; i++) {
            stripeMaterials.put(i, targetRows.contains(i) ? plan.target.material : randomNonTarget(plan).material);
        }
        forEachFloor(arena, (x, y, z) -> {
            int index = alongX ? x - arena.minX() : z - arena.minZ();
            arena.world().getBlockAt(x, y, z).setType(stripeMaterials.get(index), false);
        });
    }

    private void patternCircles(Arena arena, RoundPlan plan) {
        int centerX = arena.minX() + arena.width() / 2;
        int centerZ = arena.minZ() + arena.length() / 2;
        int rings = Math.max(arena.width(), arena.length()) / 2 + 1;
        Set<Integer> targetRings = pickTwoIndexes(rings);
        Map<Integer, Material> ringMaterials = new HashMap<>();
        for (int ring = 0; ring < rings; ring++) {
            ringMaterials.put(ring, targetRings.contains(ring) ? plan.target.material : randomNonTarget(plan).material);
        }
        // 中心点也允许是任意颜色，但若中心圈被选为目标色则保留目标色。
        forEachFloor(arena, (x, y, z) -> {
            int ring = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
            arena.world().getBlockAt(x, y, z).setType(ringMaterials.getOrDefault(ring, randomEntry(plan.colors).material), false);
        });
    }

    private void patternAreas(Arena arena, RoundPlan plan) {
        Map<String, Material> groupMaterials = new HashMap<>();
        forEachFloor(arena, (x, y, z) -> {
            int gx = Math.floorDiv(x - arena.minX(), 3);
            int gz = Math.floorDiv(z - arena.minZ(), 3);
            String key = gx + ":" + gz;
            Material material = groupMaterials.computeIfAbsent(key, ignored -> randomEntry(plan.colors).material);
            arena.world().getBlockAt(x, y, z).setType(material, false);
        });
        // 额外保证至少一个 3x3 目标安全区。
        int cx = arena.minX() + (random.nextInt(Math.max(1, arena.width() / 3)) * 3);
        int cz = arena.minZ() + (random.nextInt(Math.max(1, arena.length() / 3)) * 3);
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                int x = Math.min(arena.maxX(), cx + dx);
                int z = Math.min(arena.maxZ(), cz + dz);
                arena.world().getBlockAt(x, arena.floorY(), z).setType(plan.target.material, false);
            }
        }
    }

    private void patternDiagonals(Arena arena, RoundPlan plan) {
        int bands = Math.max(2, plan.colors.size());
        Set<Integer> targetBands = pickTwoIndexes(bands);
        Map<Integer, Material> bandMaterials = new HashMap<>();
        for (int i = 0; i < bands; i++) {
            bandMaterials.put(i, targetBands.contains(i) ? plan.target.material : randomNonTarget(plan).material);
        }
        forEachFloor(arena, (x, y, z) -> {
            int band = Math.floorMod((x - arena.minX()) + (z - arena.minZ()), bands);
            arena.world().getBlockAt(x, y, z).setType(bandMaterials.get(band), false);
        });
    }

    private void patternFixedCorners(Arena arena, RoundPlan plan) {
        patternStripes(arena, plan, random.nextBoolean());
        Material corner = randomNonTarget(plan).material;
        fillCorner(arena, arena.minX(), arena.minZ(), 1, 1, corner);
        fillCorner(arena, arena.maxX(), arena.minZ(), -1, 1, corner);
        fillCorner(arena, arena.minX(), arena.maxZ(), 1, -1, corner);
        fillCorner(arena, arena.maxX(), arena.maxZ(), -1, -1, corner);
    }

    private void fillCorner(Arena arena, int startX, int startZ, int sx, int sz, Material material) {
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                int x = startX + dx * sx;
                int z = startZ + dz * sz;
                if (arena.contains(x, z)) arena.world().getBlockAt(x, arena.floorY(), z).setType(material, false);
            }
        }
    }

    private void removeNonTargetBlocks(Arena arena, Material target) {
        World world = arena.world();
        if (world == null) return;
        forEachFloor(arena, (x, y, z) -> {
            if (world.getBlockAt(x, y, z).getType() != target) {
                world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
        });
    }

    private void protect3x3(GameSession session, Location loc, Material target) {
        Arena arena = session.arena;
        World world = arena.world();
        if (world == null) return;
        int cx = loc.getBlockX();
        int cz = loc.getBlockZ();
        int y = arena.floorY();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (arena.contains(x, z)) world.getBlockAt(x, y, z).setType(target, false);
            }
        }
    }

    private void checkFallEliminations(GameSession session) {
        for (Player p : new ArrayList<>(onlineAlivePlayers(session))) {
            if (p.getLocation().getY() < session.arena.floorY() - 5.0) {
                eliminate(session, p.getUniqueId(), "掉出场地", true);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Inventory / scoreboard / display helpers
    // ---------------------------------------------------------------------

    private void fillInventoryWithTarget(Player player, Material material) {
        ItemStack stack = new ItemStack(material, 64);
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            player.getInventory().setItem(i, stack.clone());
        }
    }

    private ItemStack protectionItem() {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("颜色保护", NamedTextColor.AQUA));
            meta.lore(List.of(
                    Component.text("右键：把周围 3x3 地板变成本回合颜色", NamedTextColor.GRAY),
                    Component.text("每回合随机获得", NamedTextColor.DARK_GRAY)
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(protectionKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isProtectionItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(protectionKey, PersistentDataType.BYTE);
    }

    private void updateScoreboards(GameSession session) {
        if (session.state != GameState.RUNNING && session.state != GameState.COUNTDOWN && session.state != GameState.LOBBY) return;
        for (UUID uuid : session.participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) updateScoreboard(session, p);
        }
    }

    private void updateScoreboard(GameSession session, Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("cm", Criteria.DUMMY, Component.text("色盲派对", NamedTextColor.LIGHT_PURPLE));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        String roundText = session.state == GameState.RUNNING
                ? "第 " + session.round + " 回合"
                : (session.state == GameState.COUNTDOWN ? "倒计时 " + session.countdownLeft + " 秒" : "等待中");

        obj.getScore(ChatColor.YELLOW + roundText).setScore(3);
        obj.getScore(ChatColor.GRAY + "地图 " + session.arena.displayName).setScore(2);
        obj.getScore(ChatColor.DARK_GRAY + " ").setScore(1);

        player.setScoreboard(board);
    }

    private void broadcast(GameSession session, String message) {
        for (UUID uuid : session.participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(prefix() + message);
        }
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    private void loadData() {
        survivalLocation = readLocation("survival", null);
        ConfigurationSection section = getConfig().getConfigurationSection("arenas");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            String displayName = s.getString("display-name", key);
            String worldName = s.getString("world", "cm_" + key);
            ensureWorld(worldName);
            Arena arena = new Arena(key, displayName, worldName);
            arena.registered = s.getBoolean("registered", false);
            arena.floorStart = readLocation("arenas." + key + ".floor-start", worldName);
            arena.floorEnd = readLocation("arenas." + key + ".floor-end", worldName);
            arena.lobby = readLocation("arenas." + key + ".lobby", worldName);
            arena.spawn = readLocation("arenas." + key + ".spawn", worldName);
            arena.deathSpawn = readLocation("arenas." + key + ".death-spawn", worldName);
            arenas.put(key, arena);
            sessions.put(key, new GameSession(arena));
        }
    }

    private void saveData() {
        getConfig().set("survival", null);
        writeLocation("survival", survivalLocation);
        getConfig().set("arenas", null);
        for (Arena arena : arenas.values()) {
            String base = "arenas." + arena.key;
            getConfig().set(base + ".display-name", arena.displayName);
            getConfig().set(base + ".world", arena.worldName);
            getConfig().set(base + ".registered", arena.registered);
            writeLocation(base + ".floor-start", arena.floorStart);
            writeLocation(base + ".floor-end", arena.floorEnd);
            writeLocation(base + ".lobby", arena.lobby);
            writeLocation(base + ".spawn", arena.spawn);
            writeLocation(base + ".death-spawn", arena.deathSpawn);
        }
        saveConfig();
    }

    private Location readLocation(String path, String fallbackWorldName) {
        if (!getConfig().contains(path + ".x")) return null;
        String worldName = getConfig().getString(path + ".world", fallbackWorldName);
        World world = worldName == null ? null : ensureWorld(worldName);
        if (world == null) return null;
        double x = getConfig().getDouble(path + ".x");
        double y = getConfig().getDouble(path + ".y");
        double z = getConfig().getDouble(path + ".z");
        float yaw = (float) getConfig().getDouble(path + ".yaw", 0.0);
        float pitch = (float) getConfig().getDouble(path + ".pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private void writeLocation(String path, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        getConfig().set(path + ".world", loc.getWorld().getName());
        getConfig().set(path + ".x", loc.getX());
        getConfig().set(path + ".y", loc.getY());
        getConfig().set(path + ".z", loc.getZ());
        getConfig().set(path + ".yaw", loc.getYaw());
        getConfig().set(path + ".pitch", loc.getPitch());
    }

    // ---------------------------------------------------------------------
    // Palettes
    // ---------------------------------------------------------------------

    private void loadPalettes() {
        palettes.clear();
        List<ColorName> colors = List.of(
                new ColorName("WHITE", "白"),
                new ColorName("LIGHT_GRAY", "淡灰"),
                new ColorName("GRAY", "灰"),
                new ColorName("BLACK", "黑"),
                new ColorName("BROWN", "棕"),
                new ColorName("RED", "红"),
                new ColorName("ORANGE", "橙"),
                new ColorName("YELLOW", "黄"),
                new ColorName("LIME", "黄绿"),
                new ColorName("GREEN", "绿"),
                new ColorName("CYAN", "青"),
                new ColorName("LIGHT_BLUE", "淡蓝"),
                new ColorName("BLUE", "蓝"),
                new ColorName("PURPLE", "紫"),
                new ColorName("MAGENTA", "品红"),
                new ColorName("PINK", "粉")
        );

        Palette terracotta = new Palette("陶瓦类");
        addIfPresent(terracotta, "TERRACOTTA", "陶瓦");
        for (ColorName c : colors) addIfPresent(terracotta, c.prefix + "_TERRACOTTA", c.display);
        palettes.add(terracotta);

        Palette concrete = new Palette("混凝土类");
        for (ColorName c : colors) addIfPresent(concrete, c.prefix + "_CONCRETE", c.display);
        palettes.add(concrete);

        Palette wool = new Palette("羊毛类");
        for (ColorName c : colors) addIfPresent(wool, c.prefix + "_WOOL", c.display);
        palettes.add(wool);

        Palette glass = new Palette("玻璃类");
        for (ColorName c : colors) addIfPresent(glass, c.prefix + "_STAINED_GLASS", c.display);
        palettes.add(glass);

        Palette glazed = new Palette("带釉陶瓦类");
        for (ColorName c : colors) addIfPresent(glazed, c.prefix + "_GLAZED_TERRACOTTA", c.display);
        palettes.add(glazed);

        Palette planks = new Palette("木板类");
        addIfPresent(planks, "OAK_PLANKS", "橡木");
        addIfPresent(planks, "SPRUCE_PLANKS", "云杉");
        addIfPresent(planks, "BIRCH_PLANKS", "白桦");
        addIfPresent(planks, "JUNGLE_PLANKS", "丛林");
        addIfPresent(planks, "ACACIA_PLANKS", "金合欢");
        addIfPresent(planks, "DARK_OAK_PLANKS", "深色橡木");
        addIfPresent(planks, "MANGROVE_PLANKS", "红树");
        addIfPresent(planks, "CHERRY_PLANKS", "樱花");
        addIfPresent(planks, "BAMBOO_PLANKS", "竹");
        addIfPresent(planks, "CRIMSON_PLANKS", "绯红");
        addIfPresent(planks, "WARPED_PLANKS", "诡异");
        addIfPresent(planks, "PALE_OAK_PLANKS", "苍白橡木");
        palettes.add(planks);

        palettes.removeIf(p -> p.entries.size() < 2);
        if (palettes.isEmpty()) {
            throw new IllegalStateException("No usable block palettes found in this server version.");
        }
    }

    private void addIfPresent(Palette palette, String materialName, String display) {
        Material material = Material.matchMaterial(materialName);
        if (material != null && material.isBlock() && material.isItem()) {
            palette.entries.add(new PaletteEntry(material, display));
        }
    }

    // ---------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------

    private WorldCreator voidWorldCreator(String worldName) {
        return new WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .generator(VOID_CHUNK_GENERATOR)
                .generateStructures(false)
                .bonusChest(false)
                .forcedSpawnPosition(Position.block(0, EDIT_PLATFORM_Y + 2, 0), 0.0f, 0.0f);
    }

    private void configureArenaWorld(World world) {
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);

        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(6000L);
        world.setSpawnLocation(0, EDIT_PLATFORM_Y + 2, 0);
    }

    private Location editPlatformSpawn(World world) {
        return new Location(world, 0.5, EDIT_PLATFORM_Y + 2.0, 0.5, 0.0f, 0.0f);
    }

    private void createEditPlatform(World world) {
        int y = EDIT_PLATFORM_Y;
        Material platform = safeMaterial("SMOOTH_QUARTZ", Material.STONE);
        Material center = safeMaterial("SEA_LANTERN", Material.GLOWSTONE);

        for (int x = -EDIT_PLATFORM_RADIUS; x <= EDIT_PLATFORM_RADIUS; x++) {
            for (int z = -EDIT_PLATFORM_RADIUS; z <= EDIT_PLATFORM_RADIUS; z++) {
                Material material = (x == 0 && z == 0) ? center : platform;
                world.getBlockAt(x, y, z).setType(material, false);
            }
        }

        // 简单标记出生点，方便管理员知道自己站在哪里。
        world.getBlockAt(0, y + 1, -EDIT_PLATFORM_RADIUS).setType(Material.TORCH, false);
        world.getBlockAt(0, y + 1, EDIT_PLATFORM_RADIUS).setType(Material.TORCH, false);
        world.getBlockAt(-EDIT_PLATFORM_RADIUS, y + 1, 0).setType(Material.TORCH, false);
        world.getBlockAt(EDIT_PLATFORM_RADIUS, y + 1, 0).setType(Material.TORCH, false);
    }

    private static final class VoidChunkGenerator extends ChunkGenerator {
        @Override
        public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
            return worldInfo.getMinHeight();
        }

        @Override
        public boolean shouldGenerateNoise() {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface() {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }

        @Override
        public boolean shouldGenerateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
            return false;
        }
    }
    
    private String prefix() {
        return ChatColor.LIGHT_PURPLE + "[色盲派对] " + ChatColor.RESET;
    }

    private String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
    }

    private boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("colormindparty.admin") || sender.isOp();
    }

    private boolean noPerm(CommandSender sender) {
        sender.sendMessage(prefix() + ChatColor.RED + "你没有权限。 ");
        return true;
    }

    private boolean onlyPlayer(CommandSender sender) {
        sender.sendMessage(prefix() + ChatColor.RED + "此命令只能由玩家执行。 ");
        return true;
    }

    private boolean usage(CommandSender sender, String usage) {
        sender.sendMessage(prefix() + ChatColor.YELLOW + usage);
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(prefix() + ChatColor.RED + message);
        return true;
    }

    private World ensureWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            configureArenaWorld(world);
            return world;
        }

        World created = Bukkit.createWorld(voidWorldCreator(worldName));
        if (created != null) {
            configureArenaWorld(created);
        }
        return created;
    }

    private boolean sameWorld(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && b.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    private Arena arenaByWorld(World world) {
        if (world == null) return null;
        for (Arena arena : arenas.values()) {
            if (arena.worldName.equals(world.getName())) return arena;
        }
        return null;
    }

    private GameSession session(Arena arena) {
        return sessions.computeIfAbsent(arena.key, ignored -> new GameSession(arena));
    }

    private Optional<GameSession> sessionByPlayer(UUID uuid) {
        return sessions.values().stream()
                .filter(s -> s.participants.contains(uuid) || s.lobbyPlayers.contains(uuid) || s.alive.contains(uuid))
                .findFirst();
    }

    private List<Player> onlineLobbyPlayers(GameSession session) {
        return session.lobbyPlayers.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private List<Player> onlineAlivePlayers(GameSession session) {
        return session.alive.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private void resetToIdle(GameSession session) {
        cancelGameTasks(session);
        clearAliveBossBar(session);
        session.state = GameState.IDLE;
        session.lobbyPlayers.clear();
        session.participants.clear();
        session.alive.clear();
        session.eliminatedOrder.clear();
        session.killOnJoin.clear();
        session.round = 0;
        session.currentTarget = null;
        session.currentTargetName = "";
        session.currentPaletteName = "";
        session.pvpEnabled = false;
        session.pvpTitleShown = false;
        session.forcedCountdown = false;
        session.finalCheckStarted = false;
        session.initialAliveCount = 0;
    }

    private void cancelGameTasks(GameSession session) {
        cancelTask(session.countdownTask);
        cancelTask(session.roundTask);
        cancelTask(session.actionbarTask);
        cancelTask(session.finalCheckTask);
        session.countdownTask = null;
        session.roundTask = null;
        session.actionbarTask = null;
        session.finalCheckTask = null;
    }

    private void cancelTask(BukkitTask task) {
        if (task != null && !task.isCancelled()) task.cancel();
    }

    private int maxColorsForRound(int round) {
        if (round <= 1) return 4;
        return Math.min(22, 4 + (round - 1) * 2);
    }

    private double freeRunSeconds(int round) {
        if (round >= 11) return 0.0;
        return Math.max(0.5, 5.0 - (round - 1) * 0.5);
    }

    private double colorCountdownSeconds(int round) {
        if (round <= 11) return 5.0;

        // 第 12-20 回合从 5.0 秒逐渐压缩到 2.0 秒；
        // 第 20 回合及以后固定 2.0 秒，不再继续压缩。
        return Math.max(2.0, 5.0 - (round - 11) * (3.0 / 9.0));
    }

    private long secondsToTicks(double seconds) {
        return Math.max(0L, Math.round(seconds * 20.0));
    }

    private PaletteEntry randomEntry(List<PaletteEntry> entries) {
        return entries.get(random.nextInt(entries.size()));
    }

    private PaletteEntry randomNonTarget(RoundPlan plan) {
        List<PaletteEntry> nonTargets = plan.colors.stream().filter(e -> e.material != plan.target.material).collect(Collectors.toList());
        if (nonTargets.isEmpty()) return plan.target;
        return randomEntry(nonTargets);
    }

    private Set<Integer> pickTwoIndexes(int size) {
        Set<Integer> set = new HashSet<>();
        if (size <= 0) return set;
        set.add(random.nextInt(size));
        if (size > 1) {
            while (set.size() < 2) set.add(random.nextInt(size));
        }
        return set;
    }

    private void forEachFloor(Arena arena, FloorConsumer consumer) {
        World world = arena.world();
        if (world == null || arena.floorStart == null || arena.floorEnd == null) return;
        int y = arena.floorY();
        for (int x = arena.minX(); x <= arena.maxX(); x++) {
            for (int z = arena.minZ(); z <= arena.maxZ(); z++) {
                consumer.accept(x, y, z);
            }
        }
    }

    private Material safeMaterial(String name, Material fallback) {
        Material m = Material.matchMaterial(name);
        return m == null ? fallback : m;
    }

    private Player playerDamager(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private String playerName(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        return Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName()).orElse(uuid.toString().substring(0, 8));
    }

    private List<String> topThreeNoSurvivor(GameSession session) {
        List<String> result = new ArrayList<>();
        List<UUID> order = new ArrayList<>(session.eliminatedOrder);
        Collections.reverse(order);
        for (UUID uuid : order) {
            if (result.size() >= 3) break;
            result.add(playerName(uuid));
        }
        return result;
    }

    private GameSession resolveSessionForForce(CommandSender sender, String rawName, boolean allowIdle) {
        if (rawName != null) {
            Arena arena = arenas.get(normalize(rawName));
            if (arena == null) {
                error(sender, "地图不存在。 ");
                return null;
            }
            GameSession s = session(arena);
            if (!allowIdle && s.state == GameState.IDLE && s.lobbyPlayers.isEmpty()) {
                error(sender, "该地图没有等待中的玩家。 ");
                return null;
            }
            return s;
        }
        if (sender instanceof Player player) {
            Arena byWorld = arenaByWorld(player.getWorld());
            if (byWorld != null) return session(byWorld);
            Optional<GameSession> byPlayer = sessionByPlayer(player.getUniqueId());
            if (byPlayer.isPresent()) return byPlayer.get();
        }
        List<GameSession> active = sessions.values().stream()
                .filter(s -> allowIdle ? s.state != GameState.IDLE : (s.state == GameState.LOBBY || s.state == GameState.COUNTDOWN))
                .collect(Collectors.toList());
        if (active.size() == 1) return active.get(0);
        if (active.isEmpty()) {
            error(sender, "没有可操作的地图。可使用 /cmforce start <地图名称>。 ");
        } else {
            error(sender, "存在多个地图，请指定地图名称：/cmforce start <地图名称> 或 /cmforce stop <地图名称>。 ");
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Tab completion
    // ---------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("cmforce")) {
            if (args.length == 1) return filter(List.of("start", "stop"), args[0]);
            if (args.length == 2) return filter(new ArrayList<>(arenas.keySet()), args[1]);
            return List.of();
        }
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("join", "quit"));
            if (isAdmin(sender)) base.addAll(List.of("set", "create", "edit", "save", "setblock", "setlobby", "setspawn", "register", "unregister"));
            return filter(base, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("join") || sub.equals("edit") || sub.equals("save") || sub.equals("register") || sub.equals("unregister")) {
                return filter(new ArrayList<>(arenas.keySet()), args[1]);
            }
            if (sub.equals("set")) return filter(List.of("survival", "deathspawn"), args[1]);
            if (sub.equals("setblock")) return filter(List.of("start", "end"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).sorted().collect(Collectors.toList());
    }

    // ---------------------------------------------------------------------
    // Nested models
    // ---------------------------------------------------------------------

    private enum GameState {
        IDLE, LOBBY, COUNTDOWN, RUNNING, RECOVERING
    }

    private enum PatternType {
        RANDOM_MIX("杂色型"),
        STRIPES("条带型"),
        CIRCLES("圆圈型"),
        AREAS_3X3("面积型"),
        DIAGONALS("斜线型"),
        FIXED_CORNERS("固定四角型");

        final String displayName;
        PatternType(String displayName) {
            this.displayName = displayName;
        }
    }

    private final class Arena {
        final String key;
        final String displayName;
        final String worldName;
        boolean registered;
        Location floorStart;
        Location floorEnd;
        Location lobby;
        Location spawn;
        Location deathSpawn;

        Arena(String key, String displayName, String worldName) {
            this.key = key;
            this.displayName = displayName;
            this.worldName = worldName;
        }

        World world() {
            return ensureWorld(worldName);
        }

        int minX() { return Math.min(floorStart.getBlockX(), floorEnd.getBlockX()); }
        int maxX() { return Math.max(floorStart.getBlockX(), floorEnd.getBlockX()); }
        int minZ() { return Math.min(floorStart.getBlockZ(), floorEnd.getBlockZ()); }
        int maxZ() { return Math.max(floorStart.getBlockZ(), floorEnd.getBlockZ()); }
        int floorY() { return floorStart.getBlockY(); }
        int width() { return maxX() - minX() + 1; }
        int length() { return maxZ() - minZ() + 1; }
        boolean contains(int x, int z) { return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ(); }
    }

    private static final class GameSession {
        final Arena arena;
        GameState state = GameState.IDLE;
        final Set<UUID> lobbyPlayers = new LinkedHashSet<>();
        final Set<UUID> participants = new LinkedHashSet<>();
        final Set<UUID> alive = new LinkedHashSet<>();
        final List<UUID> eliminatedOrder = new ArrayList<>();
        final Set<UUID> killOnJoin = new HashSet<>();

        BukkitTask countdownTask;
        BukkitTask roundTask;
        BukkitTask actionbarTask;
        BukkitTask finalCheckTask;

        boolean forcedCountdown;
        boolean pvpEnabled;
        boolean pvpTitleShown;
        boolean finalCheckStarted;
        int countdownLeft;
        int round;
        int phaseTicksLeft;
        int initialAliveCount;
        Material currentTarget;
        String currentTargetName = "";
        String currentPaletteName = "";
        BossBar aliveBossBar;

        GameSession(Arena arena) {
            this.arena = arena;
        }
    }

    private static final class Palette {
        final String name;
        final List<PaletteEntry> entries = new ArrayList<>();
        Palette(String name) { this.name = name; }
    }

    private record PaletteEntry(Material material, String display) { }
    private record ColorName(String prefix, String display) { }
    private record RoundPlan(Palette palette, List<PaletteEntry> colors, PaletteEntry target, PatternType pattern) { }

    @FunctionalInterface
    private interface FloorConsumer {
        void accept(int x, int y, int z);
    }
}
