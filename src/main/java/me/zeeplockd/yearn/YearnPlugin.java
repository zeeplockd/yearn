package me.zeeplockd.yearn;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class YearnPlugin extends JavaPlugin implements Listener {

    private Location point1 = new Location(Bukkit.getWorld("world"), -4, -32, 8);
    private Location point2 = new Location(Bukkit.getWorld("world"), 4, -2, 16);
    private Location spawn;
    private final Map<String, Integer> balances = new HashMap<>();
    private final Map<String, Integer> multipliers = new HashMap<>();

    // Cost parameters
    private final int baseCost = 100; // Initial cost for upgrade
    private final double exponentialFactor = 1.2; // Factor for exponential growth

    @Override
    public void onEnable() {
        // Ensure that the "balances" and "multipliers" sections exist
        FileConfiguration config = getConfig();

        if (!config.contains("balances")) {
            config.createSection("balances");
        }
        if (!config.contains("multipliers")) {
            config.createSection("multipliers");
        }

        // Register events and commands
        getServer().getPluginManager().registerEvents(this, this);

        // Load data from the config file
        loadData();

        // Setup repeating task for mine reset every 10 minutes
        new BukkitRunnable() {
            @Override
            public void run() {
                resetMine();
            }
        }.runTaskTimer(this, 0, 200 * 60); // 200 ticks * 60 = 10 minutes
    }

    @Override
    public void onDisable() {
        // Save data to the config file
        saveData();
    }

    // Commands
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("setspawn") && sender.hasPermission("op")) {
            spawn = ((Player) sender).getLocation();
            sender.sendMessage(ChatColor.GREEN + "Successfully set the spawn.");
            return true;
        } else if (command.getName().equalsIgnoreCase("spawn")) {
            if (spawn != null) {
                ((Player) sender).teleport(spawn);
                sender.sendMessage(ChatColor.GREEN + "Teleported to spawn.");
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("balance")) {
            if (sender instanceof Player) {
                String playerUUID = ((Player) sender).getUniqueId().toString();
                sender.sendMessage(ChatColor.GREEN + "Your balance is $" + balances.getOrDefault(playerUUID, 0));
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("givepickaxe") && sender.hasPermission("op")) {
            if (args.length == 1) {
                Player target = Bukkit.getPlayer(args[0]);
                if (target != null) {
                    givePickaxe(target);
                    sender.sendMessage(ChatColor.GREEN + "Gave " + args[0] + " a level 1 pickaxe.");
                }
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("upgradepickaxe")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                ItemStack pickaxe = player.getInventory().getItemInMainHand();

                if (pickaxe != null && pickaxe.hasItemMeta()) {
                    ItemMeta meta = pickaxe.getItemMeta();
                    if (meta != null && meta.getPersistentDataContainer().has(new NamespacedKey("yearn", "level"), PersistentDataType.BYTE)) {
                        byte level = meta.getPersistentDataContainer().get(new NamespacedKey("yearn", "level"), PersistentDataType.BYTE);
                        int cost = (int) (baseCost * Math.pow(exponentialFactor, level));

                        // Check if the player has enough money
                        String playerUUID = player.getUniqueId().toString();
                        int balance = balances.getOrDefault(playerUUID, 0);

                        if (balance >= cost) {
                            // If level is 100, prevent further upgrades and set to MAX
                            if (level == 100) {
                                meta.setDisplayName("§7Pickaxe §8[§cMAX§8]");
                                pickaxe.setType(Material.NETHERITE_PICKAXE);
                                player.sendMessage(ChatColor.RED + "Your pickaxe is already at MAX level!");
                            } else {
                                // Deduct the cost and upgrade the pickaxe
                                balances.put(playerUUID, balance - cost);

                                level++;
                                meta.getPersistentDataContainer().set(new NamespacedKey("yearn", "level"), PersistentDataType.BYTE, level);

                                // Upgrade the efficiency enchantment
                                meta.addEnchant(Enchantment.EFFICIENCY, level, true);

                                // Update the display name
                                meta.setDisplayName("§7Pickaxe §8[§b" + level + "§8]");

                                // If the level is 100, set the material to Netherite and MAX
                                if (level == 100) {
                                    meta.setDisplayName("§7Pickaxe §8[§cMAX§8]");
                                    pickaxe.setType(Material.NETHERITE_PICKAXE);
                                }

                                player.sendMessage(ChatColor.GREEN + "Your pickaxe has been upgraded to level " + level);
                            }
                            pickaxe.setItemMeta(meta);
                        } else {
                            int moneyNeeded = cost - balance;
                            player.sendMessage(ChatColor.RED + "You don't have enough money! You need $" + moneyNeeded + " more to upgrade.");
                        }
                    } else {
                        player.sendMessage(ChatColor.RED + "You must be holding a valid pickaxe to upgrade it.");
                    }
                } else {
                    player.sendMessage(ChatColor.RED + "You must be holding a valid pickaxe to upgrade it.");
                }
            }
            return true;
        }
        return false;
    }

    // Player join event
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerUUID = player.getUniqueId().toString();

        // Set default values for new players
        balances.putIfAbsent(playerUUID, 0);
        multipliers.putIfAbsent(playerUUID, 1);

        // Give pickaxe on first join
        if (!player.hasPlayedBefore()) {
            givePickaxe(player);
        }
    }

    // Player respawn event
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
    }

    // Block break event
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        // Check if the block is part of the mine
        if (isBlockInMine(block)) {
            String playerUUID = player.getUniqueId().toString();
            event.setCancelled(true); // Cancel drops to prevent default behavior

            // Add balance based on the block type
            int multiplier = multipliers.getOrDefault(playerUUID, 1);
            int addedMoney = 0;  // Initialize money to add

            // Assign money based on the block type
            if (block.getType() == Material.STONE) {
                addedMoney = 1 * multiplier;
            } else if (block.getType() == Material.COAL_ORE) {
                addedMoney = 2 * multiplier;
            } else if (block.getType() == Material.IRON_ORE) {
                addedMoney = 5 * multiplier;
            } else if (block.getType() == Material.DIAMOND_ORE) {
                addedMoney = 10 * multiplier;
            } else if (block.getType() == Material.OBSIDIAN) {
                addedMoney = 1000 * multiplier;
                // Broadcast message about lucky player
                Bukkit.broadcastMessage(ChatColor.AQUA + "Lucky player " + player.getName() + " just mined obsidian with a 0.01% chance!");
            }

            // Update the player's balance
            if (addedMoney > 0) {
                balances.put(playerUUID, balances.getOrDefault(playerUUID, 0) + addedMoney);
            }

            block.setType(Material.AIR);
        }
    }

    // Method to check if a block is within the mine area
    private boolean isBlockInMine(Block block) {
        Location loc = block.getLocation();
        return loc.getX() >= point1.getX() && loc.getX() <= point2.getX()
                && loc.getY() >= point1.getY() && loc.getY() <= point2.getY()
                && loc.getZ() >= point1.getZ() && loc.getZ() <= point2.getZ();
    }

    // Reset mine area - changes blocks based on random chances
    private void resetMine() {
        Random random = new Random();

        for (int x = point1.getBlockX(); x <= point2.getBlockX(); x++) {
            for (int y = point1.getBlockY(); y <= point2.getBlockY(); y++) {
                for (int z = point1.getBlockZ(); z <= point2.getBlockZ(); z++) {
                    Location loc = new Location(Bukkit.getWorld("world"), x, y, z);
                    Block block = loc.getBlock();

                    int chance = random.nextInt(100) + 1;
                    if (chance <= 80) {
                        block.setType(Material.STONE);
                    } else if (chance <= 90) {
                        block.setType(Material.COAL_ORE);
                    } else if (chance <= 95) {
                        block.setType(Material.IRON_ORE);
                    } else {
                        block.setType(Material.DIAMOND_ORE);
                        if (random.nextInt(1000) == 0) { // 0.1% chance
                            block.setType(Material.OBSIDIAN);
                        }
                    }
                }
            }
        }

        // Teleport players in the mine area
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isPlayerInMine(player)) {
                player.teleport(new Location(Bukkit.getWorld("world"), 0.5, -1, 12.5, 180, 0));
            }
        }
    }

    // Check if a player is within the mine area
    private boolean isPlayerInMine(Player player) {
        Location loc = player.getLocation();
        return loc.getX() >= point1.getX() && loc.getX() <= point2.getX()
                && loc.getY() >= point1.getY() && loc.getY() <= point2.getY()
                && loc.getZ() >= point1.getZ() && loc.getZ() <= point2.getZ();
    }

    // Give a custom pickaxe to the player
    private void givePickaxe(Player player) {
        ItemStack pickaxe = new ItemStack(Material.IRON_PICKAXE);
        ItemMeta meta = pickaxe.getItemMeta();

        if (meta != null) {
            NamespacedKey levelKey = new NamespacedKey("yearn", "level");
            meta.setDisplayName("§7Pickaxe §8[§b1§8]"); // Level 1
            meta.addEnchant(Enchantment.EFFICIENCY, 1, true); // Efficiency level 1
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            meta.getPersistentDataContainer().set(levelKey, PersistentDataType.BYTE, (byte) 1); // Set level as 1
            pickaxe.setItemMeta(meta);
        }

        player.getInventory().addItem(pickaxe);
    }

    // Load balances and multipliers from config
    private void loadData() {
        FileConfiguration config = getConfig();

        // Load balances
        if (config.getConfigurationSection("balances") != null) {
            for (String playerUUID : config.getConfigurationSection("balances").getKeys(false)) {
                balances.put(playerUUID, config.getInt("balances." + playerUUID));
            }
        }

        // Load multipliers
        if (config.getConfigurationSection("multipliers") != null) {
            for (String playerUUID : config.getConfigurationSection("multipliers").getKeys(false)) {
                multipliers.put(playerUUID, config.getInt("multipliers." + playerUUID));
            }
        }
    }

    // Save balances and multipliers to config
    private void saveData() {
        FileConfiguration config = getConfig();

        // Save balances
        for (Map.Entry<String, Integer> entry : balances.entrySet()) {
            config.set("balances." + entry.getKey(), entry.getValue());
        }

        // Save multipliers
        for (Map.Entry<String, Integer> entry : multipliers.entrySet()) {
            config.set("multipliers." + entry.getKey(), entry.getValue());
        }

        // Save the config file
        saveConfig();
    }

    // Get player's balance
    public int getBalance(Player player) {
        return balances.getOrDefault(player.getUniqueId().toString(), 0);
    }

    // Get player's multiplier
    public int getMultiplier(Player player) {
        return multipliers.getOrDefault(player.getUniqueId().toString(), 1);
    }

}
