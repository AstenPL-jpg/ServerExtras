package pl.serwer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zaklęte kowadło.
 *
 * W świecie to zwykłe kowadło (działa jak każde, z gui, grawitacją i dźwiękami), a wygląd
 * "z połyskiem" daje mu niewidoczna dla fizyki nakładka: ItemDisplay z kowadłem, które ma połysk.
 * Kowadło jest "zaklęte", gdy w jego bloku stoi taka nakładka (jest oznaczona znacznikiem).
 * Dzięki temu nie trzeba niczego zapisywać w pliku: nakładka zapisuje się razem ze światem.
 *
 * Różnice względem zwykłego kowadła:
 *  - nie obowiązują go limity poziomów enchantów z config.yml ani vanillowe maksimum:
 *    dwie księgi protection IV dadzą V, dwie V dadzą VI i tak dalej,
 *  - nie ma "Too Expensive": koszt jest ucięty do 39 poziomów,
 *  - przyjmuje księgę Chain Rush: broń do walki w zwarciu + księga = broń z tym enchantem,
 *  - nigdy się nie uszkadza ani nie niszczy od użycia,
 *  - nie spada, nie da się go przesunąć tłokiem i nie niszczą go eksplozje.
 */
final class SpecialAnvil implements Listener, TabExecutor {

    private final ServerExtras plugin;
    private final Dash dash;
    private final NamespacedKey itemKey;
    private final NamespacedKey displayKey;

    SpecialAnvil(ServerExtras plugin, Dash dash) {
        this.plugin = plugin;
        this.dash = dash;
        this.itemKey = new NamespacedKey(plugin, "special_anvil_item");
        this.displayKey = new NamespacedKey(plugin, "special_anvil_display");
    }

    // ---------- przedmiot ----------

