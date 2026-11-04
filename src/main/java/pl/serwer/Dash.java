package pl.serwer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Enchant "Chain Rush" (nazwę zmienisz w config.yml, enchant.name).
 *
 * To nie jest enchant z rejestru Minecrafta, tylko znacznik w przedmiocie (plus linia w opisie
 * i połysk). Działa tylko na broni do walki w zwarciu: miecze, siekiery, buława, trójząb, włócznie.
 *
 * Dwie umiejętności, każda ma własny cooldown i każdą włącza się komendą, z bronią w prawej ręce:
 *   /enchantdash   mocne wybicie w stronę patrzenia (także w górę)
 *   /enchantchain  łańcuch: przyciąga użytkownika do gracza, na którego patrzy, ale tylko wtedy,
 *                  gdy sam użytkownik jest min. 5 bloków nad ziemią (pozycja celu nie ma znaczenia)
 * Administrator: /dashitem add|remove nakłada i zdejmuje enchant z broni w ręce.
 *
 * Kto ma taką broń gdziekolwiek w ekwipunku, widzi na action barze stan obu umiejętności.
 */
final class Dash implements TabExecutor {

    // starsza wersja enchantu nazywała się po prostu "Dash", więc tę linię też rozpoznajemy w opisie
    private static final String LEGACY_NAME = "Dash";

    private static final Particle.DustOptions DUST_LIGHT = new Particle.DustOptions(Color.fromRGB(165, 165, 170), 1.2f);
    private static final Particle.DustOptions DUST_DARK = new Particle.DustOptions(Color.fromRGB(95, 95, 100), 1.0f);

    private final ServerExtras plugin;
    private final NamespacedKey key;
    private final NamespacedKey bookKey;

    private final Map<UUID, Long> dashUsed = new HashMap<>();
    private final Map<UUID, Long> chainUsed = new HashMap<>();
    // kto ma teraz cooldown, żeby zagrać dźwięk dokładnie w chwili, gdy umiejętność znowu jest gotowa
    private final Set<UUID> dashCooling = new HashSet<>();
    private final Set<UUID> chainCooling = new HashSet<>();

