package at.oai.chunkoneblock;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class ChunkOneBlockPlugin extends JavaPlugin implements Listener {

    private boolean enabled = true;
    private final String managedWorldName = "world"; // Weltname
    private final Set<String> modifiedChunks = new HashSet<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("ChunkOneBlock aktiviert!");
    }

    @Override
    public void onDisable() {
        getLogger().info("ChunkOneBlock deaktiviert!");
    }

    // 🔥 WICHTIG: Nur beim Betreten eines neuen Chunks
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!enabled || event.getTo() == null) return;

        Chunk from = event.getFrom().getChunk();
        Chunk to = event.getTo().getChunk();

        if (from.equals(to)) return;

        World world = event.getTo().getWorld();
        if (!world.getName().equals(managedWorldName)) return;

        randomizeChunkIfNeeded(to);
    }

    private void randomizeChunkIfNeeded(Chunk chunk) {
        String key = chunk.getX() + "," + chunk.getZ();

        if (modifiedChunks.contains(key)) return;

        modifiedChunks.add(key);

        generateOneBlock(chunk);
    }

    private void generateOneBlock(Chunk chunk) {
        int x = 8;
        int z = 8;
        int y = chunk.getWorld().getHighestBlockYAt(chunk.getBlock(x, 0, z).getLocation());

        Material material = getRandomBlock();

        chunk.getBlock(x, y, z).setType(material);
    }

    private Material getRandomBlock() {
        Material[] blocks = {
                Material.STONE,
                Material.DIAMOND_BLOCK,
                Material.GRASS_BLOCK,
                Material.DIRT,
                Material.OAK_LOG,
                Material.IRON_BLOCK,
                Material.GOLD_BLOCK
        };

        return blocks[random.nextInt(blocks.length)];
    }
}