    ItemStack createItem(int amount) {
        ItemStack item = new ItemStack(Material.ANVIL, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Zaklęte kowadło", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Kowadło bez limitu enchantów.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isSpecialItem(ItemStack item) {
        return item != null && item.getType() == Material.ANVIL && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ---------- rozpoznawanie postawionego kowadła ----------

    private static boolean isAnvilType(Material m) {
        return m == Material.ANVIL || m == Material.CHIPPED_ANVIL || m == Material.DAMAGED_ANVIL;
    }

    private ItemDisplay findDisplay(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (Entity en : block.getWorld().getNearbyEntities(center, 0.4, 0.4, 0.4)) {
            if (en instanceof ItemDisplay d
                    && d.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE)) {
                return d;
            }
        }
        return null;
    }

    /** Używane też przez EnchantCaps, żeby to kowadło omijało limity. */
    boolean isSpecial(Block block) {
        return isAnvilType(block.getType()) && findDisplay(block) != null;
    }

    private void spawnDisplay(Block block) {
        if (findDisplay(block) != null) return;

        float scale = (float) plugin.getConfig().getDouble("enchanted-anvil.scale", 1.002);
        boolean fullbright = plugin.getConfig().getBoolean("enchanted-anvil.fullbright", false);
        boolean flip = plugin.getConfig().getBoolean("enchanted-anvil.flip-rotation", false);

        // Model kowadła jest symetryczny co 180 stopni, więc liczy się tylko to, czy długa oś
        // blatu biegnie wzdłuż X czy Z. Kowadło zwrócone na wschód/zachód ma ją obróconą o 90 stopni.
        boolean rotate = false;
        if (block.getBlockData() instanceof Directional dir) {
            BlockFace f = dir.getFacing();
            rotate = (f == BlockFace.EAST || f == BlockFace.WEST);
        }
        if (flip) rotate = !rotate;
        AxisAngle4f rotation = rotate
                ? new AxisAngle4f((float) (Math.PI / 2), 0f, 1f, 0f)
                : new AxisAngle4f();

        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().spawn(center, ItemDisplay.class, d -> {
            ItemStack shown = new ItemStack(Material.ANVIL);
            ItemMeta meta = shown.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            shown.setItemMeta(meta);

            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(
                    new Vector3f(), rotation, new Vector3f(scale, scale, scale), new AxisAngle4f()));
            d.setPersistent(true);
            if (fullbright) {
                d.setBrightness(new Display.Brightness(15, 15));
            }
            d.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    // ---------- stawianie i niszczenie ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!isSpecialItem(e.getItemInHand())) return;
        Block b = e.getBlockPlaced();
        if (b.getType() != Material.ANVIL) return;
        spawnDisplay(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        ItemDisplay d = findDisplay(b);
        if (d == null) return;
        d.remove();

        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        // wypada zaklęte kowadło, ale tylko wtedy, gdy zwykłe też by wypadło (np. kilofem)
        if (e.isDropItems() && !b.getDrops(p.getInventory().getItemInMainHand(), p).isEmpty()) {
            e.setDropItems(false);
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), createItem(1));
        }
    }

    // ---------- ochrona przed przesunięciem i zniszczeniem ----------

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isSpecial);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isSpecial);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (e.getBlocks().stream().anyMatch(this::isSpecial)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (e.getBlocks().stream().anyMatch(this::isSpecial)) e.setCancelled(true);
    }

    /** Zwykłe kowadło spada, gdy nic pod nim nie ma. Zaklęte zostaje tam, gdzie je postawiono. */
    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof FallingBlock && isSpecial(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    // ---------- brak limitów poziomów i kosztu ----------

    /** Flagi trzeba ustawić przy otwarciu, bo kowadło liczy wynik już przy pierwszym włożonym przedmiocie. */
    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getView() instanceof AnvilView view)) return;
        Location loc = e.getInventory().getLocation();
        if (loc == null || !isSpecial(loc.getBlock())) return;

        view.bypassEnchantmentLevelRestriction(true);   // poziomy ponad vanillowe maksimum
        view.setMaximumRepairCost(1_000_000);           // serwer nigdy nie mówi "Too Expensive"
    }

    /**
     * Klient sam rysuje "Too Expensive!" przy koszcie od 40 w górę, niezależnie od serwera.
     * Żeby napis się nie pojawiał, pokazujemy i pobieramy najwyżej 39 poziomów.
     */
    @EventHandler
    public void onPrepare(PrepareAnvilEvent e) {
        Location loc = e.getInventory().getLocation();
        if (loc == null || !isSpecial(loc.getBlock())) return;

        AnvilView view = e.getView();

        // przepis: broń do walki w zwarciu (lewy slot) + księga Chain Rush (prawy slot) = broń z enchantem
        if (dash.isBook(e.getInventory().getItem(1))) {
            ItemStack enchanted = dash.applyTo(e.getInventory().getItem(0));
            if (enchanted != null) {
                e.setResult(enchanted);
                view.setRepairCost(dash.anvilCost());
            }
        }

        if (view.getRepairCost() > 39) {
            view.setRepairCost(39);
        }
    }

    // ---------- kowadło się nie psuje od używania ----------

    @EventHandler
    public void onAnvilClick(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof AnvilInventory inv)) return;
        Location loc = inv.getLocation();
        if (loc == null) return;
        Block b = loc.getBlock();
        if (!isSpecial(b)) return;

        BlockFace facing = b.getBlockData() instanceof Directional d ? d.getFacing() : BlockFace.NORTH;
        // po wzięciu wyniku kowadło może się uszkodzić albo zniknąć, więc sprawdzamy to tick później
        Bukkit.getScheduler().runTask(plugin, () -> restore(b, facing));
    }

    private void restore(Block b, BlockFace facing) {
        if (b.getType() == Material.ANVIL) return;
        BlockData data = Material.ANVIL.createBlockData();
        if (data instanceof Directional d) d.setFacing(facing);
        b.setBlockData(data);
    }

    // ---------- /giveanvil [gracz] ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player target = args.length > 0
                ? Bukkit.getPlayerExact(args[0])
                : (sender instanceof Player p ? p : null);
        if (target == null) {
            sender.sendMessage(Component.text(args.length > 0
                    ? "Nie ma takiego gracza online."
                    : "Podaj nick gracza: /giveanvil <gracz>", NamedTextColor.RED));
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createItem(1));
        for (ItemStack rest : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        sender.sendMessage(Component.text("Dano zaklęte kowadło graczowi " + target.getName() + ".",
                NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
