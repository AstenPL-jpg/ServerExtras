package pl.serwer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Zapis i odczyt backupów ekwipunku. Każdy gracz ma swój plik
 * plugins/ServerExtras/backups/&lt;uuid&gt;.yml. Przedmioty są zapisane jako base64,
 * po jednym wpisie na slot (36 slotów + 4 zbroje + druga ręka = 41).
 */
final class Backups {

    record Snapshot(long id, String reason, String world, int x, int y, int z, List<String> data) {

        int count() {
            int n = 0;
            for (String s : data) {
                if (!s.isEmpty()) n++;
            }
            return n;
        }

        ItemStack[] items() {
            ItemStack[] out = new ItemStack[Math.min(data.size(), 41)];
            for (int i = 0; i < out.length; i++) {
                out[i] = decode(data.get(i));
            }
            return out;
        }
    }

    record PlayerInfo(UUID uuid, String name, int count, long last) {
    }

    private final ServerExtras plugin;
    private final File dir;

    Backups(ServerExtras plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "backups");
        dir.mkdirs();
    }

    private File file(UUID id) {
        return new File(dir, id + ".yml");
    }

    private void save(UUID id, YamlConfiguration cfg) {
        try {
            cfg.save(file(id));
        } catch (IOException ex) {
            plugin.getLogger().severe("Nie udało się zapisać backupu gracza " + id + ": " + ex.getMessage());
        }
    }

    // ---------- (de)serializacja przedmiotów ----------

    private static String encode(ItemStack item) {
        if (item == null || item.getType().isAir()) return "";
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static ItemStack decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        } catch (Exception ex) {
            return null;
        }
    }

    // ---------- tworzenie backupu ----------

    /**
     * Zapisuje aktualny ekwipunek gracza. Pusty ekwipunek pomijamy. Przy wyjściu
     * i zamknięciu serwera pomijamy też backup identyczny z poprzednim.
     * reason: DEATH, QUIT, STOP albo PRZED_PRZYWROCENIEM.
     */
    void add(Player p, String reason) {
        ItemStack[] contents = p.getInventory().getContents();
        List<String> data = new ArrayList<>(contents.length);
        boolean empty = true;
        for (ItemStack item : contents) {
            String s = encode(item);
            if (!s.isEmpty()) empty = false;
            data.add(s);
        }
        if (empty) return;

        UUID id = p.getUniqueId();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file(id));
        List<Snapshot> all = read(cfg);
        long last = all.isEmpty() ? 0 : all.get(all.size() - 1).id();

        boolean dedupe = reason.equals("QUIT") || reason.equals("STOP");
        if (dedupe && !all.isEmpty() && all.get(all.size() - 1).data().equals(data)) return;

        long time = Math.max(System.currentTimeMillis(), last + 1);
        Location l = p.getLocation();
        String base = "backups." + time;
        cfg.set("name", p.getName());
        cfg.set(base + ".reason", reason);
        cfg.set(base + ".world", l.getWorld() == null ? "?" : l.getWorld().getName());
        cfg.set(base + ".x", l.getBlockX());
        cfg.set(base + ".y", l.getBlockY());
        cfg.set(base + ".z", l.getBlockZ());
        cfg.set(base + ".data", data);

        // trzymamy tylko ostatnie N backupów na gracza
        int max = Math.max(1, plugin.getConfig().getInt("backups.max-per-player", 30));
        ConfigurationSection sec = cfg.getConfigurationSection("backups");
        if (sec != null) {
            List<Long> keys = new ArrayList<>();
            for (String k : sec.getKeys(false)) {
                try {
                    keys.add(Long.parseLong(k));
                } catch (NumberFormatException ignored) {
                    // obcy klucz, pomijamy
                }
            }
            Collections.sort(keys);
            for (int i = 0; i < keys.size() - max; i++) {
                cfg.set("backups." + keys.get(i), null);
            }
        }
        save(id, cfg);
    }

    // ---------- odczyt ----------

    /** Wszystkie backupy z pliku, od najstarszego do najnowszego. */
    private List<Snapshot> read(YamlConfiguration cfg) {
        List<Snapshot> out = new ArrayList<>();
        ConfigurationSection sec = cfg.getConfigurationSection("backups");
        if (sec == null) return out;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            long id;
            try {
                id = Long.parseLong(key);
            } catch (NumberFormatException ex) {
                continue;
            }
            out.add(new Snapshot(id, s.getString("reason", "?"), s.getString("world", "?"),
                    s.getInt("x"), s.getInt("y"), s.getInt("z"), s.getStringList("data")));
        }
        out.sort(Comparator.comparingLong(Snapshot::id));
        return out;
    }

    /** Backupy gracza, od najnowszego. */
    List<Snapshot> list(UUID id) {
        List<Snapshot> all = read(YamlConfiguration.loadConfiguration(file(id)));
        Collections.reverse(all);
        return all;
    }

    Optional<Snapshot> get(UUID id, long snapshotId) {
        for (Snapshot s : list(id)) {
            if (s.id() == snapshotId) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** Gracze, którzy mają jakiekolwiek backupy, ci z najświeższym backupem pierwsi. */
    List<PlayerInfo> players() {
        List<PlayerInfo> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return out;
        for (File f : files) {
            UUID id;
            try {
                id = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection sec = cfg.getConfigurationSection("backups");
            if (sec == null) continue;
            int count = 0;
            long last = 0;
            for (String k : sec.getKeys(false)) {
                try {
                    last = Math.max(last, Long.parseLong(k));
                    count++;
                } catch (NumberFormatException ignored) {
                    // obcy klucz, pomijamy
                }
            }
            if (count == 0) continue;
            out.add(new PlayerInfo(id, cfg.getString("name", id.toString()), count, last));
        }
        out.sort(Comparator.comparingLong(PlayerInfo::last).reversed());
        return out;
    }

    UUID findByName(String name) {
        for (PlayerInfo info : players()) {
            if (info.name().equalsIgnoreCase(name)) return info.uuid();
        }
        return null;
    }

    String nameOf(UUID id) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file(id));
        String name = cfg.getString("name");
        if (name != null) return name;
        String online = Bukkit.getOfflinePlayer(id).getName();
        return online != null ? online : id.toString();
    }

    // ---------- przywracanie ----------

    /** Podmienia ekwipunek gracza na ten z backupu. Aktualny ekwipunek zapisuje wcześniej jako backup. */
    void restore(Player p, Snapshot s) {
        add(p, "PRZED_PRZYWROCENIEM");
        p.getInventory().setContents(s.items());
    }

    /** Przywrócenie dla gracza offline: wykona się, gdy wejdzie na serwer. */
    void setPending(UUID id, long snapshotId) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file(id));
        cfg.set("pending", snapshotId);
        save(id, cfg);
    }

    /** Zwraca id oczekującego przywrócenia i je kasuje, albo null. */
    Long takePending(UUID id) {
        File f = file(id);
        if (!f.exists()) return null;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        if (!cfg.contains("pending")) return null;
        long snapshotId = cfg.getLong("pending");
        cfg.set("pending", null);
        save(id, cfg);
        return snapshotId;
    }
}
