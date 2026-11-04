package pl.serwer;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Pluginy serwera w jednym:
 * 1. limity poziomów enchantów (domyślnie protection 3, sharpness 3),
 * 2. backupy ekwipunku graczy z menu do przywracania (/backup),
 * 3. enchant Chain Rush z umiejętnościami dash i chain (/enchantdash, /enchantchain, /dashitem),
 * 4. zaklęte kowadło bez limitu enchantów (/giveanvil).
 */
public final class ServerExtras extends JavaPlugin {

    private Backups backups;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        backups = new Backups(this);
        BackupMenu menu = new BackupMenu(this, backups);
        Dash dash = new Dash(this);
        SpecialAnvil anvil = new SpecialAnvil(this, dash);

        getServer().getPluginManager().registerEvents(new EnchantCaps(this, anvil), this);
        getServer().getPluginManager().registerEvents(new BackupListener(this, backups), this);
        getServer().getPluginManager().registerEvents(menu, this);
        getServer().getPluginManager().registerEvents(anvil, this);

        bind("backup", menu);
        bind("enchantdash", dash);
        bind("dashitem", dash);
        bind("enchantchain", dash);
        bind("givechainrush", dash);
        dash.startHud();
        bind("giveanvil", anvil);
    }

    private void bind(String name, CommandExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) cmd.setExecutor(executor);
    }

    @Override
    public void onDisable() {
        // przy zamykaniu serwera zdarzenia wyjścia mogą już nie dojść do pluginu,
        // więc zapisujemy ekwipunek wszystkich, którzy jeszcze są online
        if (backups == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            backups.add(p, "STOP");
        }
    }
}
