package org.sawiq.collins.fabric.client.video;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves YouTube URLs to direct video stream URLs using yt-dlp.
 * Supports automatic yt-dlp download and URL caching.
 */
public final class YouTubeResolver {

    private static final boolean DEBUG = true;

    private static void dbg(String msg) {
        if (!DEBUG) return;
        try {
            System.out.println("[CollinsYT] " + msg);
        } catch (Exception ignored) {}
    }

    // YouTube URL patterns
    private static final Pattern YOUTUBE_PATTERN = Pattern.compile(
        "(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/|youtube\\.com/v/|youtube\\.com/shorts/)([a-zA-Z0-9_-]{11})"
    );

    // Cache for resolved URLs (video ID -> ResolvedUrl)
    private static final ConcurrentHashMap<String, ResolvedUrl> URL_CACHE = new ConcurrentHashMap<>();

    // Resolved URL expires after 5 hours (YouTube URLs typically valid for 6 hours)
    private static final long URL_CACHE_TTL_MS = 5L * 60L * 60L * 1000L;

    // yt-dlp download URL (latest release)
    private static final String YTDLP_DOWNLOAD_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";

    // Lock for yt-dlp operations
    private static final Object YTDLP_LOCK = new Object();

    // Status tracking
    private static volatile boolean ytdlpAvailable = false;
    private static volatile boolean ytdlpChecked = false;
    private static volatile boolean ytdlpDownloading = false;
    private static volatile int ytdlpDownloadProgress = 0;

    private record ResolvedUrl(String directUrl, String videoId, long resolvedAtMs, long durationMs) {
        boolean isExpired() {
            return System.currentTimeMillis() - resolvedAtMs > URL_CACHE_TTL_MS;
        }
    }

    /**
     * Result of YouTube URL resolution.
     */
    public record YouTubeResult(
        String directUrl,       // Direct video stream URL (null if failed)
        String videoId,         // YouTube video ID
        long durationMs,        // Video duration in ms (0 if unknown)
        String error,           // Error message (null if success)
        boolean needsDownload   // true if yt-dlp needs to be downloaded
    ) {
        public boolean isSuccess() {
            return directUrl != null && !directUrl.isBlank();
        }
    }

