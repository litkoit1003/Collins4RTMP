package org.sawiq.collins.paper.util;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Автозагрузка ffprobe/yt-dlp в plugins/collins-paper/tools/
 */
public final class ToolsDownloader {

    private static final String YTDLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
    private static final String FFMPEG_URL = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";

    private static Logger logger;
    private static Path toolsDir;
    private static volatile boolean downloading = false;

    public static void init(Logger log, Path pluginDataFolder) {
        logger = log;
        toolsDir = pluginDataFolder.resolve("tools");
    }

    public static String getYtdlpPath() {
        Path p = toolsDir.resolve("yt-dlp.exe");
        if (Files.isRegularFile(p)) {
            return p.toAbsolutePath().toString();
        }
        return "yt-dlp";
    }

    public static String getFfprobePath() {
        Path p = toolsDir.resolve("ffprobe.exe");
        if (Files.isRegularFile(p)) {
            return p.toAbsolutePath().toString();
        }
        return "ffprobe";
    }

    public static void ensureToolsAsync() {
        if (downloading) return;

        Thread t = new Thread(() -> {
            downloading = true;
            try {
                ensureTools();
            } finally {
                downloading = false;
            }
        }, "Collins-ToolsDownloader");
        t.setDaemon(true);
        t.start();
    }

    public static void ensureTools() {
        try {
            Files.createDirectories(toolsDir);
        } catch (Exception e) {
            log("Failed to create tools directory: " + e.getMessage());
            return;
        }

        Path ytdlp = toolsDir.resolve("yt-dlp.exe");
        if (!Files.isRegularFile(ytdlp)) {
            log("yt-dlp not found, downloading...");
            downloadFile(YTDLP_URL, ytdlp);
        } else {
            log("yt-dlp found: " + ytdlp);
        }

        Path ffprobe = toolsDir.resolve("ffprobe.exe");
        if (!Files.isRegularFile(ffprobe)) {
            log("ffprobe not found, downloading FFmpeg...");
            downloadFfmpeg(ffprobe);
        } else {
            log("ffprobe found: " + ffprobe);
        }
    }

    private static boolean downloadFile(String urlStr, Path target) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Collins-Paper-Plugin");

            int code = conn.getResponseCode();
            if (code != 200) {
                log("Download failed, HTTP " + code + ": " + urlStr);
                conn.disconnect();
                return false;
            }

            long contentLength = conn.getContentLengthLong();
            log("Downloading " + (contentLength > 0 ? (contentLength / 1024 / 1024) + " MB" : "..."));

            try (InputStream in = conn.getInputStream();
                 var out = Files.newOutputStream(tmp)) {
                
                byte[] buf = new byte[64 * 1024];
                long written = 0;
                int r;
                int lastPercent = 0;
                while ((r = in.read(buf)) >= 0) {
                    out.write(buf, 0, r);
                    written += r;
                    if (contentLength > 0) {
                        int percent = (int) (written * 100 / contentLength);
                        if (percent >= lastPercent + 10) {
                            log("Download progress: " + percent + "%");
                            lastPercent = percent;
                        }
                    }
                }
            }

            conn.disconnect();
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log("Downloaded: " + target.getFileName());
            return true;

        } catch (Exception e) {
            log("Download error: " + e.getMessage());
            return false;
        }
    }

    private static boolean downloadFfmpeg(Path ffprobeTarget) {
        try {
            Path zipTmp = toolsDir.resolve("ffmpeg.zip.tmp");
            Files.deleteIfExists(zipTmp);

            URL url = new URL(FFMPEG_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(300_000); // 5 минут для большого файла
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Collins-Paper-Plugin");

            int code = conn.getResponseCode();
            if (code != 200) {
                log("FFmpeg download failed, HTTP " + code);
                conn.disconnect();
                return false;
            }

            long contentLength = conn.getContentLengthLong();
            log("Downloading FFmpeg " + (contentLength > 0 ? (contentLength / 1024 / 1024) + " MB" : "..."));

            try (InputStream in = conn.getInputStream();
                 var out = Files.newOutputStream(zipTmp)) {
                
                byte[] buf = new byte[64 * 1024];
                long written = 0;
                int r;
                int lastPercent = 0;
                while ((r = in.read(buf)) >= 0) {
                    out.write(buf, 0, r);
                    written += r;
                    if (contentLength > 0) {
                        int percent = (int) (written * 100 / contentLength);
                        if (percent >= lastPercent + 10) {
                            log("FFmpeg download: " + percent + "%");
                            lastPercent = percent;
                        }
                    }
                }
            }
            conn.disconnect();

            log("Extracting ffprobe.exe...");
            boolean extracted = false;
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipTmp))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.endsWith("ffprobe.exe") && !entry.isDirectory()) {
                        log("Found: " + name);
                        Files.copy(zis, ffprobeTarget, StandardCopyOption.REPLACE_EXISTING);
                        extracted = true;
                    }
                    if (name.endsWith("ffmpeg.exe") && !entry.isDirectory()) {
                        Path ffmpeg = toolsDir.resolve("ffmpeg.exe");
                        if (!Files.exists(ffmpeg)) {
                            Files.copy(zis, ffmpeg, StandardCopyOption.REPLACE_EXISTING);
                            log("Also extracted: ffmpeg.exe");
                        }
                    }
                    zis.closeEntry();
                }
            }

            Files.deleteIfExists(zipTmp);

            if (extracted) {
                log("ffprobe.exe extracted successfully");
                return true;
            } else {
                log("ffprobe.exe not found in archive!");
                return false;
            }

        } catch (Exception e) {
            log("FFmpeg download/extract error: " + e.getMessage());
            return false;
        }
    }

    private static void log(String msg) {
        if (logger != null) {
            logger.info("[ToolsDownloader] " + msg);
        } else {
            System.out.println("[Collins-ToolsDownloader] " + msg);
        }
    }
}
