package pl.serwer;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ucina poziomy wybranych enchantów do limitu z config.yml.
 * Działa w stole enchantów, u handlarzy, w kowadle, w lootcie ze skrzyń,
 * przy łowieniu i w dropach z mobów.
 */
final class EnchantCaps implements Listener {

    private final Map<Enchantment, Integer> caps = new HashMap<>();
    private final SpecialAnvil specialAnvil;

    EnchantCaps(ServerExtras plugin, SpecialAnvil specialAnvil) {
        this.specialAnvil = specialAnvil;
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("max-levels");
        StringBuilder log = new StringBuilder();
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
                if (en == null) {
                    plugin.getLogger().warning("Nie znam enchantu '" + key + "' z config.yml, pomijam.");
                    continue;
                }
                int max = sec.getInt(key);
                caps.put(en, max);
                if (log.length() > 0) log.append(", ");
                log.append(key).append(" ").append(max);
            }
        }
        plugin.getLogger().info("Limity enchantów: " + (log.length() == 0 ? "brak" : log));
    }

    // ---------- ucinanie poziomów na przedmiocie ----------

    /** Zwraca true, jeśli coś zmieniono. Zmienia przedmiot w miejscu. */
    private boolean sanitize(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        boolean changed = false;

        for (Map.Entry<Enchantment, Integer> e : new HashMap<>(meta.getEnchants()).entrySet()) {
            Integer cap = caps.get(e.getKey());
            if (cap != null && e.getValue() > cap) {
                meta.addEnchant(e.getKey(), cap, true);
                changed = true;
            }
        }
        if (meta instanceof EnchantmentStorageMeta book) {
            for (Map.Entry<Enchantment, Integer> e : new HashMap<>(book.getStoredEnchants()).entrySet()) {
                Integer cap = caps.get(e.getKey());
                if (cap != null && e.getValue() > cap) {
                    book.addStoredEnchant(e.getKey(), cap, true);
                    changed = true;
                }
            }
        }
        if (changed) item.setItemMeta(meta);
        return changed;
    }

    private static MerchantRecipe copyWithResult(MerchantRecipe r, ItemStack result) {
        MerchantRecipe copy = new MerchantRecipe(result, r.getUses(), r.getMaxUses(),
                r.hasExperienceReward(), r.getVillagerExperience(), r.getPriceMultiplier(),
                r.getDemand(), r.getSpecialPrice());
        copy.setIgnoreDiscounts(r.shouldIgnoreDiscounts());
        copy.setIngredients(r.getIngredients());
        return copy;
    }

    // ---------- stół enchantów ----------

    @EventHandler
    public void onPrepareEnchant(PrepareItemEnchantEvent e) {
        for (EnchantmentOffer offer : e.getOffers()) {
            if (offer == null) continue;
            Enchantment en = offer.getEnchantment();
            if (en == null) continue;
            Integer cap = caps.get(en);
            if (cap != null && offer.getEnchantmentLevel() > cap) {
                offer.setEnchantmentLevel(cap);
            }
        }
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent e) {
        e.getEnchantsToAdd().replaceAll((en, level) -> {
            Integer cap = caps.get(en);
            return cap != null && level > cap ? cap : level;
        });
    }

    // ---------- handlarze ----------

    @EventHandler
    public void onTradeAcquire(VillagerAcquireTradeEvent e) {
        MerchantRecipe r = e.getRecipe();
        ItemStack result = r.getResult();
        if (sanitize(result)) {
            e.setRecipe(copyWithResult(r, result));
        }
    }

    /** Handlarze, którzy mają już oferty z za wysokim poziomem, naprawiamy tuż przed handlem. */
    @EventHandler(ignoreCancelled = true)
    public void onTraderClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof AbstractVillager trader)) return;
        List<MerchantRecipe> list = new ArrayList<>(trader.getRecipes());
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            MerchantRecipe r = list.get(i);
            ItemStack result = r.getResult();
            if (sanitize(result)) {
                list.set(i, copyWithResult(r, result));
                changed = true;
            }
        }
        if (changed) trader.setRecipes(list);
    }

    // ---------- kowadło ----------

    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        // zaklęte kowadło nie ma limitów
        Location anvilLocation = e.getInventory().getLocation();
        if (anvilLocation != null && specialAnvil.isSpecial(anvilLocation.getBlock())) return;

        ItemStack result = e.getResult();
        if (result != null && sanitize(result)) {
            e.setResult(result);
        }
    }

    // ---------- loot, wędka, dropy z mobów ----------

    @EventHandler
    public void onLoot(LootGenerateEvent e) {
        List<ItemStack> loot = e.getLoot();
        boolean changed = false;
        for (ItemStack item : loot) {
            if (sanitize(item)) changed = true;
        }
        if (changed) e.setLoot(loot);
    }

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (e.getCaught() instanceof Item caught) {
            ItemStack stack = caught.getItemStack();
            if (sanitize(stack)) caught.setItemStack(stack);
        }
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return; // ekwipunku graczy nie ruszamy
        for (ItemStack item : e.getDrops()) {
            sanitize(item);
        }
    }
}
