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

        VideoScreen screen = VideoScreenManager.findNearestPlaying(client.player.getEntityPos());
        if (screen == null) return;

        long serverNowMs = VideoScreenManager.estimateServerNowMs();
        long posMs = screen.currentPosMs(serverNowMs);
        long durMs = screen.durationMs();

        String text = (durMs > 0)
                ? (TimeFormatUtil.formatMs(posMs) + " / " + TimeFormatUtil.formatMs(durMs))
                : TimeFormatUtil.formatMs(posMs);

        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();

        int y = sh - 59;
        int color = 0x00FF00;

        ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(text).formatted(Formatting.GREEN), sw / 2, y, color);
    }
}
