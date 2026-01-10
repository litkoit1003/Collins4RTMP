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

    private static final int GREEN = 0x00FF00;
    private static final Text PREFIX = Text.literal("[Collins-Fabric] ").setStyle(Style.EMPTY.withColor(GREEN));

    private static volatile long lastActionbarUpdateMs = 0;

    public static Collection<VideoScreen> all() {
        return SCREENS.values();
    }

    public static VideoScreen getByName(String name) {
        if (name == null) return null;
        return SCREENS.get(name.toLowerCase(Locale.ROOT));
    }

    public static VideoScreen findNearestPlaying(Vec3d playerPos) {
        if (playerPos == null) return null;

        VideoScreen best = null;
        double bestDist2 = Double.MAX_VALUE;

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st == null) continue;
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

        VideoScreen best = null;
        double bestDist2 = Double.MAX_VALUE;
        double r = (double) radiusBlocks;
        double r2 = r * r;

        for (VideoScreen s : SCREENS.values()) {
            ScreenState st = s.state();
            if (st == null) continue;
            if (!st.playing()) continue;
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

        Vec3d pos = p.getPos();

        // ВАЖНО: используем server-sent настройки (а не тестовые константы)
        int radius = CollinsNet.HEAR_RADIUS;
        float globalVolume = CollinsNet.GLOBAL_VOLUME;

        long serverNowMs = estimateServerNowMs();

        for (VideoScreen s : SCREENS.values()) {
            s.tickPlayback(pos, radius, globalVolume, serverNowMs);
        }

        CollinsClientConfig cfg = CollinsClientConfig.get();
        if (cfg.renderVideo && cfg.actionbarTimeline && !(client.currentScreen instanceof ChatScreen)) {
            long now = System.currentTimeMillis();
            if (now - lastActionbarUpdateMs >= 500L) {
                lastActionbarUpdateMs = now;

                VideoScreen nearest = findNearestPlayingInRadius(pos, radius);
                if (nearest != null) {
                    long posMs = nearest.currentPosMsForDisplay(serverNowMs);
                    long durMs = nearest.durationMs();

                    String text = (durMs > 0)
                            ? (nearest.state().name() + ": " + TimeFormatUtil.formatMs(posMs) + " / " + TimeFormatUtil.formatMs(durMs))
                            : (nearest.state().name() + ": " + TimeFormatUtil.formatMs(posMs));

                    p.sendMessage(PREFIX.copy().append(Text.literal(text).setStyle(Style.EMPTY.withColor(GREEN))), true);
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
}