    /**
     * Check if URL is a YouTube URL.
     */
    public static boolean isYouTubeUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("youtube.com") || u.contains("youtu.be");
    }

    /**
     * Extract video ID from YouTube URL.
     */
    public static String extractVideoId(String url) {
        if (url == null) return null;
        Matcher m = YOUTUBE_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Resolve YouTube URL to direct video stream URL.
     * This method is blocking and should be called from a background thread.
     */
    public static YouTubeResult resolve(String url) {
        if (url == null || url.isBlank()) {
            return new YouTubeResult(null, null, 0, "Empty URL", false);
        }

        String videoId = extractVideoId(url);
        if (videoId == null) {
            return new YouTubeResult(null, null, 0, "Not a valid YouTube URL", false);
        }

        dbg("resolve: videoId=" + videoId + " url=" + url);

        // Check cache first
        ResolvedUrl cached = URL_CACHE.get(videoId);
        if (cached != null && !cached.isExpired()) {
            dbg("resolve: using cached URL for " + videoId);
            return new YouTubeResult(cached.directUrl, videoId, cached.durationMs, null, false);
        }

        // Ensure yt-dlp is available
        if (!ensureYtdlpAvailable()) {
            if (ytdlpDownloading) {
                return new YouTubeResult(null, videoId, 0, "yt-dlp downloading: " + ytdlpDownloadProgress + "%", true);
            }
            return new YouTubeResult(null, videoId, 0, "yt-dlp not available", true);
        }

        // Resolve using yt-dlp
        try {
            return resolveWithYtdlp(videoId, url);
        } catch (Exception e) {
            dbg("resolve: error " + e.getMessage());
            return new YouTubeResult(null, videoId, 0, "Resolution failed: " + e.getMessage(), false);
        }
    }

    /**
     * Check if yt-dlp is available (non-blocking check).
     */
    public static boolean isYtdlpAvailable() {
        if (ytdlpChecked) return ytdlpAvailable;
        
        Path ytdlp = getYtdlpPath();
        ytdlpAvailable = Files.isRegularFile(ytdlp) && Files.isExecutable(ytdlp);
        ytdlpChecked = true;
        return ytdlpAvailable;
    }

    /**
     * Check if yt-dlp is currently downloading.
     */
    public static boolean isDownloading() {
        return ytdlpDownloading;
    }

    /**
     * Get yt-dlp download progress (0-100).
     */
    public static int getDownloadProgress() {
        return ytdlpDownloadProgress;
    }

    /**
     * Trigger yt-dlp download in background.
     */
    public static void downloadYtdlpAsync() {
        if (ytdlpDownloading || ytdlpAvailable) return;
        
        Thread t = new Thread(() -> {
            ensureYtdlpAvailable();
        }, "Collins-YtdlpDownload");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Ensure yt-dlp is available, downloading if necessary.
     */
    private static boolean ensureYtdlpAvailable() {
        if (ytdlpAvailable) return true;

        synchronized (YTDLP_LOCK) {
            // Double-check
            Path ytdlp = getYtdlpPath();
            if (Files.isRegularFile(ytdlp)) {
                ytdlpAvailable = true;
                ytdlpChecked = true;
                return true;
            }

            // Download yt-dlp
            dbg("ensureYtdlpAvailable: downloading yt-dlp...");
            ytdlpDownloading = true;
            ytdlpDownloadProgress = 0;

            try {
                Path dir = ytdlp.getParent();
                Files.createDirectories(dir);

                Path tmp = dir.resolve("yt-dlp.exe.tmp");
                Files.deleteIfExists(tmp);

                URL downloadUrl = new URL(YTDLP_DOWNLOAD_URL);
                HttpURLConnection conn = (HttpURLConnection) downloadUrl.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(60_000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                int code = conn.getResponseCode();
                if (code != 200) {
                    dbg("ensureYtdlpAvailable: download failed, code=" + code);
                    conn.disconnect();
                    return false;
                }

                long contentLength = conn.getContentLengthLong();
                dbg("ensureYtdlpAvailable: downloading " + (contentLength / 1024 / 1024) + " MB");

                try (InputStream in = conn.getInputStream();
                     var out = Files.newOutputStream(tmp)) {
                    
                    byte[] buf = new byte[64 * 1024];
                    long written = 0;
                    int r;
                    while ((r = in.read(buf)) >= 0) {
                        out.write(buf, 0, r);
                        written += r;
                        if (contentLength > 0) {
                            ytdlpDownloadProgress = (int) (written * 100 / contentLength);
                        }
                    }
                }

                conn.disconnect();

                // Move to final location
                Files.move(tmp, ytdlp, StandardCopyOption.REPLACE_EXISTING);
                
                dbg("ensureYtdlpAvailable: download complete");
                ytdlpAvailable = true;
                ytdlpChecked = true;
                return true;

            } catch (Exception e) {
                dbg("ensureYtdlpAvailable: download error " + e.getMessage());
                return false;
            } finally {
                ytdlpDownloading = false;
            }
        }
    }

    /**
     * Resolve YouTube video using yt-dlp.
     */
    private static YouTubeResult resolveWithYtdlp(String videoId, String originalUrl) {
        Path ytdlp = getYtdlpPath();
        if (!Files.isRegularFile(ytdlp)) {
            return new YouTubeResult(null, videoId, 0, "yt-dlp not found", true);
        }

        try {
            // Build yt-dlp command
            // -f: format selection - prefer high quality mp4 for streaming
            // -g: get URL only (no download)
            // --no-playlist: single video only
            // --no-warnings: suppress warnings
            // Формат: сначала пробуем 720p/1080p mp4 (хорошее качество для стриминга)
            ProcessBuilder pb = new ProcessBuilder(
                ytdlp.toString(),
                "-f", "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720][ext=mp4]+bestaudio/best[height<=1080][ext=mp4]/best[ext=mp4]/best",
                "-g",
                "--no-playlist",
                "--no-warnings",
                "--no-check-certificates",
                "https://www.youtube.com/watch?v=" + videoId
            );
            
            pb.redirectErrorStream(true);
            
            dbg("resolveWithYtdlp: running yt-dlp for " + videoId);
            long startMs = System.currentTimeMillis();
            
            Process p = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new YouTubeResult(null, videoId, 0, "yt-dlp timeout", false);
            }

            int exitCode = p.exitValue();
            long elapsedMs = System.currentTimeMillis() - startMs;
            dbg("resolveWithYtdlp: exit=" + exitCode + " elapsed=" + elapsedMs + "ms");

            if (exitCode != 0) {
                String error = output.toString().trim();
                if (error.length() > 200) error = error.substring(0, 200) + "...";
                dbg("resolveWithYtdlp: error output: " + error);
                return new YouTubeResult(null, videoId, 0, "yt-dlp error: " + error, false);
            }

            // Parse output - yt-dlp with -g returns URLs (one per line for video+audio or single for combined)
            String[] lines = output.toString().trim().split("\n");
            String directUrl = null;
            
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("http") && line.contains("googlevideo.com")) {
                    // Prefer video URL (first one is usually video in separate streams)
                    if (directUrl == null) {
                        directUrl = line;
                    }
                }
            }

            // If we got separate video+audio, we need to use the combined one
            // For simplicity, let's re-run with a format that gives combined stream
            if (directUrl == null || lines.length > 1) {
                dbg("resolveWithYtdlp: retrying with combined format");
                return resolveWithYtdlpCombined(videoId);
            }

            if (directUrl == null || directUrl.isBlank()) {
                return new YouTubeResult(null, videoId, 0, "No URL in yt-dlp output", false);
            }

            dbg("resolveWithYtdlp: resolved to " + directUrl.substring(0, Math.min(100, directUrl.length())) + "...");

            // Get duration
            long durationMs = getDurationWithYtdlp(videoId);

            // Cache the result
            URL_CACHE.put(videoId, new ResolvedUrl(directUrl, videoId, System.currentTimeMillis(), durationMs));

            return new YouTubeResult(directUrl, videoId, durationMs, null, false);

        } catch (Exception e) {
            dbg("resolveWithYtdlp: exception " + e.getMessage());
            return new YouTubeResult(null, videoId, 0, "Exception: " + e.getMessage(), false);
        }
    }

    /**
     * Resolve with a combined video+audio format (better for FFmpeg).
     */
    private static YouTubeResult resolveWithYtdlpCombined(String videoId) {
        Path ytdlp = getYtdlpPath();

        try {
            // Use format that gives a single URL with both video and audio
            // Приоритет: 720p mp4 > 1080p > best mp4 > best
            ProcessBuilder pb = new ProcessBuilder(
                ytdlp.toString(),
                "-f", "best[height>=720][height<=1080][ext=mp4]/best[height>=480][ext=mp4]/best[ext=mp4]/best",
                "-g",
                "--no-playlist",
                "--no-warnings",
                "--no-check-certificates",
                "https://www.youtube.com/watch?v=" + videoId
            );
            
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new YouTubeResult(null, videoId, 0, "yt-dlp timeout", false);
            }

            if (p.exitValue() != 0) {
                return new YouTubeResult(null, videoId, 0, "yt-dlp error (combined)", false);
            }

            String directUrl = output.toString().trim().split("\n")[0].trim();
            
            if (directUrl.isBlank() || !directUrl.startsWith("http")) {
                return new YouTubeResult(null, videoId, 0, "Invalid URL from yt-dlp", false);
            }

            long durationMs = getDurationWithYtdlp(videoId);
            URL_CACHE.put(videoId, new ResolvedUrl(directUrl, videoId, System.currentTimeMillis(), durationMs));

            dbg("resolveWithYtdlpCombined: resolved to " + directUrl.substring(0, Math.min(100, directUrl.length())) + "...");
            return new YouTubeResult(directUrl, videoId, durationMs, null, false);

        } catch (Exception e) {
            return new YouTubeResult(null, videoId, 0, "Exception: " + e.getMessage(), false);
        }
    }

    /**
     * Get video duration using yt-dlp.
     */
    private static long getDurationWithYtdlp(String videoId) {
        Path ytdlp = getYtdlpPath();
        
        try {
            ProcessBuilder pb = new ProcessBuilder(
                ytdlp.toString(),
                "--get-duration",
                "--no-playlist",
                "--no-warnings",
                "--no-check-certificates",
                "https://www.youtube.com/watch?v=" + videoId
            );
            
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                    break; // Only need first line
                }
            }
            
            p.waitFor(30, TimeUnit.SECONDS);
            
            if (p.exitValue() == 0) {
                return parseDuration(output.toString().trim());
            }
        } catch (Exception ignored) {}
        
        return 0;
    }

    /**
     * Parse duration string (e.g., "1:23:45" or "12:34" or "45") to milliseconds.
     */
    private static long parseDuration(String duration) {
        if (duration == null || duration.isBlank()) return 0;
        
        try {
            String[] parts = duration.split(":");
            long seconds = 0;
            
            if (parts.length == 1) {
                seconds = Long.parseLong(parts[0]);
            } else if (parts.length == 2) {
                seconds = Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
            } else if (parts.length == 3) {
                seconds = Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
            }
            
            return seconds * 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Get path to yt-dlp executable.
     */
    private static Path getYtdlpPath() {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            return gameDir.resolve("collins-tools").resolve("yt-dlp.exe");
        } catch (Exception e) {
            return Path.of("collins-tools", "yt-dlp.exe");
        }
    }

    /**
     * Clear URL cache.
     */
    public static void clearCache() {
        URL_CACHE.clear();
    }

    /**
     * Get yt-dlp version (for diagnostics).
     */
    public static String getYtdlpVersion() {
        if (!ytdlpAvailable) return "not installed";
        
        try {
            Path ytdlp = getYtdlpPath();
            ProcessBuilder pb = new ProcessBuilder(ytdlp.toString(), "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String version = reader.readLine();
                p.waitFor(5, TimeUnit.SECONDS);
                return version != null ? version.trim() : "unknown";
            }
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Update yt-dlp to latest version.
     */
    public static boolean updateYtdlp() {
        if (!ytdlpAvailable) return false;
        
        try {
            Path ytdlp = getYtdlpPath();
            ProcessBuilder pb = new ProcessBuilder(ytdlp.toString(), "-U");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            // Read output to prevent blocking
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (reader.readLine() != null) {}
            }
            
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            dbg("updateYtdlp: error " + e.getMessage());
            return false;
        }
    }
}
