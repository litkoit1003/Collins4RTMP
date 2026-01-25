package org.sawiq.collins.fabric.client.video;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.sawiq.collins.fabric.client.config.CollinsClientConfig;
import org.sawiq.collins.fabric.client.net.CollinsNet;
import org.sawiq.collins.fabric.client.state.ScreenState;
import org.sawiq.collins.fabric.client.util.TimeFormatUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VideoScreenManager {

    private static final boolean DEBUG = false;

    private VideoScreenManager() {}

    private static final Map<String, VideoScreen> SCREENS = new ConcurrentHashMap<>();

    // Экраны которым уже показали сообщение о удалении кэша
    private static final Set<String> SHOWN_DELETE_PROMPT = ConcurrentHashMap.newKeySet();
    // Путь к файлу для удаления (последний предложенный)
    private static volatile String pendingDeletePath = null;

    private static final int GREEN = 0x00FF00;
    private static final int GRAY = 0xAAAAAA;
    private static final int YELLOW = 0xFFFF55;
    private static final int RED = 0xFF5555;
    private static final Text PREFIX = Text.literal("[Collins-Fabric] ").setStyle(Style.EMPTY.withColor(GREEN));

    private static volatile long lastActionbarUpdateMs = 0;
    private static volatile String lastClientWorldKey = "";

    static String currentWorldKey(MinecraftClient client) {
        if (client == null) return "";
        try {
            if (client.world != null && client.world.getRegistryKey() != null) {
                String k = client.world.getRegistryKey().getValue().toString();
                return (k == null) ? "" : k;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String defaultBukkitWorldNameForDim(String dimKey) {
        if (dimKey == null || dimKey.isBlank()) return null;
        String k = dimKey.toLowerCase(Locale.ROOT);
        if (k.equals("minecraft:overworld")) return "world";
        if (k.equals("minecraft:the_nether")) return "world_nether";
        if (k.equals("minecraft:the_end")) return "world_the_end";
        return null;
    }

    private static boolean isDefaultBukkitWorldName(String w) {
        if (w == null) return false;
        String s = w.toLowerCase(Locale.ROOT);
        return s.equals("world") || s.equals("world_nether") || s.equals("world_the_end");
    }

    static boolean isCompatibleWithCurrentWorld(ScreenState st, MinecraftClient client) {
        if (st == null || client == null) return true;
        String sw = st.world();
        if (sw == null || sw.isBlank()) return true;

        String dimKey = currentWorldKey(client);
        if (sw.regionMatches(true, 0, "minecraft:", 0, "minecraft:".length())) {
            return sw.equalsIgnoreCase(dimKey);
        }

        if (isDefaultBukkitWorldName(sw)) {
            String expected = defaultBukkitWorldNameForDim(dimKey);
            return expected != null && sw.equalsIgnoreCase(expected);
        }

        return true;
    }

    public static Collection<VideoScreen> all() {
        return SCREENS.values();
    }

    public static VideoScreen getByName(String name) {
        if (name == null) return null;
        return SCREENS.get(name.toLowerCase(Locale.ROOT));
    }

    public static VideoScreen findNearestPlaying(Vec3d playerPos) {
        if (playerPos == null) return null;

        MinecraftClient client = MinecraftClient.getInstance();

        VideoScreen best = null;
        double bestDist2 = Double.MAX_VALUE;

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st == null) continue;
            if (!isCompatibleWithCurrentWorld(st, client)) continue;
            if (!st.playing()) continue;
            if (st.url() == null || st.url().isEmpty()) continue;

            double cx = (st.minX() + st.maxX() + 1) * 0.5;
            double cy = (st.minY() + st.maxY() + 1) * 0.5;
            double cz = (st.minZ() + st.maxZ() + 1) * 0.5;

            double dx = playerPos.x - cx;
            double dy = playerPos.y - cy;
            double dz = playerPos.z - cz;

            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = s;
            }
        }

        return best;
    }

    private static VideoScreen findNearestPlayingInRadius(Vec3d playerPos, int radiusBlocks) {
        if (playerPos == null) return null;
        if (radiusBlocks <= 0) return findNearestPlaying(playerPos);

        MinecraftClient client = MinecraftClient.getInstance();

        VideoScreen best = null;
        double bestDist2 = Double.MAX_VALUE;
        double r = (double) radiusBlocks;
        double r2 = r * r;

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st == null) continue;
            if (!isCompatibleWithCurrentWorld(st, client)) continue;
            // Показываем и playing экраны, и ended (чтобы показать "Сеанс окончен")
            if (!st.playing() && !s.isEnded()) continue;
            if (st.url() == null || st.url().isEmpty()) continue;

            double cx = (st.minX() + st.maxX() + 1) * 0.5;
            double cy = (st.minY() + st.maxY() + 1) * 0.5;
            double cz = (st.minZ() + st.maxZ() + 1) * 0.5;

            double dx = playerPos.x - cx;
            double dy = playerPos.y - cy;
            double dz = playerPos.z - cz;

            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2) continue;

            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = s;
            }
        }

        return best;
    }

    public static void applySync(Map<String, ScreenState> incoming) {
        Set<String> keep = new HashSet<>(incoming.keySet());

        // 1) удалённые экраны
        for (String key : new ArrayList<>(SCREENS.keySet())) {
            if (!keep.contains(key)) {
                VideoScreen vs = SCREENS.remove(key);
                if (vs != null) {
                    if (DEBUG) System.out.println("[Collins] STOP by remove: key=" + key);
                    vs.stop();
                }
            }
        }

        // 2) обновляем существующие/создаём новые
        for (var e : incoming.entrySet()) {
            String key = e.getKey();
            ScreenState st = e.getValue();

            VideoScreen vs = SCREENS.get(key);
            if (vs == null) {
                vs = new VideoScreen(st);
                SCREENS.put(key, vs);
                if (DEBUG) System.out.println("[Collins] screen created: key=" + key + " name=" + st.name());
            } else {
                vs.updateState(st);
            }

            // 3) если сервер сказал остановить — останавливаем
            if (!st.playing() || st.url() == null || st.url().isEmpty()) {
                if (DEBUG) System.out.println("[Collins] STOP by sync: name=" + st.name()
                        + " playing=" + st.playing()
                        + " url=" + st.url());
                vs.stop();
            }
        }
    }

    public static void tick(MinecraftClient client) {
        PlayerEntity p = client.player;
        if (p == null) return;

        // При смене мира/измерения (в т.ч. сервер/ад/энд) очищаем локальные экраны,
        // иначе могут "прилипнуть" экраны от предыдущего подключения.
        String worldKey = currentWorldKey(client);
        if (!worldKey.equals(lastClientWorldKey)) {
            lastClientWorldKey = worldKey;
            stopAllPlayback();
        }

        Vec3d pos = p.getEntityPos();

        // ВАЖНО: используем server-sent настройки (а не тестовые константы)
        int radius = CollinsNet.HEAR_RADIUS;
        float globalVolume = CollinsNet.GLOBAL_VOLUME;

        long serverNowMs = estimateServerNowMs();

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st != null && !isCompatibleWithCurrentWorld(st, client)) {
                if (st.playing()) s.stop();
                continue;
            }
            s.tickPlayback(pos, radius, globalVolume, serverNowMs);
        }

        CollinsClientConfig cfg = CollinsClientConfig.get();
        if (cfg.renderVideo && cfg.actionbarTimeline && !(client.currentScreen instanceof ChatScreen)) {
            long now = System.currentTimeMillis();
            if (now - lastActionbarUpdateMs >= 500L) {
                lastActionbarUpdateMs = now;

                VideoScreen nearest = findNearestPlayingInRadius(pos, radius);
                if (nearest != null) {
                    String text = null;
                    int color = GREEN;

                    // Если видео закончилось — показываем предложение удаления в чат (не в action bar)
                    if (nearest.isEnded()) {
                        // Показываем предложение удалить кэш (только один раз и только для скачанных видео)
                        String screenKey = nearest.state().name() + "_" + nearest.state().url();
                        if (nearest.hasCachedFile() && !SHOWN_DELETE_PROMPT.contains(screenKey)) {
                            SHOWN_DELETE_PROMPT.add(screenKey);
                            pendingDeletePath = nearest.getCachedFilePath();
                            long sizeMb = nearest.getCachedFileSizeMb();

                            // Сообщение в чат с командами
                            p.sendMessage(PREFIX.copy()
                                .append(Text.literal("Сеанс окончен. Видео занимает " + sizeMb + " МБ на диске.\n").setStyle(Style.EMPTY.withColor(GRAY)))
                                .append(Text.literal("  /collins-cache delete").setStyle(Style.EMPTY.withColor(RED)))
                                .append(Text.literal(" — удалить видео\n").setStyle(Style.EMPTY.withColor(GRAY)))
                                .append(Text.literal("  /collins-cache open").setStyle(Style.EMPTY.withColor(YELLOW)))
                                .append(Text.literal(" — открыть папку").setStyle(Style.EMPTY.withColor(GRAY))), false);
                        }
                        // Action bar покажет пустую строку (очистит)
                        text = "";
                    }
                    // Если видео уже закончилось (hasEnded) но прошло 5 секунд — ничего не показываем
                    else if (nearest.hasEnded()) {
                        text = "";
                    }
                    // Если идёт скачивание — показываем прогресс
                    else if (nearest.isDownloading()) {
                        int pct = nearest.getDownloadPercent();
                        long dlMb = nearest.getDownloadedMb();
                        long totalMb = nearest.getDownloadTotalMb();
                        if (totalMb > 0) {
                            text = "⏬ Скачивание: " + pct + "% (" + dlMb + "МБ / " + totalMb + "МБ)";
                        } else {
                            text = "⏬ Скачивание: " + dlMb + "МБ...";
                        }
                        color = YELLOW;
                    } else {
                        // Обычный таймлайн
                        long posMs = nearest.currentPosMsForDisplay(serverNowMs);
                        long durMs = nearest.durationMs();

                        text = (durMs > 0)
                                ? (nearest.state().name() + ": " + TimeFormatUtil.formatMs(posMs) + " / " + TimeFormatUtil.formatMs(durMs))
                                : (nearest.state().name() + ": " + TimeFormatUtil.formatMs(posMs));
                        color = GREEN;
                    }

                    if (text != null && !text.isEmpty()) {
                        p.sendMessage(PREFIX.copy().append(Text.literal(text).setStyle(Style.EMPTY.withColor(color))), true);
                    } else if (text != null) {
                        // Очищаем action bar пустым сообщением
                        p.sendMessage(Text.literal(""), true);
                    }
                }
            }
        }
    }

    public static long estimateServerNowMs() {
        long sn = CollinsNet.SERVER_NOW_MS;
        long cr = CollinsNet.CLIENT_RECV_MS;
        if (sn <= 0 || cr <= 0) return 0;
        return sn + (System.currentTimeMillis() - cr);
    }

    public static void stopAll() {
        if (DEBUG) System.out.println("[Collins] stopAll()");
        for (VideoScreen s : SCREENS.values()) s.stop();
        SCREENS.clear();
    }

    public static void stopAllPlayback() {
        if (DEBUG) System.out.println("[Collins] stopAllPlayback()");
        for (VideoScreen s : SCREENS.values()) s.stop();
    }

    // ==================== Управление кэшем ====================

    /** Получить путь к файлу для удаления */
    public static String getPendingDeletePath() {
        return pendingDeletePath;
    }

    /** Удалить последний предложенный файл */
    public static boolean deletePendingFile() {
        String path = pendingDeletePath;
        if (path != null && !path.isEmpty()) {
            boolean deleted = VideoPlayer.deleteCachedFile(path);
            if (deleted) {
                pendingDeletePath = null;
            }
            return deleted;
        }
        return false;
    }

    /** Очистить кэш сохранённых предложений */
    public static void clearDeletePromptHistory() {
        SHOWN_DELETE_PROMPT.clear();
    }
}
