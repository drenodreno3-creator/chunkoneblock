package at.oai.chunkoneblock;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public final class ChunkOneBlockPlugin extends JavaPlugin implements Listener {

    private final Set<String> randomizedChunks = new HashSet<>();
    private final Map<UUID, InventoryViewType> openMenus = new HashMap<>();
    private final Set<UUID> resetConfirmOpen = new HashSet<>();
    private final List<Material> rotationBag = new ArrayList<>();
    private final Set<UUID> hardcoreDeadPlayers = new HashSet<>();
    private final Random random = new Random();

    private NamespacedKey menuBookKey;
    private String managedWorldName;
    private boolean enabled;
    private boolean keepInventory;
    private boolean hardcoreMode;
    private List<Material> activeMaterials;
    private BukkitTask bookEnforcerTask;

    private static final int BOOK_SLOT = 8;
    private static final String MAIN_MENU_TITLE = "§8ChunkOneBlock";
    private static final String BLOCK_MENU_TITLE = "§8Block-Auswahl";
    private static final String RESET_MENU_TITLE = "§4Welt wirklich resetten?";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        menuBookKey = new NamespacedKey(this, "menu_book");
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("chunkoneblock")).setExecutor(this);
        startBookEnforcer();
        applyGameRules();
        getLogger().info("ChunkOneBlock aktiviert.");
    }

    @Override
    public void onDisable() {
        if (bookEnforcerTask != null) {
            bookEnforcerTask.cancel();
        }
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration cfg = getConfig();
        managedWorldName = cfg.getString("managed-world", "world");
        enabled = cfg.getBoolean("enabled", false);
        keepInventory = cfg.getBoolean("keep-inventory", false);
        hardcoreMode = cfg.getBoolean("hardcore-mode", false);
        activeMaterials = cfg.getStringList("active-materials").stream()
                .map(String::toUpperCase)
                .map(name -> {
                    try {
                        return Material.valueOf(name);
                    } catch (IllegalArgumentException ex) {
                        getLogger().warning("Ungültiges Material in config.yml: " + name);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(this::isAllowedPoolMaterial)
                .collect(Collectors.toCollection(ArrayList::new));

        if (activeMaterials.isEmpty()) {
            activeMaterials.add(Material.STONE);
        }
        refillRotationBag();
    }

    private void saveSettings() {
        FileConfiguration cfg = getConfig();
        cfg.set("managed-world", managedWorldName);
        cfg.set("enabled", enabled);
        cfg.set("keep-inventory", keepInventory);
        cfg.set("hardcore-mode", hardcoreMode);
        cfg.set("active-materials", activeMaterials.stream().map(Enum::name).toList());
        saveConfig();
        applyGameRules();
    }

    private void applyGameRules() {
        World world = Bukkit.getWorld(managedWorldName);
        if (world == null) return;
        world.setGameRule(GameRule.KEEP_INVENTORY, keepInventory);
    }

    private void startBookEnforcer() {
        if (bookEnforcerTask != null) bookEnforcerTask.cancel();
        bookEnforcerTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    ensureBook(player);
                }
            }
        }.runTaskTimer(this, 20L, 40L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler können diesen Befehl benutzen.");
            return true;
        }
        ensureBook(player);
        openMainMenu(player);
        return true;
    }

    private void ensureBook(Player player) {
        ItemStack current = player.getInventory().getItem(BOOK_SLOT);
        if (!isMenuBook(current)) {
            player.getInventory().setItem(BOOK_SLOT, createMenuBook());
        }
    }

    private ItemStack createMenuBook() {
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(getConfig().getString("menu-book-title", "§aChunkOneBlock Menü"));
            meta.setLore(getConfig().getStringList("menu-book-lore"));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(menuBookKey, PersistentDataType.BYTE, (byte) 1);
            book.setItemMeta(meta);
        }
        return book;
    }

    private boolean isMenuBook(ItemStack item) {
        if (item == null || item.getType() != Material.BOOK || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        Byte val = pdc.get(menuBookKey, PersistentDataType.BYTE);
        return val != null && val == (byte) 1;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ensureBook(event.getPlayer());
        if (hardcoreMode && hardcoreDeadPlayers.contains(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> event.getPlayer().setGameMode(GameMode.SPECTATOR), 1L);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isMenuBook(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            ensureBook(event.getPlayer());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMenuBook(event.getItem())) return;
        event.setCancelled(true);
        openMainMenu(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == BOOK_SLOT && isMenuBook(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }

        String title = event.getView().getTitle();
        if (MAIN_MENU_TITLE.equals(title)) {
            event.setCancelled(true);
            handleMainMenuClick(player, event);
            return;
        }
        if (BLOCK_MENU_TITLE.equals(title)) {
            event.setCancelled(true);
            handleBlockMenuClick(player, event);
            return;
        }
        if (RESET_MENU_TITLE.equals(title)) {
            event.setCancelled(true);
            handleResetMenuClick(player, event);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        openMenus.remove(event.getPlayer().getUniqueId());
        resetConfirmOpen.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!enabled) return;
        if (!event.getWorld().getName().equals(managedWorldName)) return;
        Bukkit.getScheduler().runTask(this, () -> randomizeChunkIfNeeded(event.getChunk()));
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!enabled || event.getTo() == null) return;
        if (event.getFrom().getChunk().equals(event.getTo().getChunk())) return;
        if (!event.getTo().getWorld().getName().equals(managedWorldName)) return;
        randomizeChunkIfNeeded(event.getTo().getChunk());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.setKeepInventory(keepInventory);
        Player player = event.getEntity();
        if (!hardcoreMode) {
            hardcoreDeadPlayers.remove(player.getUniqueId());
            return;
        }

        hardcoreDeadPlayers.add(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            player.spigot().respawn();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                player.setGameMode(GameMode.SPECTATOR);
                player.sendMessage("§cHardcore ist aktiv. Du bist jetzt Zuschauer bis zum Server-Neustart.");
            }, 1L);
        }, 2L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!hardcoreMode) {
            event.getPlayer().setGameMode(GameMode.SURVIVAL);
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player player = event.getPlayer();
            if (hardcoreDeadPlayers.contains(player.getUniqueId())) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        }, 1L);
    }

    private void handleMainMenuClick(Player player, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        switch (slot) {
            case 10 -> {
                enabled = true;
                saveSettings();
                player.sendMessage("§aChunkOneBlock gestartet.");
                openMainMenu(player);
            }
            case 12 -> {
                enabled = false;
                saveSettings();
                player.sendMessage("§eChunkOneBlock pausiert.");
                openMainMenu(player);
            }
            case 14 -> openResetMenu(player);
            case 16 -> openBlockMenu(player, 0);
            case 28 -> {
                keepInventory = !keepInventory;
                saveSettings();
                player.sendMessage("§bKeepInventory: " + (keepInventory ? "AN" : "AUS"));
                openMainMenu(player);
            }
            case 30 -> {
                hardcoreMode = !hardcoreMode;
                if (!hardcoreMode) {
                    hardcoreDeadPlayers.clear();
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (online.getGameMode() == GameMode.SPECTATOR) {
                            online.setGameMode(GameMode.SURVIVAL);
                        }
                    }
                }
                saveSettings();
                player.sendMessage("§dModus: " + (hardcoreMode ? "§cHardcore / Zuschauer bis Neustart" : "§aWiederbelebung / normaler Respawn"));
                openMainMenu(player);
            }
            case 32 -> {
                player.closeInventory();
                randomizeChunkIfNeeded(player.getLocation().getChunk(), true);
                player.sendMessage("§aAktueller Chunk wurde neu randomisiert.");
            }
            default -> {
            }
        }
    }

    private void handleBlockMenuClick(Player player, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        Inventory inv = event.getInventory();
        int page = getPageFromInventory(inv);

        if (slot == 45 && page > 0) {
            openBlockMenu(player, page - 1);
            return;
        }
        if (slot == 49) {
            openMainMenu(player);
            return;
        }
        if (slot == 53 && hasMorePages(page)) {
            openBlockMenu(player, page + 1);
            return;
        }

        if (slot < 0 || slot >= 45) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        Material material = clicked.getType();

        if (activeMaterials.contains(material)) {
            if (activeMaterials.size() == 1) {
                player.sendMessage("§cMindestens ein Block muss aktiv bleiben.");
                return;
            }
            activeMaterials.remove(material);
            player.sendMessage("§eDeaktiviert: §f" + formatName(material));
        } else {
            activeMaterials.add(material);
            player.sendMessage("§aAktiviert: §f" + formatName(material));
        }

        refillRotationBag();
        saveSettings();
        openBlockMenu(player, page);
    }

    private void handleResetMenuClick(Player player, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == 11) {
            player.closeInventory();
            resetManagedWorld(player);
        } else if (slot == 15) {
            openMainMenu(player);
        }
    }

    private void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 45, MAIN_MENU_TITLE);
        inv.setItem(10, menuItem(enabled ? Material.LIME_CONCRETE : Material.LIME_STAINED_GLASS_PANE,
                "§aStarten", List.of("§7Aktiviert den Randomizer")));
        inv.setItem(12, menuItem(!enabled ? Material.YELLOW_CONCRETE : Material.YELLOW_STAINED_GLASS_PANE,
                "§ePausieren", List.of("§7Pausiert den Randomizer")));
        inv.setItem(14, menuItem(Material.RED_CONCRETE, "§cWelt resetten", List.of("§7Löscht nur die Challenge-Welt", "§7und erstellt sie neu.")));
        inv.setItem(16, menuItem(Material.CHEST, "§6Block-Auswahl", List.of("§7Blöcke aktivieren/deaktivieren", "§7Aktiv: " + activeMaterials.size())));
        inv.setItem(28, menuItem(keepInventory ? Material.TOTEM_OF_UNDYING : Material.BARRIER,
                "§bKeepInventory", List.of("§7Status: " + (keepInventory ? "§aAN" : "§cAUS"))));
        inv.setItem(30, menuItem(hardcoreMode ? Material.WITHER_SKELETON_SKULL : Material.PLAYER_HEAD,
                "§dHardcore / Wiederbelebung", List.of("§7Status: " + (hardcoreMode ? "§cHardcore" : "§aWiederbelebung"))));
        inv.setItem(32, menuItem(Material.COMPASS, "§aAktuellen Chunk randomisieren", List.of("§7Nützlich zum Testen")));
        inv.setItem(40, menuItem(Material.BOOK, "§fStatus", List.of(
                "§7Welt: §f" + managedWorldName,
                "§7Randomizer: " + (enabled ? "§aAN" : "§cAUS"),
                "§7Aktive Blöcke: §f" + activeMaterials.size()
        )));
        fillBorders(inv);
        openMenus.put(player.getUniqueId(), InventoryViewType.MAIN);
        player.openInventory(inv);
    }

    private void openBlockMenu(Player player, int page) {
        Inventory inv = Bukkit.createInventory(null, 54, BLOCK_MENU_TITLE);
        setPageOnInventory(inv, page);

        List<Material> allBlocks = Arrays.stream(Material.values())
                .filter(this::isAllowedPoolMaterial)
                .sorted(Comparator.comparing(Enum::name))
                .toList();

        int start = page * 45;
        int end = Math.min(start + 45, allBlocks.size());
        for (int i = start; i < end; i++) {
            Material mat = allBlocks.get(i);
            boolean active = activeMaterials.contains(mat);
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName((active ? "§a" : "§c") + formatName(mat));
                meta.setLore(List.of(active ? "§7Klick zum Deaktivieren" : "§7Klick zum Aktivieren"));
                item.setItemMeta(meta);
            }
            inv.setItem(i - start, item);
        }

        inv.setItem(45, menuItem(Material.ARROW, "§fZurück", List.of("§7Vorherige Seite")));
        inv.setItem(49, menuItem(Material.BOOK, "§eHauptmenü", List.of("§7Zurück zum Hauptmenü")));
        inv.setItem(53, menuItem(Material.ARROW, "§fWeiter", List.of("§7Nächste Seite")));
        openMenus.put(player.getUniqueId(), InventoryViewType.BLOCKS);
        player.openInventory(inv);
    }

    private void openResetMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, RESET_MENU_TITLE);
        inv.setItem(11, menuItem(Material.LIME_CONCRETE, "§aJa, Welt resetten", List.of("§7Die verwaltete Welt wird gelöscht", "§7und neu erstellt.")));
        inv.setItem(15, menuItem(Material.RED_CONCRETE, "§cNein, abbrechen", List.of("§7Zurück zum Menü")));
        fillBorders(inv);
        resetConfirmOpen.add(player.getUniqueId());
        player.openInventory(inv);
    }

    private void fillBorders(Inventory inv) {
        ItemStack filler = menuItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    private ItemStack menuItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private int getPageFromInventory(Inventory inventory) {
        ItemStack marker = inventory.getItem(52);
        if (marker == null || !marker.hasItemMeta() || marker.getItemMeta().getLore() == null) return 0;
        try {
            return Integer.parseInt(Objects.requireNonNull(marker.getItemMeta().getLore()).get(0));
        } catch (Exception ex) {
            return 0;
        }
    }

    private void setPageOnInventory(Inventory inventory, int page) {
        ItemStack marker = new ItemStack(Material.PAPER);
        ItemMeta meta = marker.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§7Seite " + (page + 1));
            meta.setLore(List.of(String.valueOf(page)));
            marker.setItemMeta(meta);
        }
        inventory.setItem(52, marker);
    }


    private boolean hasMorePages(int page) {
        long total = Arrays.stream(Material.values())
                .filter(this::isAllowedPoolMaterial)
                .count();
        return (page + 1) * 45 < total;
    }

    private boolean isAllowedPoolMaterial(Material material) {
        if (material == null || !material.isBlock() || material.isAir() || material.isLegacy()) return false;

        String name = material.name();

        if (name.contains("WALL") || name.contains("SLAB") || name.contains("STAIRS") || name.contains("FENCE")
                || name.contains("GATE") || name.contains("DOOR") || name.contains("TRAPDOOR")
                || name.contains("BUTTON") || name.contains("PRESSURE_PLATE") || name.contains("SIGN")
                || name.contains("BANNER") || name.contains("TORCH") || name.contains("LANTERN")
                || name.contains("CANDLE") || name.contains("RAIL") || name.contains("VINE")
                || name.contains("SAPLING") || name.contains("LEAVES") || name.contains("FLOWER")
                || name.contains("TULIP") || name.contains("BUSH") || name.contains("CORAL")
                || name.contains("FAN") || name.contains("SEAGRASS") || name.contains("KELP")
                || name.contains("MUSHROOM") || name.contains("ROOTS") || name.contains("CARPET")
                || name.contains("GLASS_PANE") || name.contains("HEAD") || name.contains("SKULL")
                || name.contains("POT") || name.contains("BED") || name.contains("BELL")
                || name.contains("CHAIN") || name.contains("AMETHYST_CLUSTER") || name.contains("BUD")
                || name.contains("POINTED_DRIPSTONE") || name.contains("SCAFFOLDING") || name.contains("CAKE")
                || name.contains("CROP") || name.contains("STEM") || name.contains("PICKLE")
                || name.contains("SNOW") || name.contains("FIRE") || name.contains("PORTAL")) {
            return false;
        }

        if (material == Material.SAND || material == Material.RED_SAND || material == Material.GRAVEL) {
            return true;
        }

        return material.isSolid() && material.isOccluding();
    }

    private String chunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    private void randomizeChunkIfNeeded(Chunk chunk) {
        randomizeChunkIfNeeded(chunk, false);
    }

    private void randomizeChunkIfNeeded(Chunk chunk, boolean force) {
        if (chunk == null || !chunk.getWorld().getName().equals(managedWorldName)) return;
        String key = chunkKey(chunk);
        if (!force && randomizedChunks.contains(key)) return;
        Material replacement = nextMaterial();
        World world = chunk.getWorld();

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (block.getType() == Material.AIR) continue;
                    if (block.isLiquid()) continue;
                    if (block.getType() == Material.BEDROCK) continue;
                    block.setType(replacement, false);
                }
            }
        }
        randomizedChunks.add(key);
    }

    private Material nextMaterial() {
        if (rotationBag.isEmpty()) {
            refillRotationBag();
        }
        return rotationBag.remove(0);
    }

    private void refillRotationBag() {
        rotationBag.clear();
        rotationBag.addAll(activeMaterials);
        Collections.shuffle(rotationBag, random);
    }

    private String formatName(Material material) {
        String raw = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String part : raw.split(" ")) {
            if (part.isEmpty()) continue;
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return out.toString().trim();
    }

    private void resetManagedWorld(Player initiator) {
        World managed = Bukkit.getWorld(managedWorldName);
        if (managed == null) {
            initiator.sendMessage("§cDie verwaltete Welt wurde nicht gefunden: " + managedWorldName);
            return;
        }

        World fallback = Bukkit.getWorlds().stream()
                .filter(w -> !w.getName().equals(managedWorldName))
                .findFirst()
                .orElse(null);

        if (fallback == null) {
            initiator.sendMessage("§cEs gibt keine sichere Ausweichwelt zum Teleportieren.");
            return;
        }

        Location safe = fallback.getSpawnLocation();
        for (Player online : managed.getPlayers()) {
            online.teleport(safe);
            online.sendMessage("§eDie Challenge-Welt wird gerade neu erstellt...");
        }

        for (Entity entity : managed.getEntities()) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }

        String worldName = managed.getName();
        File worldFolder = managed.getWorldFolder();

        if (!Bukkit.unloadWorld(managed, false)) {
            initiator.sendMessage("§cDie Welt konnte nicht entladen werden.");
            return;
        }

        try {
            deleteRecursively(worldFolder);
        } catch (IOException e) {
            initiator.sendMessage("§cWeltordner konnte nicht gelöscht werden: " + e.getMessage());
            getLogger().warning("Fehler beim Löschen von " + worldFolder + ": " + e.getMessage());
            return;
        }

        WorldCreator creator = new WorldCreator(worldName);
        World newWorld = creator.createWorld();
        if (newWorld == null) {
            initiator.sendMessage("§cDie Welt konnte nicht neu erstellt werden.");
            return;
        }

        randomizedChunks.clear();
        if (!hardcoreMode) {
            hardcoreDeadPlayers.clear();
        }
        applyGameRules();
        initiator.sendMessage("§aDie Welt wurde neu erstellt.");
    }

    private void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        Files.walk(file.toPath())
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private enum InventoryViewType {
        MAIN,
        BLOCKS
    }
}
