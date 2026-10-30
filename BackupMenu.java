package pl.serwer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Menu /backup:
 *  1. lista graczy z backupami (głowy),
 *  2. lista backupów wybranego gracza (LPM = przywróć, PPM = podgląd),
 *  3. podgląd zawartości jednego backupu.
 */
final class BackupMenu implements Listener, CommandExecutor {

    private static final String PERM = "serverextras.backup";
    private static final int PAGE = 45;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ServerExtras plugin;
    private final Backups backups;

    BackupMenu(ServerExtras plugin, Backups backups) {
        this.plugin = plugin;
        this.backups = backups;
    }

    /** Rozpoznaje nasze menu po właścicielu ekwipunku i trzyma, co jest w którym slocie. */
    static final class Holder implements InventoryHolder {
        enum Type { PLAYERS, BACKUPS, PREVIEW }

        final Type type;
        final UUID target;
        final int page;
        final Map<Integer, UUID> playerSlots = new HashMap<>();
        final Map<Integer, Long> backupSlots = new HashMap<>();
        Inventory inventory;

        Holder(Type type, UUID target, int page) {
            this.type = type;
            this.target = target;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ---------- komenda ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Tej komendy można użyć tylko w grze.");
            return true;
        }
        if (args.length == 0) {
            openPlayers(p, 0);
            return true;
        }
        UUID id = backups.findByName(args[0]);
        if (id == null) {
            p.sendMessage(Component.text("Nie ma backupów gracza " + args[0] + ".", NamedTextColor.RED));
            return true;
        }
        openBackups(p, id, 0);
        return true;
    }

    // ---------- pomocnicze ----------

