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
import org.sawiq.collins.fabric.client.video.YouTubeResolver;

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

            // Команды для YouTube/yt-dlp
            dispatcher.register(ClientCommandManager.literal("collins-yt")
                    .executes(ctx -> showYouTubeInfo())
                    .then(ClientCommandManager.literal("info")
                            .executes(ctx -> showYouTubeInfo()))
                    .then(ClientCommandManager.literal("install")
                            .executes(ctx -> installYtdlp()))
                    .then(ClientCommandManager.literal("update")
                            .executes(ctx -> updateYtdlp())));

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

        // Если нет сохранённого пути, попробуем найти от ближайшего ended экрана
        if (path == null || path.isEmpty()) {
            VideoScreen screen = VideoScreenManager.findNearestPlayingOrEnded(client.player.getPos());
            if (screen != null && screen.hasCachedFile()) {
                path = screen.getCachedFilePath();
            }
        }

        if (path == null || path.isEmpty()) {
            client.player.sendMessage(PREFIX.copy().append(
                Text.literal("Нет файла для удаления").setStyle(Style.EMPTY.withColor(RED))), false);
            return 0;
        }

        boolean deleted = VideoPlayer.deleteCachedFile(path);
        if (deleted) {
            VideoScreenManager.clearPendingDeletePath();
            client.player.sendMessage(PREFIX.copy().append(
                Text.literal("✓ Видео удалено с диска").setStyle(Style.EMPTY.withColor(GREEN))), false);
        } else {
            client.player.sendMessage(PREFIX.copy().append(
                Text.literal("✗ Не удалось удалить файл: " + path).setStyle(Style.EMPTY.withColor(RED))), false);
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
            screen = VideoScreenManager.findNearestPlaying(client.player.getEntityPos());
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

    private static int showYouTubeInfo() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        boolean available = YouTubeResolver.isYtdlpAvailable();
        String version = available ? YouTubeResolver.getYtdlpVersion() : "не установлен";
        boolean downloading = YouTubeResolver.isDownloading();
        int progress = YouTubeResolver.getDownloadProgress();

        Text msg = PREFIX.copy()
            .append(Text.literal("YouTube (yt-dlp):\n").setStyle(Style.EMPTY.withColor(GREEN)))
            .append(Text.literal("  Статус: ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal(available ? "✓ Установлен" : "✗ Не установлен").setStyle(Style.EMPTY.withColor(available ? GREEN : RED)))
            .append(Text.literal("\n"))
            .append(Text.literal("  Версия: ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal(version + "\n").setStyle(Style.EMPTY.withColor(Formatting.WHITE)));

        if (downloading) {
            msg = msg.copy()
                .append(Text.literal("  Загрузка: ").setStyle(Style.EMPTY.withColor(GRAY)))
                .append(Text.literal(progress + "%\n").setStyle(Style.EMPTY.withColor(YELLOW)));
        }

        msg = msg.copy()
            .append(Text.literal("Команды: ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal("/collins-yt install").setStyle(Style.EMPTY.withColor(YELLOW)))
            .append(Text.literal(" | ").setStyle(Style.EMPTY.withColor(GRAY)))
            .append(Text.literal("/collins-yt update").setStyle(Style.EMPTY.withColor(YELLOW)));

        client.player.sendMessage(msg, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int installYtdlp() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        if (YouTubeResolver.isYtdlpAvailable()) {
            client.player.sendMessage(PREFIX.copy().append(
                Text.literal("yt-dlp уже установлен. Версия: " + YouTubeResolver.getYtdlpVersion())
                    .setStyle(Style.EMPTY.withColor(GREEN))), false);
            return Command.SINGLE_SUCCESS;
        }

        if (YouTubeResolver.isDownloading()) {
            client.player.sendMessage(PREFIX.copy().append(
                Text.literal("yt-dlp уже скачивается: " + YouTubeResolver.getDownloadProgress() + "%")
                    .setStyle(Style.EMPTY.withColor(YELLOW))), false);
            return Command.SINGLE_SUCCESS;
        }

        client.player.sendMessage(PREFIX.copy().append(
            Text.literal("Скачивание yt-dlp... Это займёт около минуты.")
                .setStyle(Style.EMPTY.withColor(YELLOW))), false);

        YouTubeResolver.downloadYtdlpAsync();
        return Command.SINGLE_SUCCESS;
    }

    private static int updateYtdlp() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        if (!YouTubeResolver.isYtdlpAvailable()) {
            client.player.sendMessage(PREFIX.copy().append(
                Text.literal("yt-dlp не установлен. Используйте /collins-yt install")
                    .setStyle(Style.EMPTY.withColor(RED))), false);
            return 0;
        }

        client.player.sendMessage(PREFIX.copy().append(
            Text.literal("Обновление yt-dlp... Подождите.")
                .setStyle(Style.EMPTY.withColor(YELLOW))), false);

        Thread t = new Thread(() -> {
            boolean success = YouTubeResolver.updateYtdlp();
            MinecraftClient.getInstance().execute(() -> {
                if (client.player != null) {
                    if (success) {
                        client.player.sendMessage(PREFIX.copy().append(
                            Text.literal("✓ yt-dlp обновлён. Версия: " + YouTubeResolver.getYtdlpVersion())
                                .setStyle(Style.EMPTY.withColor(GREEN))), false);
                    } else {
                        client.player.sendMessage(PREFIX.copy().append(
                            Text.literal("✗ Не удалось обновить yt-dlp")
                                .setStyle(Style.EMPTY.withColor(RED))), false);
                    }
                }
            });
        }, "Collins-YtdlpUpdate");
        t.setDaemon(true);
        t.start();

        return Command.SINGLE_SUCCESS;
    }
}