    Dash(ServerExtras plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "dash"); // ten sam klucz co w starej wersji, więc stare przedmioty działają
        this.bookKey = new NamespacedKey(plugin, "chain_rush_book");
    }

    // ---------- co to jest broń melee i czy przedmiot ma enchant ----------

    private static boolean isMeleeWeapon(Material m) {
        String n = m.name();
        return m == Material.MACE || m == Material.TRIDENT
                || n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR");
    }

    private boolean hasTag(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Przedmiot ma enchant i jest bronią melee. */
    boolean has(ItemStack item) {
        return item != null && isMeleeWeapon(item.getType()) && hasTag(item);
    }

    private boolean carriesEnchant(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (has(item)) return true;
        }
        return false;
    }

    // ---------- drobne pomocnicze ----------

    private String name() {
        return plugin.getConfig().getString("enchant.name", "Chain Rush");
    }

    private Component loreLine() {
        return Component.text(name(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble(path, def);
    }

    private static String seconds(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void error(Player p, String text) {
        p.sendMessage(Component.text(text, NamedTextColor.RED));
    }

    private static void sound(Location at, String soundKey, float volume, float pitch) {
        if (at.getWorld() != null) {
            at.getWorld().playSound(at, soundKey, SoundCategory.PLAYERS, volume, pitch);
        }
    }

    private static List<Component> loreOf(ItemMeta meta) {
        List<Component> lore = meta.lore();
        return lore == null ? new ArrayList<>() : new ArrayList<>(lore);
    }

    private void stripEnchantLines(List<Component> lore) {
        String current = name();
        lore.removeIf(line -> {
            String text = PlainTextComponentSerializer.plainText().serialize(line);
            return text.equals(LEGACY_NAME) || text.equals(current);
        });
    }

    private void notHolding(Player p) {
        error(p, "Ta umiejętność działa tylko wtedy, gdy w prawej ręce trzymasz broń z enchantem "
                + name() + ".");
    }

    private void deny(Player p) {
        // cooldown widać na action barze, więc bez tekstu, samo "kliknięcie"
        p.playSound(p.getLocation(), "block.dispenser.fail", SoundCategory.PLAYERS, 0.8f, 1.0f);
    }

    /** Ile sekund zostało do końca cooldownu (0 = gotowe). */
    private double remaining(Map<UUID, Long> used, UUID id, double cooldown) {
        Long last = used.get(id);
        if (last == null || cooldown <= 0) return 0;
        double left = cooldown - (System.currentTimeMillis() - last) / 1000.0;
        return Math.max(0, left);
    }

    // ---------- komendy ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("givechainrush")) {
            return giveBook(sender, args);
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Ta komenda działa tylko w grze."));
            return true;
        }
        return switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "dashitem" -> admin(p, args);
            case "enchantchain" -> chain(p);
            default -> dash(p);
        };
    }

    // ---------- umiejętność 1: dash ----------

    private boolean dash(Player p) {
        if (!has(p.getInventory().getItemInMainHand())) {
            notHolding(p);
            return true;
        }
        UUID id = p.getUniqueId();
        double cooldown = cfg("dash.cooldown-seconds", 15.0);
        if (remaining(dashUsed, id, cooldown) > 0) {
            deny(p);
            return true;
        }
        dashUsed.put(id, System.currentTimeMillis());

        double strength = cfg("dash.strength", 2.2);
        Vector direction = p.getLocation().getDirection().normalize();
        p.setVelocity(direction.multiply(strength));

        dashEffects(p);
        return true;
    }

    private void dashEffects(Player p) {
        Location at = p.getLocation();
        sound(at, "entity.ender_dragon.flap", 1.0f, 1.5f);
        sound(at, "item.trident.riptide_1", 0.8f, 1.3f);
        p.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 1, 0), 25, 0.3, 0.4, 0.3, 0.08);
        drawRing(at, 1.0);

        // smuga za graczem przez pół sekundy
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!p.isOnline() || ticks++ >= 10) {
                    cancel();
                    return;
                }
                Location body = p.getLocation().add(0, 1, 0);
                World w = p.getWorld();
                w.spawnParticle(Particle.CLOUD, body, 4, 0.2, 0.3, 0.2, 0.01);
                w.spawnParticle(Particle.CRIT, body, 3, 0.25, 0.3, 0.25, 0.1);
                w.spawnParticle(Particle.DUST, body, 3, 0.25, 0.3, 0.25, 0, DUST_LIGHT);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ---------- umiejętność 2: chain ----------

    private boolean chain(Player p) {
        if (!has(p.getInventory().getItemInMainHand())) {
            notHolding(p);
            return true;
        }
        UUID id = p.getUniqueId();
        double cooldown = cfg("chain.cooldown-seconds", 20.0);
        if (remaining(chainUsed, id, cooldown) > 0) {
            deny(p);
            return true;
        }

        double range = cfg("chain.range", 30.0);
        double minHeight = cfg("chain.min-height", 5.0);
        double aimAssist = cfg("chain.aim-assist", 1.2);

        // warunek jest po stronie użytkownika: musi być w powietrzu, pozycja celu nie ma znaczenia
        if (heightAboveGround(p) < minHeight) {
            error(p, "Chain działa tylko wtedy, gdy jesteś co najmniej " + (int) minHeight + " bloków nad ziemią.");
            return true;
        }

        // trafiamy w pierwszą rzecz na linii wzroku: gracz albo blok (blok zasłania cel)
        Location eye = p.getEyeLocation();
        RayTraceResult hit = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true,
                aimAssist, e -> e instanceof Player other && !other.equals(p) && other.getGameMode() != GameMode.SPECTATOR);

        if (hit == null || !(hit.getHitEntity() instanceof Player target)) {
            error(p, "Nie patrzysz na żadnego gracza (zasięg " + (int) range + " bloków).");
            return true;
        }

        chainUsed.put(id, System.currentTimeMillis());
        startChain(p, target);
        return true;
    }

    /** Wysokość gracza nad ziemią (albo wodą). Inni gracze pod nim się nie liczą. */
    private double heightAboveGround(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) return 0;
        RayTraceResult r = world.rayTrace(loc, new Vector(0, -1, 0), 64, FluidCollisionMode.ALWAYS, true, 0.3,
                e -> false);
        if (r == null) return Double.MAX_VALUE; // pod nim przez 64 bloki nic nie ma
        return loc.toVector().distance(r.getHitPosition());
    }

    private void startChain(Player user, Player target) {
        double speed = cfg("chain.pull-speed", 1.5);
        int maxTicks = (int) cfg("chain.max-ticks", 60);
        double stopDistance = Math.max(cfg("chain.stop-distance", 2.0), 0.5);

        Location start = user.getLocation();
        sound(start, "item.armor.equip_chain", 1.0f, 0.7f);
        sound(start, "entity.fishing_bobber.throw", 1.0f, 0.8f);
        sound(target.getLocation(), "item.armor.equip_chain", 1.0f, 0.5f); // cel słyszy, że coś w niego leci
        drawRing(start, 1.2);
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.2);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!user.isOnline() || user.isDead() || !target.isOnline() || target.isDead()
                        || !user.getWorld().equals(target.getWorld()) || ticks >= maxTicks) {
                    cancel();
                    return;
                }
                Location from = user.getLocation().add(0, 1, 0);
                Location to = target.getLocation().add(0, 1, 0);
                double distance = from.distance(to);

                if (distance <= stopDistance) {
                    finishChain(user, target, to.toVector().subtract(from.toVector()));
                    cancel();
                    return;
                }

                Vector direction = to.toVector().subtract(from.toVector()).normalize();
                user.setVelocity(direction.multiply(speed));
                drawChain(from, to);
                if (ticks % 4 == 0) {
                    sound(from, "item.armor.equip_chain", 0.6f, 0.9f + (ticks % 8) * 0.03f);
                }
                ticks++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void finishChain(Player user, Player target, Vector toward) {
        if (toward.lengthSquared() > 1.0E-6) {
            user.setVelocity(toward.clone().normalize().multiply(0.3));
        }
        Location at = target.getLocation().add(0, 1, 0);
        sound(at, "entity.player.attack.knockback", 1.0f, 0.8f);
        sound(at, "item.armor.equip_chain", 1.0f, 1.2f);
        at.getWorld().spawnParticle(Particle.CRIT, at, 25, 0.4, 0.5, 0.4, 0.2);
        at.getWorld().spawnParticle(Particle.DUST, at, 20, 0.4, 0.5, 0.4, 0, DUST_LIGHT);
    }

    // ---------- cząsteczki ----------

    /** Szary łańcuch: linia cząsteczek z lekkim zygzakiem, żeby wyglądały jak ogniwa. */
    private void drawChain(Location from, Location to) {
        World w = from.getWorld();
        if (w == null) return;
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < 0.1) return;

        Vector direction = delta.clone().normalize();
        Vector side = direction.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0E-4) {
            side = new Vector(1, 0, 0);
        } else {
            side.normalize();
        }

        int i = 0;
        for (double d = 0; d < length; d += 0.4, i++) {
            double jitter = (i % 2 == 0) ? 0.12 : -0.12;
            Location point = from.clone().add(direction.clone().multiply(d)).add(side.clone().multiply(jitter));
            w.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, (i % 3 == 0) ? DUST_DARK : DUST_LIGHT);
            if (i % 4 == 0) {
                w.spawnParticle(Particle.CRIT, point, 1, 0.02, 0.02, 0.02, 0);
            }
        }
    }

    /** Pierścień na ziemi pod graczem. */
    private void drawRing(Location center, double radius) {
        World w = center.getWorld();
        if (w == null) return;
        int points = 28;
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            Location l = center.clone().add(Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius);
            w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, DUST_LIGHT);
        }
    }

    // ---------- pasek Ready / cooldown ----------

    /** Uruchamiane raz przy starcie pluginu. Odświeża action bar co 2 ticki. */
    void startHud() {
        new BukkitRunnable() {
            @Override
            public void run() {
                tickHud();
            }
        }.runTaskTimer(plugin, 20L, 2L);
    }

    private void tickHud() {
        double dashCooldown = cfg("dash.cooldown-seconds", 15.0);
        double chainCooldown = cfg("chain.cooldown-seconds", 20.0);
        String dashIcon = plugin.getConfig().getString("hud.dash-icon", "Ψ");
        String chainIcon = plugin.getConfig().getString("hud.chain-icon", "☁");

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!carriesEnchant(p)) continue;

            UUID id = p.getUniqueId();
            double dashLeft = remaining(dashUsed, id, dashCooldown);
            double chainLeft = remaining(chainUsed, id, chainCooldown);
            trackReady(p, dashLeft, dashCooling);
            trackReady(p, chainLeft, chainCooling);

            p.sendActionBar(hudPart(dashIcon, dashLeft)
                    .append(Component.text(" | ", NamedTextColor.AQUA))
                    .append(hudPart(chainIcon, chainLeft)));
        }
    }

    private static Component hudPart(String icon, double left) {
        Component status = left <= 0
                ? Component.text("Ready", NamedTextColor.GREEN)
                : Component.text(seconds(left) + "s", NamedTextColor.RED);
        return Component.text(icon + " ", NamedTextColor.AQUA).append(status);
    }

    /** Cichy dźwięk w chwili, gdy umiejętność wraca z cooldownu. */
    private void trackReady(Player p, double left, Set<UUID> cooling) {
        if (left > 0) {
            cooling.add(p.getUniqueId());
        } else if (cooling.remove(p.getUniqueId())) {
            p.playSound(p.getLocation(), "entity.experience_orb.pickup", SoundCategory.PLAYERS, 0.6f, 1.6f);
        }
    }

    // ---------- księga Chain Rush (do użycia w zaklętym kowadle) ----------

    ItemStack createBook() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.displayName(Component.text("Księga: " + name(), NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                loreLine(),
                Component.text("Użyj w zaklętym kowadle razem z bronią do walki w zwarciu.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(bookKey, PersistentDataType.BYTE, (byte) 1);
        book.setItemMeta(meta);
        return book;
    }

    boolean isBook(ItemStack item) {
        return item != null && item.getType() == Material.ENCHANTED_BOOK && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(bookKey, PersistentDataType.BYTE);
    }

    /** Kopia broni z nałożonym enchantem albo null, jeśli to nie broń do walki w zwarciu albo już go ma. */
    ItemStack applyTo(ItemStack weapon) {
        if (weapon == null || !isMeleeWeapon(weapon.getType()) || hasTag(weapon)) return null;
        ItemStack result = weapon.clone();
        ItemMeta meta = result.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        List<Component> lore = loreOf(meta);
        stripEnchantLines(lore);
        lore.add(0, loreLine());
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        result.setItemMeta(meta);
        return result;
    }

    /** Koszt nałożenia enchantu w zaklętym kowadle, w poziomach doświadczenia. */
    int anvilCost() {
        return Math.max(1, (int) cfg("enchant.anvil-cost", 10));
    }

    private boolean giveBook(CommandSender sender, String[] args) {
        Player target = args.length > 0
                ? Bukkit.getPlayerExact(args[0])
                : (sender instanceof Player p ? p : null);
        if (target == null) {
            sender.sendMessage(Component.text(args.length > 0
                    ? "Nie ma takiego gracza online."
                    : "Podaj nick gracza: /givechainrush <gracz>", NamedTextColor.RED));
            return true;
        }
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createBook());
        for (ItemStack rest : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        sender.sendMessage(Component.text("Dano księgę " + name() + " graczowi " + target.getName() + ".",
                NamedTextColor.GREEN));
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
            error(p, "Weź do prawej ręki broń, którą chcesz zmienić.");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (!isMeleeWeapon(hand.getType())) {
                    error(p, name() + " pasuje tylko na broń do walki w zwarciu: miecze, siekiery, buławę, "
                            + "trójząb i włócznie.");
                    return true;
                }
                boolean already = hasTag(hand);
                ItemMeta meta = hand.getItemMeta();
                meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
                List<Component> lore = loreOf(meta);
                stripEnchantLines(lore);          // także stara linia "Dash", jeśli przedmiot jest ze starej wersji
                lore.add(0, loreLine());
                meta.lore(lore);
                meta.setEnchantmentGlintOverride(true);
                hand.setItemMeta(meta);
                p.getInventory().setItemInMainHand(hand);
                p.sendMessage(Component.text(already
                        ? "Odświeżono enchant " + name() + " na przedmiocie w ręce."
                        : "Dodano enchant " + name() + " do przedmiotu w ręce.", NamedTextColor.GREEN));
            }
            case "remove" -> {
                if (!hasTag(hand)) {
                    error(p, "Ten przedmiot nie ma tego enchantu.");
                    return true;
                }
                ItemMeta meta = hand.getItemMeta();
                meta.getPersistentDataContainer().remove(key);
                List<Component> lore = loreOf(meta);
                stripEnchantLines(lore);
                meta.lore(lore.isEmpty() ? null : lore);
                meta.setEnchantmentGlintOverride(null);
                hand.setItemMeta(meta);
                p.getInventory().setItemInMainHand(hand);
                p.sendMessage(Component.text("Zdjęto enchant " + name() + " z przedmiotu w ręce.",
                        NamedTextColor.GREEN));
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
        if (cmd.getName().equalsIgnoreCase("givechainrush") && args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
