package pl.serwer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/** Robi backupy przy śmierci i wyjściu oraz wykonuje oczekujące przywrócenia po wejściu. */
final class BackupListener implements Listener {

    private final ServerExtras plugin;
    private final Backups backups;

    BackupListener(ServerExtras plugin, Backups backups) {
        this.plugin = plugin;
        this.backups = backups;
    }

    // LOWEST, żeby złapać ekwipunek zanim jakiś inny plugin go ruszy
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        backups.add(e.getEntity(), "DEATH");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        backups.add(e.getPlayer(), "QUIT");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Long snapshotId = backups.takePending(id);
        if (snapshotId == null) return;

        Backups.Snapshot snapshot = backups.get(id, snapshotId).orElse(null);
        if (snapshot == null) return;

        // chwila zwłoki, żeby inne pluginy zdążyły wczytać swoje rzeczy
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            backups.restore(p, snapshot);
            p.sendMessage(Component.text("Twój ekwipunek został przywrócony z backupu.", NamedTextColor.GREEN));
        }, 20L);
    }
}
