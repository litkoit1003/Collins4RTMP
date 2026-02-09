package org.sawiq.collins.paper.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Получение длительности видео через FFprobe/yt-dlp (асинхронно)
 */
public class FFprobeUtil {

    private static final Pattern DURATION_PATTERN = Pattern.compile("duration[\"=:]\\s*([0-9.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern YT_DURATION_PATTERN = Pattern.compile("\"duration\"\\s*:\\s*([0-9.]+)");
    
    private static Logger logger;
    private static String configFfprobePath = "";
    private static String configYtdlpPath = "";
    private static int timeoutSeconds = 30;

    public static void init(Logger log, String ffprobe, String ytdlp, int timeout) {
        logger = log;
        configFfprobePath = ffprobe != null ? ffprobe : "";
        configYtdlpPath = ytdlp != null ? ytdlp : "";
        if (timeout > 0) timeoutSeconds = timeout;
    }

    private static String getFfprobePath() {
        if (!configFfprobePath.isEmpty() && !configFfprobePath.equals("auto") && !configFfprobePath.equals("ffprobe")) {
            return configFfprobePath;
        }
        return ToolsDownloader.getFfprobePath();
    }

    private static String getYtdlpPath() {
        if (!configYtdlpPath.isEmpty() && !configYtdlpPath.equals("auto") && !configYtdlpPath.equals("yt-dlp")) {
            return configYtdlpPath;
        }
        return ToolsDownloader.getYtdlpPath();
    }

    public static CompletableFuture<Long> getDurationMs(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (isYouTubeUrl(url)) {
                    return getDurationViaYtDlp(url);
                } else {
                    return getDurationViaFFprobe(url);
                }
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("FFprobe/yt-dlp error for " + url + ": " + e.getMessage());
                }
                return 0L;
            }
        });
    }

    private static boolean isYouTubeUrl(String url) {
        return url != null && (
            url.contains("youtube.com") || 
            url.contains("youtu.be") ||
            url.contains("ytimg.com")
        );
    }

    private static long getDurationViaYtDlp(String url) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            getYtdlpPath(),
            "--no-download",
            "--print", "duration",
            "--no-warnings",
            "--quiet",
            url
        );
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("yt-dlp timeout");
        }
        
        String result = output.toString().trim();
        if (result.isEmpty()) {
            throw new Exception("yt-dlp returned empty duration");
        }
        
        double seconds = Double.parseDouble(result);
        long ms = (long) (seconds * 1000);
        
        if (logger != null) {
            logger.info("yt-dlp duration for " + shortenUrl(url) + ": " + (ms / 1000) + "s");
        }
        
        return ms;
    }

    private static long getDurationViaFFprobe(String url) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            getFfprobePath(),
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            url
        );
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("ffprobe timeout");
        }
        
        String result = output.toString().trim();
        if (result.isEmpty() || result.equals("N/A")) {
            throw new Exception("ffprobe returned no duration");
        }
        
        double seconds = Double.parseDouble(result);
        long ms = (long) (seconds * 1000);
        
        if (logger != null) {
            logger.info("ffprobe duration for " + shortenUrl(url) + ": " + (ms / 1000) + "s");
        }
        
        return ms;
    }

    private static String shortenUrl(String url) {
        if (url == null) return "null";
        if (url.length() <= 50) return url;
        return url.substring(0, 47) + "...";
    }
}
