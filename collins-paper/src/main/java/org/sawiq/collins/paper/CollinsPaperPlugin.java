package org.sawiq.collins.paper;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.sawiq.collins.paper.command.CollinsCommand;
import org.sawiq.collins.paper.net.CollinsMessenger;
import org.sawiq.collins.paper.selection.SelectionService;
import org.sawiq.collins.paper.state.CollinsRuntimeState;
import org.sawiq.collins.paper.store.ScreenStore;
import org.sawiq.collins.paper.util.Lang;

public final class CollinsPaperPlugin extends JavaPlugin implements Listener {

    private ScreenStore store;
    private CollinsMessenger messenger;
    private SelectionService selection;
    private CollinsRuntimeState runtime;
    private Lang lang;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String language = getConfig().getString("language", "en");
        lang = new Lang(this, language);

        store = new ScreenStore(this);
        store.load();

        runtime = new CollinsRuntimeState();
        selection = new SelectionService();

        messenger = new CollinsMessenger(this, store, runtime);

        var cmd = new CollinsCommand(this, store, messenger, selection, runtime, lang);
        var pluginCmd = getCommand("collins");
        if (pluginCmd != null) {
            pluginCmd.setExecutor(cmd);
            pluginCmd.setTabCompleter(cmd);
        } else {
            getLogger().severe("Command /collins not found in plugin.yml");
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, "collins:main");

        getLogger().info("collins-paper enabled. Loaded screens: " + store.all().size());
    }

    public Lang lang() {
        return lang;
    }

    @Override
    public void onDisable() {
        store.save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // через 1 секунду после входа (как у тебя было)
        Bukkit.getScheduler().runTaskLater(this, () -> messenger.sendSync(e.getPlayer()), 20L);
    }
}
