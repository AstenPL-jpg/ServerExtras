package pl.serwer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Enchant "Dash". To nie jest enchant z rejestru Minecrafta, tylko znacznik w przedmiocie
 * (plus linia "Dash" w opisie i połysk), więc działa na dowolnym przedmiocie oprócz zbroi.
 *
 * /enchantdash  - dash w kierunku patrzenia, wymaga przedmiotu z Dash w głównej (prawej) ręce
 * /dashitem add|remove - (admin) dodaje lub zdejmuje Dash z przedmiotu w ręce
 */
final class Dash implements TabExecutor {

    private static final Component LORE_LINE =
            Component.text("Dash", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);

    private final ServerExtras plugin;
    private final NamespacedKey key;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    Dash(ServerExtras plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "dash");
    }

    boolean has(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private static boolean isArmor(Material m) {
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.contains("_ARMOR"); // zbroje dla wilka, konia itp.
    }

    private static void error(Player p, String text) {
        p.sendMessage(Component.text(text, NamedTextColor.RED));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Ta komenda działa tylko w grze."));
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("dashitem")) {
            return admin(p, args);
        }
        return dash(p);
    }

    // ---------- /enchantdash ----------

    private boolean dash(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!has(hand)) {
            error(p, "Dash działa tylko wtedy, gdy w prawej ręce trzymasz przedmiot z enchantem Dash.");
            return true;
        }

        double cooldown = plugin.getConfig().getDouble("dash.cooldown-seconds", 0.0);
        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        if (cooldown > 0 && last != null && now - last < cooldown * 1000) {
            double left = (cooldown * 1000 - (now - last)) / 1000.0;
            error(p, "Dash będzie gotowy za " + String.format(Locale.ROOT, "%.1f", left) + " s.");
            return true;
        }
        lastUse.put(p.getUniqueId(), now);

        double strength = plugin.getConfig().getDouble("dash.strength", 1.8);
        Vector velocity = p.getLocation().getDirection().normalize().multiply(strength);
        p.setVelocity(velocity);

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.2f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 12, 0.3, 0.3, 0.3, 0.02);
        return true;
    }

    // ---------- /dashitem add|remove ----------

    private boolean admin(Player p, String[] args) {
        if (args.length != 1) {
            error(p, "Użycie: /dashitem <add|remove>");
            return true;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            error(p, "Weź do prawej ręki przedmiot, który chcesz zmienić.");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (isArmor(hand.getType())) {
                    error(p, "Dash nie pasuje na zbroję.");
                    return true;
                }
                if (has(hand)) {
                    error(p, "Ten przedmiot już ma Dash.");
                    return true;
                }
                ItemMeta meta = hand.getItemMeta();
                meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(0, LORE_LINE);
                meta.lore(lore);
                meta.setEnchantmentGlintOverride(true);
                hand.setItemMeta(meta);
                p.getInventory().setItemInMainHand(hand);
                p.sendMessage(Component.text("Dodano Dash do przedmiotu w ręce.", NamedTextColor.GREEN));
            }
            case "remove" -> {
                if (!has(hand)) {
                    error(p, "Ten przedmiot nie ma Dasha.");
                    return true;
                }
                ItemMeta meta = hand.getItemMeta();
                meta.getPersistentDataContainer().remove(key);
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.remove(LORE_LINE);
                meta.lore(lore.isEmpty() ? null : lore);
                meta.setEnchantmentGlintOverride(null);
                hand.setItemMeta(meta);
                p.getInventory().setItemInMainHand(hand);
                p.sendMessage(Component.text("Zdjęto Dash z przedmiotu w ręce.", NamedTextColor.GREEN));
            }
            default -> error(p, "Użycie: /dashitem <add|remove>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("dashitem") && args.length == 1) {
            return List.of("add", "remove");
        }
        return List.of();
    }
}
