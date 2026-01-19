package org.sawiq.collins.fabric.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.sawiq.collins.fabric.client.util.TimeFormatUtil;
import org.sawiq.collins.fabric.client.video.VideoPlayer;
import org.sawiq.collins.fabric.client.video.VideoScreen;
import org.sawiq.collins.fabric.client.video.VideoScreenManager;

public final class CollinsClientCommands {

    private static final int GREEN = 0x00FF00;
    private static final int YELLOW = 0xFFFF55;
    private static final int RED = 0xFF5555;
    private static final int GRAY = 0xAAAAAA;
    private static final Text PREFIX = Text.literal("[Collins-Fabric] ").setStyle(Style.EMPTY.withColor(GREEN));

    private CollinsClientCommands() {
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Команда для просмотра таймлайна
            dispatcher.register(ClientCommandManager.literal("collinsc")
                    .then(ClientCommandManager.literal("time")
                            .executes(ctx -> showTimeline(null))
                            .then(ClientCommandManager.argument("screen", StringArgumentType.word())
                                    .executes(ctx -> showTimeline(StringArgumentType.getString(ctx, "screen"))))));

            // Команды для управления кэшем
            dispatcher.register(ClientCommandManager.literal("collins-cache")
                    .executes(ctx -> showCacheInfo())
                    .then(ClientCommandManager.literal("info")
                            .executes(ctx -> showCacheInfo()))
                    .then(ClientCommandManager.literal("open")
                            .executes(ctx -> openCacheFolder()))
                    .then(ClientCommandManager.literal("delete")
                            .executes(ctx -> deletePendingFile()))
                    .then(ClientCommandManager.literal("clear")
                            .executes(ctx -> clearCache())));
        });
    }

    private static int showCacheInfo() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        VideoPlayer.CacheInfo info = VideoPlayer.getCacheInfo();

        Text msg = PREFIX.copy()
            .append(Text.literal("Кэш видео:\n").setStyle(Style.EMPTY.withColor(GREEN)))
            .append(Text.literal("  Папка: ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal(info.cacheDir().toString() + "\n").setStyle(Style.EMPTY.withColor(Formatting.WHITE)))
            .append(Text.literal("  Файлов: ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal(info.fileCount() + " (" + info.cacheSizeMb() + " МБ)\n").setStyle(Style.EMPTY.withColor(Formatting.WHITE)))
            .append(Text.literal("  Свободно на диске: ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal(info.freeSpaceGb() + " ГБ\n").setStyle(Style.EMPTY.withColor(Formatting.WHITE)))
            .append(Text.literal("Команды: ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal("/collins-cache open").setStyle(Style.EMPTY.withColor(YELLOW)))
            .append(Text.literal(" | ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal("/collins-cache clear").setStyle(Style.EMPTY.withColor(RED)));

        client.player.sendMessage(msg, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int openCacheFolder() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        VideoPlayer.openCacheFolder();
        client.player.sendMessage(PREFIX.copy().append(
            Text.literal("Папка кэша открыта").setStyle(Style.EMPTY.withColor(GREEN))), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int deletePendingFile() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String path = VideoScreenManager.getPendingDeletePath();
        if (path == null || path.isEmpty()) {
            client.player.sendMessage(PREFIX.copy().append(
                Text.literal("Нет файла для удаления").setStyle(Style.EMPTY.withColor(RED))), false);
            return 0;
        }

        boolean deleted = VideoScreenManager.deletePendingFile();
        if (deleted) {
            client.player.sendMessage(PREFIX.copy().append(
                Text.literal("✓ Видео удалено с диска").setStyle(Style.EMPTY.withColor(GREEN))), false);
        } else {
            client.player.sendMessage(PREFIX.copy().append(
                Text.literal("✗ Не удалось удалить файл").setStyle(Style.EMPTY.withColor(RED))), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int clearCache() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        long deleted = VideoPlayer.clearCache();
        long deletedMb = deleted / (1024L * 1024L);

        VideoScreenManager.clearDeletePromptHistory();

        client.player.sendMessage(PREFIX.copy().append(
            Text.literal("✓ Кэш очищен. Освобождено: " + deletedMb + " МБ").setStyle(Style.EMPTY.withColor(GREEN))), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int showTimeline(String screenName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        VideoScreen screen = null;
        if (screenName != null && !screenName.isBlank()) {
            screen = VideoScreenManager.getByName(screenName);
        } else {
            screen = VideoScreenManager.findNearestPlaying(client.player.getPos());
        }

        if (screen == null) {
            client.player.sendMessage(PREFIX.copy().append(Text.literal("No active screen").formatted(Formatting.RED)), false);
            return 0;
        }

        long serverNowMs = VideoScreenManager.estimateServerNowMs();
        long posMs = screen.currentPosMsForDisplay(serverNowMs);
        long durMs = screen.durationMs();

        String pos = TimeFormatUtil.formatMs(posMs);
        String msg;
        if (durMs > 0) {
            msg = screen.state().name() + ": " + pos + " / " + TimeFormatUtil.formatMs(durMs);
        } else {
            msg = screen.state().name() + ": " + pos;
        }

        client.player.sendMessage(PREFIX.copy().append(Text.literal(msg).setStyle(Style.EMPTY.withColor(GREEN))), false);
        return Command.SINGLE_SUCCESS;
    }
}
