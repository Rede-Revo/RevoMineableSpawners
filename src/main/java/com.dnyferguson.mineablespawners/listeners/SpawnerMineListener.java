package com.dnyferguson.mineablespawners.listeners;

import com.cryptomorin.xseries.XMaterial;
import com.dnyferguson.mineablespawners.MineableSpawners;
import com.dnyferguson.mineablespawners.utils.Chat;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.util.*;

import static com.dnyferguson.mineablespawners.utils.TranslateMobs.getTranslatedName;

public class SpawnerMineListener implements Listener {
    private final MineableSpawners plugin;
    private final Set<Location> minedSpawners = new HashSet<>();
    private final Map<String, Double> permissionChances = new HashMap<>();
    private final Map<EntityType, Double> prices = new HashMap<>();
    private boolean allSamePrice = false;
    private double globalPrice = 0;
    private final DecimalFormat df = new DecimalFormat("##.##");

    public SpawnerMineListener(MineableSpawners plugin) {
        this.plugin = plugin;
        for (String line : plugin.getConfigurationHandler().getList("mining", "perm-based-chances")) {
            String[] args = line.split(":");
            try {
                String permission = args[0];
                double chance = Double.parseDouble(args[1]);
                permissionChances.put(permission, chance);
            } catch (Exception ignore) {
            }
        }
        for (String line : plugin.getConfigurationHandler().getList("mining", "prices")) {
            try {
                String[] args = line.split(":");
                if (args[0].equalsIgnoreCase("all")) {
                    allSamePrice = true;
                    globalPrice = Double.parseDouble(args[1]);
                    break;
                }
                EntityType type = EntityType.valueOf(args[0].toUpperCase());
                double price = Double.parseDouble(args[1]);
                prices.put(type, price);
            } catch (Exception ignore) {
                plugin.getLogger().info("Error with mining price \"" + line + "\"");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawnerMine(BlockBreakEvent e) {
        if (e.isCancelled()) {
            return;
        }

        // check if block is spawner
        Block block = e.getBlock();
        Material material = block.getType();
        if (!material.equals(XMaterial.SPAWNER.parseMaterial())) {
            return;
        }

        // get spawner location & type
        Location loc = block.getLocation();
        CreatureSpawner spawner = (CreatureSpawner) block.getState();
        EntityType entityType = spawner.getSpawnedType();

        // check if should give exp
        if (!plugin.getConfigurationHandler().getBoolean("mining", "drop-exp") || minedSpawners.contains(loc)) {
            e.setExpToDrop(0);
        }

        // check if bypassing
        Player player = e.getPlayer();
        boolean bypassing = player.getGameMode().equals(GameMode.CREATIVE) || player.hasPermission("mineablespawners.bypass");
        if (bypassing) {
            giveSpawner(e, entityType, loc, player, block, 0);
            return;
        }

        // check if blacklisted world
        if (plugin.getConfigurationHandler().getList("mining", "blacklisted-worlds").contains(player.getWorld().getName())) {
            player.sendMessage(plugin.getConfigurationHandler().getMessage("mining", "blacklisted"));
            e.setCancelled(true);
            return;
        }

        // check if requiring permission
        if (plugin.getConfigurationHandler().getBoolean("mining", "require-permission")) {
            if (!player.hasPermission("mineablespawners.mine")) {
                handleStillBreak(e, player, plugin.getConfigurationHandler().getMessage("mining", "no-permission"), plugin.getConfigurationHandler().getMessage("mining", "requirements.permission"));
                return;
            }
        }

        if (plugin.getConfigurationHandler().getBoolean("mining", "require-individual-permission")) {
            if (!player.hasPermission("mineablespawners.mine." + entityType.name().toLowerCase())) {
                handleStillBreak(e, player, plugin.getConfigurationHandler().getMessage("mining", "no-individual-permission"), plugin.getConfigurationHandler().getMessage("mining", "requirements.individual-permission"));
                return;
            }
        }

        // check if right tool
        Material tool = player.getInventory().getItemInHand().getType();
        if (!plugin.getConfigurationHandler().getList("mining", "tools").contains(tool.name())) {
            handleStillBreak(e, player, plugin.getConfigurationHandler().getMessage("mining", "wrong-tool"), plugin.getConfigurationHandler().getMessage("mining", "requirements.wrong-tool"));
            return;
        }

        // check if requiring silktouch
        ItemStack itemInHand = player.getInventory().getItemInHand();
        if (plugin.getConfigurationHandler().getBoolean("mining", "require-silktouch") && !player.hasPermission("mineablespawners.nosilk")) {
            int silkTouchLevel = 0;
            if (itemInHand.containsEnchantment(Enchantment.SILK_TOUCH)) {
                silkTouchLevel = itemInHand.getEnchantmentLevel(Enchantment.SILK_TOUCH);
            }
            if (plugin.getConfigurationHandler().getBoolean("mining", "require-silktouch-level")) {
                int requiredLevel = plugin.getConfigurationHandler().getInteger("mining", "required-level");
                if (silkTouchLevel < requiredLevel) {
                    handleStillBreak(e, player, plugin.getConfigurationHandler().getMessage("mining", "not-level-required"), plugin.getConfigurationHandler().getMessage("mining", "requirements.silktouch-level"));
                    return;
                }
            } else {
                if (silkTouchLevel < 1) {
                    handleStillBreak(e, player, plugin.getConfigurationHandler().getMessage("mining", "no-silktouch"), plugin.getConfigurationHandler().getMessage("mining", "requirements.silktouch"));
                    return;
                }
            }
        }

        // check if charging/has enough
        double cost = 0;
        if (plugin.getEcon() != null && plugin.getConfigurationHandler().getBoolean("mining", "charge")) {
            if (!allSamePrice && prices.containsKey(entityType)) {
                cost = prices.getOrDefault(entityType, 0.0);
            } else {
                cost = globalPrice;
            }

            if (!plugin.getEcon().withdrawPlayer(player, cost).transactionSuccess()) {
                String missing = df.format(cost - plugin.getEcon().getBalance(player));
                player.sendMessage(plugin.getConfigurationHandler().getMessage("mining", "not-enough-money").replace("%missing%", missing).replace("%cost%", cost + ""));
                e.setCancelled(true);
                return;
            }
        }

// check chances
        double dropChance = 0.0;

// Verifica chances baseadas em permissões
        if (plugin.getConfigurationHandler().getBoolean("mining", "use-perm-based-chances") && !permissionChances.isEmpty()) {
            for (String perm : permissionChances.keySet()) {
                if (player.hasPermission(perm)) {
                    dropChance = permissionChances.get(perm); // Usa a chance base da permissão
                    break;
                }
            }
        } else {
            dropChance = plugin.getConfigurationHandler().getDouble("mining", "chance");
        }

// Bônus baseado no Silk Touch
        int silkTouchLevel = 0;
        if (itemInHand.containsEnchantment(Enchantment.SILK_TOUCH)) {
            silkTouchLevel = itemInHand.getEnchantmentLevel(Enchantment.SILK_TOUCH);
        }

// Apenas Silk Touch >= 2 fornece bônus
        if (silkTouchLevel >= 2) {
            int bonusLevels = silkTouchLevel - 1; // Silk Touch 2 -> 1x, Silk Touch 3 -> 2x, etc.
            double silkTouchBonus = bonusLevels * plugin.getConfigurationHandler().getDouble("mining", "silktouch-chance-boost");
            dropChance += silkTouchBonus; // Adiciona o bônus à chance base
        }

// Limitar a chance a 100%
        if (dropChance > 100.0) {
            dropChance = 100.0;
        }

// Verificação final de chance
        if (Math.random() * 100 >= dropChance) { // Converte chance para escala de 0-100
            plugin.getConfigurationHandler().sendMessage("mining", "out-of-luck", player);
            return;
        }

        // handle giving spawner
        giveSpawner(e, entityType, loc, player, block, cost);
    }

    private void giveSpawner(BlockBreakEvent e, EntityType entityType, Location loc, Player player, Block block, double cost) {
        String translatedName = getTranslatedName(entityType.name());

        ItemStack item = MineableSpawners.getApi().getSpawnerFromEntityType(entityType);

        // Modificar o nome do spawner para o nome traduzido
        String mobFormatted = Chat.uppercaseStartingLetters(translatedName);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Chat.format(plugin.getConfigurationHandler().getMessage("global", "name").replace("%mob%", mobFormatted)));
            List<String> newLore = new ArrayList<>();
            if (plugin.getConfigurationHandler().getList("global", "lore") != null && plugin.getConfigurationHandler().getBoolean("global", "lore-enabled")) {
                for (String line : plugin.getConfigurationHandler().getList("global", "lore")) {
                    newLore.add(Chat.format(line).replace("%mob%", mobFormatted));
                }
                meta.setLore(newLore);
            }
            item.setItemMeta(meta);
        }

        if (cost > 0) {
            player.sendMessage(plugin.getConfigurationHandler()
                    .getMessage("mining", "transaction-success")
                    .replace("%type%", Chat.uppercaseStartingLetters(translatedName))
                    .replace("%cost%", df.format(cost))
                    .replace("%balance%", df.format(plugin.getEcon().getBalance(player))));
        }

        if (plugin.getConfigurationHandler().getBoolean("mining", "drop-to-inventory")) {
            if (player.getInventory().firstEmpty() == -1) {
                e.setCancelled(true);
                player.sendMessage(plugin.getConfigurationHandler().getMessage("mining", "inventory-full"));
                return;
            }
            minedSpawners.add(loc);
            player.getInventory().addItem(item);
            block.getDrops().clear();
            return;
        }

        minedSpawners.add(loc);
        loc.getWorld().dropItemNaturally(loc, item);
    }

    private void handleStillBreak(BlockBreakEvent e, Player player, String msg, String reason) {
        if (!plugin.getConfigurationHandler().getBoolean("mining", "still-break")) {
            e.setCancelled(true);
            player.sendMessage(Chat.format(msg));
            return;
        }

        if (msg.length() > 0) {
            player.sendMessage(plugin.getConfigurationHandler().getMessage("mining", "still-break").replace("%requirement%", reason));
        }
    }
}
