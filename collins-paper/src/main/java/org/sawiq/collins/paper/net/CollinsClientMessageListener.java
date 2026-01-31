package org.sawiq.collins.paper.net;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.state.CollinsRuntimeState;
import org.sawiq.collins.paper.store.ScreenStore;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Обработчик сообщений от клиента (C2S)
 */
public final class CollinsClientMessageListener implements PluginMessageListener {

    private final JavaPlugin plugin;
    private final ScreenStore store;
    private final CollinsMessenger messenger;
    private final CollinsRuntimeState runtime;

    public CollinsClientMessageListener(JavaPlugin plugin, ScreenStore store,
                                        CollinsMessenger messenger, CollinsRuntimeState runtime) {
        this.plugin = plugin;
        this.store = store;
        this.messenger = messenger;
        this.runtime = runtime;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!channel.equals("collins:main")) return;
        if (message.length < 8) return; // минимум: magic(4) + len(4)

        try {
            parseMessage(player, message);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse client message: " + e.getMessage());
        }
    }

    private void parseMessage(Player player, byte[] bytes) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            // Читаем magic
            byte[] magic = new byte[4];
            in.readFully(magic);
            String m = new String(magic, StandardCharsets.US_ASCII);
            if (!m.equals("COLL")) {
                return;
            }

            // Читаем длину
            int len = in.readInt();
            if (len < 0 || len > 1024) { // Ограничиваем размер
                return;
            }

            if (in.available() < len) {
                return;
            }

            byte[] inner = new byte[len];
            in.readFully(inner);

            parseInner(player, inner);
        }
    }

    private void parseInner(Player player, byte[] inner) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(inner))) {
            byte msgType = in.readByte();
            int version = in.readInt();

            if (version != CollinsProtocol.PROTOCOL_VERSION) {
                return; // Несовместимая версия
            }

            switch (msgType) {
                case CollinsProtocol.MSG_VIDEO_ENDED -> handleVideoEnded(player, in);
                case CollinsProtocol.MSG_VIDEO_DURATION -> handleVideoDuration(player, in);
                default -> {
                    // Неизвестный тип сообщения
                }
            }
        }
    }

    /**
     * Обработка сообщения об окончании видео (ИГНОРИРУЕТСЯ - небезопасно доверять клиенту)
     * Сервер сам определяет окончание по времени через checkVideoEndings()
     */
    private void handleVideoEnded(Player player, DataInputStream in) throws Exception {
        String screenName = in.readUTF();
        // Игнорируем - сервер сам определяет окончание по durationMs
        plugin.getLogger().fine("Ignored VIDEO_ENDED from " + player.getName() + " for screen: " + screenName + " (server determines ending by time)");
    }

    /**
     * Обработка сообщения о длительности видео
     * Формат: UTF screenName, long durationMs
     */
    private void handleVideoDuration(Player player, DataInputStream in) throws Exception {
        String screenName = in.readUTF();
        long durationMs = in.readLong();

        Screen screen = store.get(screenName);
        if (screen == null) {
            return; // Экран не найден
        }

        if (durationMs > 0) {
            runtime.setDuration(screenName, durationMs);
        }
    }
}
