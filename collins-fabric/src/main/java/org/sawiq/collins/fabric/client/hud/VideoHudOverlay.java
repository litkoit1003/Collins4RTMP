package org.sawiq.collins.fabric.client.hud;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.sawiq.collins.fabric.client.util.TimeFormatUtil;
import org.sawiq.collins.fabric.client.video.VideoScreen;
import org.sawiq.collins.fabric.client.video.VideoScreenManager;

public final class VideoHudOverlay {

    private VideoHudOverlay() {
    }

    public static void init() {
        HudRenderCallback.EVENT.register(VideoHudOverlay::render);
    }

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.textRenderer == null) return;
        if (client.currentScreen instanceof ChatScreen) return;

        VideoScreen screen = VideoScreenManager.findNearestPlayingOrEnded(client.player.getPos());
        if (screen == null) return;

        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int y = sh - 59;

        // Проверяем идёт ли скачивание
        if (screen.isDownloading()) {
            int pct = screen.getDownloadPercent();
            long dlMb = screen.getDownloadedMb();
            long totalMb = screen.getDownloadTotalMb();
            String text;
            if (totalMb > 0) {
                text = "§eСкачивание видео... " + dlMb + " МБ / " + totalMb + " МБ (" + pct + "%)";
            } else {
                text = "§eСкачивание видео... " + dlMb + " МБ";
            }
            ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(text), sw / 2, y, 0xFFFF00);
            return;
        }

        // Проверяем закончилось ли видео
        if (screen.isEnded()) {
            String text = "§aСеанс окончен";
            if (screen.hasCachedFile()) {
                text += " §7(" + screen.getCachedFileSizeMb() + " МБ на диске)";
            }
            ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(text), sw / 2, y, 0x55FF55);
            // Показываем подсказки по удалению кэша
            if (screen.hasCachedFile()) {
                String hint = "§7/collins-cache delete — удалить | /collins-cache open — открыть папку";
                ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(hint), sw / 2, y + 12, 0x888888);
            }
            return;
        }

        // Проверяем hasEnded без ограничения времени - просто не показываем таймлайн
        if (screen.hasEnded()) {
            return;
        }

        long serverNowMs = VideoScreenManager.estimateServerNowMs();
        long posMs = screen.currentPosMsForDisplay(serverNowMs);
        long durMs = screen.durationMs();

        String text = (durMs > 0)
                ? (TimeFormatUtil.formatMs(posMs) + " / " + TimeFormatUtil.formatMs(durMs))
                : TimeFormatUtil.formatMs(posMs);

        int color = 0x00FF00;

        ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(text).formatted(Formatting.GREEN), sw / 2, y, color);
    }
}
