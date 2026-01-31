package org.sawiq.collins.paper;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.sawiq.collins.paper.command.CollinsCommand;
import org.sawiq.collins.paper.model.Playlist;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.net.CollinsClientMessageListener;
import org.sawiq.collins.paper.net.CollinsMessenger;
import org.sawiq.collins.paper.selection.SelectionService;
import org.sawiq.collins.paper.state.CollinsRuntimeState;
import org.sawiq.collins.paper.store.PlaylistStore;
import org.sawiq.collins.paper.store.ScreenStore;
import org.sawiq.collins.paper.util.FFprobeUtil;
import org.sawiq.collins.paper.util.Lang;
import org.sawiq.collins.paper.util.ToolsDownloader;

public final class CollinsPaperPlugin extends JavaPlugin implements Listener {

    private ScreenStore store;
    private PlaylistStore playlistStore;
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

        playlistStore = new PlaylistStore(this);
        playlistStore.load();

        runtime = new CollinsRuntimeState();
        selection = new SelectionService();

        messenger = new CollinsMessenger(this, store, runtime);

        var cmd = new CollinsCommand(this, store, playlistStore, messenger, selection, runtime, lang);
        var pluginCmd = getCommand("collins");
        if (pluginCmd != null) {
            pluginCmd.setExecutor(cmd);
            pluginCmd.setTabCompleter(cmd);
        } else {
            getLogger().severe("Command /collins not found in plugin.yml");
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, "collins:main");
        Bukkit.getMessenger().registerIncomingPluginChannel(this, "collins:main",
            new CollinsClientMessageListener(this, store, messenger, runtime));

        // FFprobe/yt-dlp
        ToolsDownloader.init(getLogger(), getDataFolder().toPath());
        if (getConfig().getBoolean("ffprobe.auto_download", true)) {
            ToolsDownloader.ensureToolsAsync();
        }

        String ffprobePath = getConfig().getString("ffprobe.path", "");
        String ytdlpPath = getConfig().getString("ffprobe.ytdlp", "");
        if (ffprobePath.isEmpty() || ffprobePath.equals("auto")) ffprobePath = ToolsDownloader.getFfprobePath();
        if (ytdlpPath.isEmpty() || ytdlpPath.equals("auto")) ytdlpPath = ToolsDownloader.getYtdlpPath();
        FFprobeUtil.init(getLogger(), ffprobePath, ytdlpPath, getConfig().getInt("ffprobe.timeout", 30));

        Bukkit.getScheduler().runTaskTimer(this, this::checkVideoEndings, 40L, 40L);

        getLogger().info("collins-paper enabled. Loaded screens: " + store.all().size());
    }

    /**
     * Проверяет все экраны и обрабатывает окончание видео (плейлист или остановка)
     */
    private void checkVideoEndings() {
        boolean needBroadcast = false;

        for (var screen : store.all()) {
            if (!screen.playing()) continue;
            
            if (screen.loop()) continue;

            long currentPos = runtime.getCurrentPosMs(screen.name());
            long duration = runtime.getDurationMs(screen.name());
            
            // Получаем duration через FFprobe если неизвестен
            if (duration == 0 && currentPos > 5000) {
                String url = screen.mp4Url();
                if (url != null && !url.isEmpty()) {
                    final String screenName = screen.name();
                    FFprobeUtil.getDurationMs(url).thenAccept(durationMs -> {
                        if (durationMs > 0) {
                            Bukkit.getScheduler().runTask(this, () -> {
                                runtime.setDurationFromServer(screenName, durationMs);
                            });
                        }
                    });
                    runtime.get(screen.name()).durationMs = -1;
                }
            }
            
            boolean ended = runtime.isVideoEnded(screen.name());
            
            if (ended) {
                getLogger().info("Video time ended for screen '" + screen.name() + "'");

                Playlist playlist = Playlist.get(screen.name());
                if (playlist != null && playlist.isEnabled() && !playlist.isEmpty()) {
                    Playlist.PlaylistEntry nextEntry = playlist.next();
                    if (nextEntry != null) {
                        getLogger().info("Playlist auto-advance for '" + screen.name() + "' -> [" + nextEntry.index() + "] " + nextEntry.title());
                        
                        Screen updated = new Screen(
                                screen.name(), screen.world(),
                                screen.x1(), screen.y1(), screen.z1(),
                                screen.x2(), screen.y2(), screen.z2(),
                                screen.axis(),
                                nextEntry.url(),
                                true,
                                false,
                                screen.volume()
                        );
                        runtime.resetPlayback(screen.name());
                        CollinsRuntimeState.Playback pb = runtime.get(screen.name());
                        pb.startEpochMs = System.currentTimeMillis();
                        pb.basePosMs = 0;
                        
                        store.put(updated);
                        needBroadcast = true;
                        continue;
                    } else {
                        getLogger().info("Playlist ended for screen '" + screen.name() + "'");
                    }
                }

                var updated = screen.withPlaying(false);
                store.put(updated);
                runtime.resetPlayback(screen.name());
                needBroadcast = true;
            }
        }

        if (needBroadcast) {
            store.save();
            playlistStore.save();
            messenger.requestBroadcastSync();
        }
    }

    public Lang lang() {
        return lang;
    }

    @Override
    public void onDisable() {
        store.save();
        playlistStore.save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> messenger.sendSync(e.getPlayer()), 20L);
    }
}
