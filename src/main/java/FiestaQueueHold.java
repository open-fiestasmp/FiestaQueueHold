package com.fiesta.queue;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.projectiles.ProjectileSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FiestaQueueHold extends JavaPlugin implements Listener {
    private static final Pattern OUTBOX_LINE = Pattern.compile(
            "^\\{\\\"eventId\\\":\\\"([0-9a-fA-F-]{36})\\\",\\\"victimUuid\\\":\\\"([0-9a-fA-F-]{36})\\\",\\\"killerUuid\\\":(null|\\\"([0-9a-fA-F-]{36})\\\")\\}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Object outboxLock = new Object();
    private final List<PendingPvpEvent> pendingEvents = new ArrayList<>();
    private List<String> tips;
    private Location waitingSpawn;
    private Path outboxPath;
    private URI coordinatorUri;
    private String statsNode;
    private byte[] hmacKey;
    private HttpClient httpClient;
    private long lastStatsWarningMs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tips = getConfig().getStringList("queue-tips");
        if (tips.isEmpty()) tips = List.of("Your spot updates in the action bar.");
        for (World world : Bukkit.getWorlds()) {
            configureWaitingWorld(world);
            if (waitingSpawn == null) waitingSpawn = prepareWaitingSpawn(world);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        initializeStatsBridge();
        Bukkit.getScheduler().runTaskTimer(this, this::showRandomTip, 600L, 900L);
        getLogger().info("Queue waiting room protection, PvP kit, public chat logging, and rotating tips enabled.");
    }

    @Override
    public void onDisable() {
        synchronized (outboxLock) {
            try {
                persistPendingEvents();
            } catch (IOException error) {
                getLogger().severe("Could not persist the pending queue PvP stats outbox: " + error.getClass().getSimpleName());
            }
        }
    }

    private void configureWaitingWorld(World world) {
        world.setTime(18_000L);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, false);
        world.setPVP(true);
        world.getWorldBorder().setCenter(0.0, 0.0);
        world.getWorldBorder().setSize(256.0);
        world.getWorldBorder().setDamageBuffer(0.0);
        world.getWorldBorder().setDamageAmount(0.0);
        world.getWorldBorder().setWarningDistance(0);
    }

    private Location prepareWaitingSpawn(World world) {
        int x = 0;
        int z = 0;
        int floorY = Math.max(world.getMinHeight() + 2, world.getHighestBlockYAt(x, z) + 1);
        if (world.getBlockAt(x, floorY - 1, z).getType().isAir()) {
            world.getBlockAt(x, floorY - 1, z).setType(Material.END_STONE, false);
        }
        world.setSpawnLocation(x, floorY, z);
        return new Location(world, x + 0.5, floorY, z + 0.5);
    }

    private void initializeStatsBridge() {
        String endpoint = getConfig().getString("stats.coordinator-url", "").trim();
        statsNode = getConfig().getString("stats.node-id", "").trim();
        String keyText = getConfig().getString("stats.hmac-key-hex", "").trim();
        if (endpoint.isEmpty() || statsNode.isEmpty() || !keyText.matches("(?i)[0-9a-f]{64,}") || keyText.length() % 2 != 0) {
            getLogger().warning("Queue PvP stats bridge is not configured; local queue PvP still works.");
            return;
        }
        try {
            coordinatorUri = URI.create(endpoint);
            hmacKey = hexToBytes(keyText);
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            outboxPath = getDataFolder().toPath().resolve("pvp-outbox.jsonl");
            Files.createDirectories(getDataFolder().toPath());
            loadPendingEvents();
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::flushPendingEvents, 20L, 20L);
            getLogger().info("Queue PvP stats bridge loaded with durable retry outbox.");
        } catch (Exception error) {
            coordinatorUri = null;
            hmacKey = null;
            getLogger().warning("Queue PvP stats bridge could not start: " + error.getClass().getSimpleName());
        }
    }

    private void loadPendingEvents() throws IOException {
        synchronized (outboxLock) {
            pendingEvents.clear();
            if (!Files.exists(outboxPath)) return;
            for (String line : Files.readAllLines(outboxPath, StandardCharsets.UTF_8)) {
                Matcher matcher = OUTBOX_LINE.matcher(line.trim());
                if (!matcher.matches()) continue;
                pendingEvents.add(new PendingPvpEvent(matcher.group(1), matcher.group(2), matcher.group(4)));
            }
            persistPendingEvents();
        }
    }

    private void enqueuePvpEvent(Player victim, Player killer) {
        if (coordinatorUri == null || hmacKey == null) return;
        PendingPvpEvent event = new PendingPvpEvent(
                java.util.UUID.randomUUID().toString(),
                victim.getUniqueId().toString(),
                killer == null ? null : killer.getUniqueId().toString());
        synchronized (outboxLock) {
            try (FileChannel channel = FileChannel.open(outboxPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                channel.write(ByteBuffer.wrap((event.toJson() + "\n").getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
                pendingEvents.add(event);
            } catch (IOException error) {
                getLogger().severe("Could not durably queue PvP stats event: " + error.getClass().getSimpleName());
            }
        }
    }

    private void flushPendingEvents() {
        List<PendingPvpEvent> copy;
        synchronized (outboxLock) {
            copy = new ArrayList<>(pendingEvents);
        }
        for (PendingPvpEvent event : copy) {
            try {
                if (postEvent(event)) {
                    synchronized (outboxLock) {
                        pendingEvents.removeIf(candidate -> candidate.eventId.equals(event.eventId));
                        persistPendingEvents();
                    }
                } else {
                    warnStatsBridgeOnce("coordinator rejected a queue PvP stats event");
                    return;
                }
            } catch (Exception error) {
                warnStatsBridgeOnce("coordinator is unavailable; queue PvP stats remain in the retry outbox");
                return;
            }
        }
    }

    private boolean postEvent(PendingPvpEvent event) throws Exception {
        String body = event.toCoordinatorJson();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        byte[] nonceBytes = new byte[18];
        RANDOM.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String digest = toHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        String signed = timestamp + "\n" + nonce + "\n" + statsNode + "\n" + digest;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
        String signature = toHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(coordinatorUri)
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .header("X-Fiesta-Node", statsNode)
                .header("X-Fiesta-Timestamp", timestamp)
                .header("X-Fiesta-Nonce", nonce)
                .header("X-Fiesta-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private void persistPendingEvents() throws IOException {
        if (outboxPath == null) return;
        Files.createDirectories(outboxPath.getParent());
        Path temporary = outboxPath.resolveSibling(outboxPath.getFileName() + ".tmp");
        StringBuilder content = new StringBuilder();
        for (PendingPvpEvent event : pendingEvents) content.append(event.toJson()).append('\n');
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(content.toString().getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        try {
            Files.move(temporary, outboxPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, outboxPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void warnStatsBridgeOnce(String message) {
        long now = System.currentTimeMillis();
        if (now - lastStatsWarningMs >= 60_000L) {
            lastStatsWarningMs = now;
            getLogger().warning(message);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }

    private void giveStarterKit(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[]{
                new ItemStack(Material.IRON_BOOTS),
                new ItemStack(Material.IRON_LEGGINGS),
                new ItemStack(Material.IRON_CHESTPLATE),
                new ItemStack(Material.IRON_HELMET)
        });
        inventory.setItem(0, new ItemStack(Material.IRON_SWORD));
        inventory.setItem(1, new ItemStack(Material.IRON_PICKAXE));
        inventory.setItem(2, new ItemStack(Material.IRON_AXE));
        inventory.setItem(3, new ItemStack(Material.IRON_SHOVEL));
        inventory.setItem(4, new ItemStack(Material.IRON_HOE));
        inventory.setItem(5, new ItemStack(Material.APPLE, 64));
        inventory.setItemInOffHand(new ItemStack(Material.SHIELD));
        inventory.setHeldItemSlot(0);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
    }

    private void showRandomTip() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        String tip = tips.get(ThreadLocalRandom.current().nextInt(tips.size()));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle(ChatColor.GOLD + "Fiesta Queue", ChatColor.YELLOW + tip, 8, 50, 12);
        }
        getLogger().info("QUEUE_TIP: " + tip);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            for (World world : Bukkit.getWorlds()) configureWaitingWorld(world);
            player.setGameMode(GameMode.ADVENTURE);
            player.teleport(waitingSpawn);
            giveStarterKit(player);
            String tip = tips.get(ThreadLocalRandom.current().nextInt(tips.size()));
            player.sendTitle(ChatColor.GOLD + "Fiesta Queue", ChatColor.YELLOW + tip, 8, 60, 12);
        });
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && (killer == victim || killer.getWorld() != victim.getWorld())) killer = null;
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(false);
        event.setKeepLevel(false);
        event.setNewExp(0);
        event.setNewLevel(0);
        event.setNewTotalExp(0);
        enqueuePvpEvent(victim, killer);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = event.getPlayer();
            player.setGameMode(GameMode.ADVENTURE);
            giveStarterKit(player);
        });
    }

    @EventHandler public void onBreak(BlockBreakEvent event) { event.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent event) { event.setCancelled(true); }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null) event.setCancelled(true);
    }

    @EventHandler public void onInteractEntity(PlayerInteractEntityEvent event) { event.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent event) { event.setCancelled(true); }
    @EventHandler public void onPickup(EntityPickupItemEvent event) { event.setCancelled(true); }
    @EventHandler public void onInventory(InventoryClickEvent event) { event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent playerDamage
                && event.getEntity() instanceof Player victim) {
            Player attacker = null;
            if (playerDamage.getDamager() instanceof Player direct) {
                attacker = direct;
            } else if (playerDamage.getDamager() instanceof Projectile projectile) {
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof Player ranged) attacker = ranged;
            }
            if (attacker != null && attacker != victim && attacker.getWorld() == victim.getWorld()) {
                event.setCancelled(false);
                return;
            }
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        getLogger().info("QUEUE_CHAT " + event.getPlayer().getName() + ": " + message);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().toLowerCase(Locale.ROOT).trim();
        String command = raw.split("\\s+", 2)[0].replaceFirst("^/", "");
        if (List.of("queue", "joinqueue", "joinq", "move", "leavequeue", "leaveq", "listqueues", "listq", "server").contains(command)) return;
        event.setCancelled(true);
    }

    private record PendingPvpEvent(String eventId, String victimUuid, String killerUuid) {
        private String toJson() {
            return "{\"eventId\":\"" + eventId + "\",\"victimUuid\":\"" + victimUuid
                    + "\",\"killerUuid\":" + (killerUuid == null ? "null" : "\"" + killerUuid + "\"") + "}";
        }

        private String toCoordinatorJson() {
            return "{\"action\":\"record-pvp-event\"," + toJson().substring(1);
        }
    }
}