    private static Component text(String s, NamedTextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    private static List<Component> lore(String... lines) {
        List<Component> out = new ArrayList<>();
        for (String line : lines) {
            out.add(text(line, NamedTextColor.GRAY));
        }
        return out;
    }

    private static ItemStack button(Material m, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name, color));
        meta.lore(lore(lore));
        item.setItemMeta(meta);
        return item;
    }

    private static String reasonName(String r) {
        return switch (r) {
            case "DEATH" -> "Śmierć";
            case "QUIT" -> "Wyjście z serwera";
            case "STOP" -> "Zamknięcie serwera";
            case "PRZED_PRZYWROCENIEM" -> "Przed przywróceniem";
            default -> r;
        };
    }

    private static Material reasonIcon(String r) {
        return switch (r) {
            case "DEATH" -> Material.SKELETON_SKULL;
            case "QUIT" -> Material.OAK_DOOR;
            case "STOP" -> Material.REDSTONE_BLOCK;
            case "PRZED_PRZYWROCENIEM" -> Material.CLOCK;
            default -> Material.PAPER;
        };
    }

    private static String time(long millis) {
        return FMT.format(Instant.ofEpochMilli(millis));
    }

    private void later(Runnable r) {
        // otwieranie nowego menu w trakcie kliknięcia bywa kapryśne, więc robimy to w następnym ticku
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private static int pagesFor(int size) {
        return Math.max(1, (size + PAGE - 1) / PAGE);
    }

    private static void navigation(Holder h, int pages) {
        if (h.page > 0) {
            h.inventory.setItem(45, button(Material.ARROW, "Poprzednia strona", NamedTextColor.YELLOW));
        }
        if (h.page < pages - 1) {
            h.inventory.setItem(53, button(Material.ARROW, "Następna strona", NamedTextColor.YELLOW));
        }
    }

    // ---------- menu 1: gracze ----------

    private void openPlayers(Player viewer, int page) {
        List<Backups.PlayerInfo> all = backups.players();
        int pages = pagesFor(all.size());
        int pg = Math.max(0, Math.min(page, pages - 1));

        Holder h = new Holder(Holder.Type.PLAYERS, null, pg);
        h.inventory = Bukkit.createInventory(h, 54,
                Component.text("Backupy: gracze (" + (pg + 1) + "/" + pages + ")"));

        for (int i = 0; i < PAGE; i++) {
            int idx = pg * PAGE + i;
            if (idx >= all.size()) break;
            Backups.PlayerInfo info = all.get(idx);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(info.uuid()));
            meta.displayName(text(info.name(), NamedTextColor.YELLOW));
            meta.lore(lore("Backupów: " + info.count(), "Ostatni: " + time(info.last()), "", "Kliknij, aby zobaczyć"));
            head.setItemMeta(meta);

            h.inventory.setItem(i, head);
            h.playerSlots.put(i, info.uuid());
        }
        navigation(h, pages);
        if (all.isEmpty()) {
            h.inventory.setItem(22, button(Material.BARRIER, "Brak backupów", NamedTextColor.RED,
                    "Backup powstaje przy śmierci", "i wyjściu gracza z serwera."));
        }
        viewer.openInventory(h.inventory);
    }

    // ---------- menu 2: backupy jednego gracza ----------

    private void openBackups(Player viewer, UUID target, int page) {
        List<Backups.Snapshot> all = backups.list(target);
        String name = backups.nameOf(target);
        int pages = pagesFor(all.size());
        int pg = Math.max(0, Math.min(page, pages - 1));

        Holder h = new Holder(Holder.Type.BACKUPS, target, pg);
        h.inventory = Bukkit.createInventory(h, 54,
                Component.text("Backupy: " + name + " (" + (pg + 1) + "/" + pages + ")"));

        for (int i = 0; i < PAGE; i++) {
            int idx = pg * PAGE + i;
            if (idx >= all.size()) break;
            Backups.Snapshot s = all.get(idx);

            h.inventory.setItem(i, button(reasonIcon(s.reason()), time(s.id()), NamedTextColor.AQUA,
                    reasonName(s.reason()),
                    "Miejsce: " + s.world() + " " + s.x() + " " + s.y() + " " + s.z(),
                    "Przedmiotów: " + s.count(),
                    "",
                    "LPM: przywróć ekwipunek",
                    "PPM: podgląd"));
            h.backupSlots.put(i, s.id());
        }
        navigation(h, pages);
        h.inventory.setItem(49, button(Material.BARRIER, "Wróć do listy graczy", NamedTextColor.RED));
        viewer.openInventory(h.inventory);
    }

    // ---------- menu 3: podgląd ----------

    private static ItemStack at(ItemStack[] items, int index) {
        return index < items.length ? items[index] : null;
    }

    private void openPreview(Player viewer, UUID target, Backups.Snapshot s, int backPage) {
        Holder h = new Holder(Holder.Type.PREVIEW, target, backPage);
        h.inventory = Bukkit.createInventory(h, 54, Component.text("Podgląd: " + time(s.id())));

        ItemStack[] items = s.items();
        for (int i = 0; i < 27; i++) {
            h.inventory.setItem(i, at(items, 9 + i));      // główna część ekwipunku
        }
        for (int i = 0; i < 9; i++) {
            h.inventory.setItem(27 + i, at(items, i));     // pasek szybkiego dostępu
        }
        for (int i = 0; i < 4; i++) {
            h.inventory.setItem(45 + i, at(items, 39 - i)); // hełm, napierśnik, getry, buty
        }
        h.inventory.setItem(49, at(items, 40));            // druga ręka
        h.inventory.setItem(53, button(Material.ARROW, "Wróć", NamedTextColor.YELLOW));
        viewer.openInventory(h.inventory);
    }

    // ---------- przywracanie ----------

    private void doRestore(Player admin, UUID target, long snapshotId) {
        Backups.Snapshot s = backups.get(target, snapshotId).orElse(null);
        if (s == null) {
            admin.sendMessage(Component.text("Nie znaleziono tego backupu.", NamedTextColor.RED));
            return;
        }
        String name = backups.nameOf(target);
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            backups.restore(online, s);
            admin.sendMessage(Component.text("Przywrócono ekwipunek gracza " + name + " z " + time(s.id())
                    + ". Jego poprzedni ekwipunek zapisał się jako nowy backup.", NamedTextColor.GREEN));
            if (!online.equals(admin)) {
                online.sendMessage(Component.text("Twój ekwipunek został przywrócony z backupu.", NamedTextColor.GREEN));
            }
        } else {
            backups.setPending(target, snapshotId);
            admin.sendMessage(Component.text("Gracz " + name + " jest offline. Ekwipunek zostanie przywrócony, "
                    + "gdy wejdzie na serwer.", NamedTextColor.YELLOW));
        }
    }

    // ---------- kliknięcia ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission(PERM)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;

        int slot = e.getSlot();
        switch (h.type) {
            case PLAYERS -> {
                UUID id = h.playerSlots.get(slot);
                if (id != null) {
                    later(() -> openBackups(p, id, 0));
                } else if (slot == 45) {
                    later(() -> openPlayers(p, h.page - 1));
                } else if (slot == 53) {
                    later(() -> openPlayers(p, h.page + 1));
                }
            }
            case BACKUPS -> {
                Long snapshotId = h.backupSlots.get(slot);
                if (snapshotId != null) {
                    if (e.isRightClick()) {
                        Backups.Snapshot s = backups.get(h.target, snapshotId).orElse(null);
                        if (s != null) later(() -> openPreview(p, h.target, s, h.page));
                    } else {
                        later(() -> {
                            p.closeInventory();
                            doRestore(p, h.target, snapshotId);
                        });
                    }
                } else if (slot == 45) {
                    later(() -> openBackups(p, h.target, h.page - 1));
                } else if (slot == 53) {
                    later(() -> openBackups(p, h.target, h.page + 1));
                } else if (slot == 49) {
                    later(() -> openPlayers(p, 0));
                }
            }
            case PREVIEW -> {
                if (slot == 53) {
                    later(() -> openBackups(p, h.target, h.page));
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }
}
