package org.sawiq.collins.fabric.client.video;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.ffmpeg.global.avutil;
import net.fabricmc.loader.api.FabricLoader;

import javax.sound.sampled.LineUnavailableException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

public final class VideoPlayer {

    /**
     * Преобразует путь в формат, который FFmpeg может прочитать.
     * Кэш теперь размещается в папке без кириллицы, поэтому просто возвращаем путь.
     */
    private static String toFFmpegPath(String path) {
        return path;
    }


    private static final boolean DEBUG = false;

    private static void dbg(String msg) {
        if (!DEBUG) return;
        try {
            System.out.println("[CollinsVideo] " + msg);
        } catch (Exception ignored) {
        }
    }

    static {
        try {
            avutil.av_log_set_level(avutil.AV_LOG_QUIET);
        } catch (Throwable ignored) {
        }

        try {
            if (CookieHandler.getDefault() == null) {
                CookieHandler.setDefault(new CookieManager());
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readLimitedUtf8(InputStream in, int maxBytes) throws Exception {
        if (in == null) return null;
        int limit = Math.max(1, maxBytes);
        ByteArrayOutputStream bout = new ByteArrayOutputStream(Math.min(16_384, limit));
        byte[] buf = new byte[8_192];
        int r;
        while (bout.size() < limit) {
            int need = Math.min(buf.length, limit - bout.size());
            r = in.read(buf, 0, need);
            if (r < 0) break;
            if (r == 0) break;
            bout.write(buf, 0, r);
        }
        return new String(bout.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String extractLikelyMediaUrl(String html) {
        if (html == null || html.isBlank()) return null;

        String h = html;
        int i = 0;
        String best = null;
        while (true) {
            int p = h.indexOf("http", i);
            if (p < 0) break;

            int end = p;
            while (end < h.length()) {
                char ch = h.charAt(end);
                if (ch == '"' || ch == '\'' || ch == '<' || Character.isWhitespace(ch)) break;
                end++;
            }

            if (end > p) {
                String cand = h.substring(p, end);
                cand = cand.replace("&amp;", "&");
                while (!cand.isEmpty()) {
                    char last = cand.charAt(cand.length() - 1);
                    if (last == ')' || last == ']' || last == '}' || last == '.' || last == ',' || last == ';') {
                        cand = cand.substring(0, cand.length() - 1);
                        continue;
                    }
                    break;
                }
                if (cand.length() <= 2048) {
                    String lc = cand.toLowerCase(Locale.ROOT);
                    if (lc.contains(".mp4") || lc.contains(".webm") || lc.contains(".mkv") || lc.contains(".mov")) {
                        return cand;
                    }
                    if (lc.contains("dropbox.com") && (lc.contains("dl=1") || lc.contains("raw=1"))) {
                        best = cand;
                    }
                }
            }

            i = Math.max(end, p + 4);
        }

        return best;
    }

    private static boolean isDropboxDownloadUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;
        // dropbox direct download
        if (u.contains("dropboxusercontent.com")) return true;
        // dropbox link forced to download
        if (u.contains("dropbox.com") && (u.contains("dl=1") || u.contains("raw=1"))) return true;
        return false;
    }

    private static String stripFragment(String url) {
        if (url == null) return null;
        int i = url.indexOf('#');
        if (i < 0) return url;
        return url.substring(0, i);
    }

    private static CacheResult ensureCachedToDiskFallback(String cacheKeyUrl, String downloadUrl,
                                                          FrameSink sink, long sessionId, VideoPlayer player) {
        if (cacheKeyUrl == null || cacheKeyUrl.isBlank()) return null;
        if (downloadUrl == null || downloadUrl.isBlank()) return null;

        String key = cacheKeyUrl.trim();
        String u0 = stripFragment(downloadUrl.trim());
        if (!(u0.startsWith("http://") || u0.startsWith("https://"))) return null;

        String hash = sha256Hex(key);
        Object lock = DISK_CACHE_LOCKS.computeIfAbsent(hash, k -> new Object());

        synchronized (lock) {
            try {
                dbg("cacheFallback: start keyHash=" + hash + " url=" + u0);
                Path dir = getCacheDir();
                Files.createDirectories(dir);

                Path partFile = dir.resolve(hash + ".part");
                // Если загрузка в процессе (есть .part файл), ждём завершения
                int waitAttempts = 0;
                while (Files.exists(partFile) && waitAttempts < 300) { // макс 5 минут
                    // Проверяем что сессия ещё активна
                    if (player != null && player.sessionId != sessionId) {
                        dbg("cacheFallback: session changed while waiting, aborting");
                        return null;
                    }
                    dbg("cacheFallback: waiting for download in progress keyHash=" + hash);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    waitAttempts++;
                }

                Path existing = findExistingCacheFile(dir, hash);
                if (existing != null && Files.isRegularFile(existing)) {
                    try {
                        long sz = Files.size(existing);
                        if (sz > 0 && sz <= DISK_CACHE_MAX_BYTES) {
                            // Проверяем валидность кэша через FFmpeg
                            if (isValidMediaFile(existing)) {
                                try {
                                    Files.setLastModifiedTime(existing, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                                } catch (Exception ignored) {
                                }
                                enforceDiskCacheLimit(dir, DISK_CACHE_MAX_BYTES);
                                DISK_CACHE_LAST_FAIL_MS.remove(hash);
                                dbg("cacheFallback: using valid existing file keyHash=" + hash);
                                // Уведомляем о использовании существующего кэша
                                if (sink != null) {
                                    try {
                                        sink.onCachedFileUsed(existing.toString(), sz);
                                    } catch (Exception ignored) {}
                                }
                                return new CacheResult(existing, null);
                            } else {
                                dbg("cacheFallback: existing file invalid, re-downloading keyHash=" + hash);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        Files.deleteIfExists(existing);
                    } catch (Exception ignored) {
                    }
                }

                Long lastFail = DISK_CACHE_LAST_FAIL_MS.get(hash);
                if (lastFail != null && (System.currentTimeMillis() - lastFail) < DISK_CACHE_FAIL_COOLDOWN_MS) {
                    dbg("cacheFallback: cooldown active keyHash=" + hash);
                    return null;
                }

                enforceDiskCacheLimit(dir, DISK_CACHE_MAX_BYTES);

                Path tmp = dir.resolve(hash + ".part");
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }


                String cur = u0;
                String ref = null;
                HttpURLConnection c = null;
                String ct = null;

                for (int i = 0; i < 8; i++) {
                    URL base = new URL(cur);
                    c = (HttpURLConnection) base.openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(15_000);
                    c.setReadTimeout(60_000); // Увеличен для больших файлов
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                    c.setRequestProperty("Accept", "*/*");
                    c.setRequestProperty("Accept-Encoding", "identity");
                    c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                    c.setRequestProperty("Connection", "keep-alive");
                    if (ref != null && !ref.isBlank()) {
                        c.setRequestProperty("Referer", ref);
                    }

                    int code = c.getResponseCode();
                    dbg("cacheFallback: GET " + cur + " -> " + code);
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (loc == null || loc.isBlank()) {
                            dbg("cacheFallback: redirect without Location");
                            DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                            return null;
                        }
                        URL next = new URL(base, loc);
                        ref = cur;
                        cur = next.toString();
                        continue;
                    }

                    if (code < 200 || code >= 400) {
                        dbg("cacheFallback: non-2xx code=" + code + " url=" + cur);
                        c.disconnect();
                        DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                        return null;
                    }

                    try {
                        ct = c.getHeaderField("Content-Type");
                    } catch (Exception ignored) {
                        ct = null;
                    }

                    try {
                        String host = "";
                        try {
                            host = base.getHost();
                        } catch (Exception ignored) {
                        }
                        String hostLower = (host == null) ? "" : host.toLowerCase(Locale.ROOT);
                        boolean isSurl = hostLower.equals("surl.lu") || hostLower.endsWith(".surl.lu")
                                || hostLower.equals("surl.li") || hostLower.endsWith(".surl.li");
                        boolean ctHtml = (ct != null && ct.toLowerCase(Locale.ROOT).startsWith("text/html"));

                        if (isSurl || ctHtml) {
                            String html = null;
                            try (InputStream in = c.getInputStream()) {
                                html = readLimitedUtf8(in, 256 * 1024);
                            } catch (Exception ignored) {
                            }
                            String extracted = extractLikelyMediaUrl(html);
                            c.disconnect();
                            if (extracted != null && !extracted.isBlank() && !extracted.equalsIgnoreCase(cur)) {
                                dbg("cacheFallback: extracted media url=" + extracted);
                                ref = cur;
                                cur = extracted;
                                c = null;
                                ct = null;
                                continue;
                            }
                            dbg("cacheFallback: html page without media url");
                            DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                            return null;
                        }
                    } catch (Exception ignored) {
                    }
                    break;
                }

                if (c == null) {
                    dbg("cacheFallback: failed to open connection");
                    DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                    return null;
                }

                long declaredLen = -1L;
                try {
                    declaredLen = c.getContentLengthLong();
                } catch (Exception ignored) {
                }
                dbg("cacheFallback: contentType=" + ct + " contentLength=" + declaredLen + " finalUrl=" + cur);
                if (declaredLen > DISK_CACHE_MAX_BYTES) {
                    c.disconnect();
                    DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                    return null;
                }

                String ext = guessCacheExtension(cur, ct);
                Path dst = dir.resolve(hash + ext);

                long written = 0L;
                long lastProgressLog = 0L;
                try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                    boolean ctHtml = false;
                    try {
                        if (ct != null) {
                            String ctl = ct.toLowerCase(Locale.ROOT);
                            ctHtml = ctl.startsWith("text/html");
                        }
                    } catch (Exception ignored) {
                    }

                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) >= 0) {
                        if (written == 0L) {
                            int n = Math.min(r, 512);
                            String head;
                            try {
                                head = new String(buf, 0, n, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                            } catch (Exception e) {
                                head = "";
                            }
                            if (ctHtml || head.contains("<html") || head.contains("<!doctype") || head.contains("<head") || head.contains("<body")) {
                                if (head.contains("<html") || head.contains("<!doctype") || head.contains("<head") || head.contains("<body")) {
                                    try {
                                        Files.deleteIfExists(tmp);
                                    } catch (Exception ignored) {
                                    }
                                    DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                                    return null;
                                }
                            }
                        }

                        out.write(buf, 0, r);
                        written += r;

                        // Логируем прогресс каждые 10 МБ
                        long progressMb = written / (10L * 1024L * 1024L);
                        if (progressMb > lastProgressLog) {
                            lastProgressLog = progressMb;
                            long writtenMb = written / (1024L * 1024L);
                            long totalMb = declaredLen > 0 ? declaredLen / (1024L * 1024L) : -1;
                            int pct = declaredLen > 0 ? (int) (written * 100L / declaredLen) : -1;

                            if (declaredLen > 0) {
                                dbg("cacheFallback: downloading... " + writtenMb + " MB / " + totalMb + " MB (" + pct + "%)");
                            } else {
                                dbg("cacheFallback: downloading... " + writtenMb + " MB");
                            }

                            // Отправляем прогресс в sink
                            if (sink != null) {
                                sink.onDownloadProgress(Math.max(0, pct), writtenMb, Math.max(0, totalMb));
                            }

                            // Проверяем что сессия ещё активна
                            if (player != null && player.sessionId != sessionId) {
                                dbg("cacheFallback: session changed during download, aborting");
                                try {
                                    Files.deleteIfExists(tmp);
                                } catch (Exception ignored) {
                                }
                                return null;
                            }
                        }

                        if (written > DISK_CACHE_MAX_BYTES) {
                            try {
                                Files.deleteIfExists(tmp);
                            } catch (Exception ignored) {
                            }
                            DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                            return null;
                        }
                    }
                } finally {
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                }

                dbg("cacheFallback: downloaded bytes=" + written + " -> " + dst);

                try {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    try {
                        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ignored) {
                        try {
                            Files.deleteIfExists(tmp);
                        } catch (Exception ignored2) {
                        }
                        DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                        return null;
                    }
                }

                try {
                    long sz = Files.size(dst);
                    if (sz <= 0 || sz > DISK_CACHE_MAX_BYTES) {
                        try {
                            Files.deleteIfExists(dst);
                        } catch (Exception ignored) {
                        }
                        DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                        return null;
                    }
                } catch (Exception ignored) {
                }

                enforceDiskCacheLimit(dir, DISK_CACHE_MAX_BYTES);
                DISK_CACHE_LAST_FAIL_MS.remove(hash);
                return new CacheResult(dst, ct);
            } catch (Exception e) {
                dbg("cacheFallback: exception " + e);
                DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                return null;
            }
        }
    }

    public interface FrameSink {
        void initVideo(int videoW, int videoH, int targetW, int targetH, double fps);

        void onFrame(int[] argb, int w, int h, long timestampUs);

        void onStop();

        default void onPlaybackClockStart(long wallStartNs) {
        }

        default void onDuration(long durationMs) {
        }

        default void onEnded(long durationMs) {
        }

        /** Вызывается когда начинается скачивание тяжёлого видео */
        default void onDownloadStart(String message) {
        }

        /** Вызывается с прогрессом скачивания (0-100) */
        default void onDownloadProgress(int percent, long downloadedMb, long totalMb) {
        }

        /** Вызывается когда скачивание завершено */
        default void onDownloadComplete() {
        }

        /** Вызывается когда видео было скачано в кэш (для предложения удаления) */
        default void onCachedFileUsed(String cachedFilePath, long fileSizeBytes) {
        }

        /** true если буфер ещё не полон (декодер может продолжать) */
        default boolean canAcceptFrame() {
            return true;
        }

        /** Получить свободный буфер из пула (или null если пул пуст) */
        default int[] borrowBuffer() {
            return null;
        }

        /** Вернуть буфер в пул после использования */
        default void returnBuffer(int[] buf) {
        }

        /** true когда буфер видео готов (можно начинать аудио) */
        default boolean isBufferReady() {
            return true;
        }
    }

    private final FrameSink sink;

    private volatile boolean running;
    private volatile long sessionId; // Уникальный ID сессии для защиты от дублирования
    private Thread thread;

    private volatile long startPosMs = 0;
    private volatile float gain = 1.0f;
    private volatile VideoAudioPlayer currentAudio;
    private volatile long startRequestEpochMs = 0;

    private record CachedMeta(String resolvedUrl, boolean forceMp4Demuxer, int videoW, int videoH, double fps, long durationMs, long cachedAtMs) {
    }

    private static final ConcurrentHashMap<String, CachedMeta> META_CACHE = new ConcurrentHashMap<>();
    private static final long META_TTL_MS = 15L * 60L * 1000L;

    private static final long DISK_CACHE_MAX_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final long DISK_CACHE_FAIL_COOLDOWN_MS = 10_000L;

    public boolean isRunning() {
        return running && thread != null && thread.isAlive();
    }
    private static final ConcurrentHashMap<String, Object> DISK_CACHE_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> DISK_CACHE_LAST_FAIL_MS = new ConcurrentHashMap<>();

    private record CacheResult(Path path, String contentType) {
    }

    /**
     * Проверяет, является ли файл валидным медиафайлом, который может быть открыт FFmpeg.
     * Для MP4 проверяем наличие moov атома (без него файл не воспроизводится).
     */
    private static boolean isValidMediaFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) return false;
        long fileSize;
        try {
            fileSize = Files.size(file);
            if (fileSize <= 0) return false;
            // Минимальный размер для валидного mp4 — хотя бы 8KB (ftyp + moov минимум)
            if (fileSize < 8 * 1024) {
                dbg("isValidMediaFile: file=" + file + " too small: " + fileSize + " bytes");
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        // Проверяем структуру MP4: должен быть ftyp и moov атом
        try (var raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            // Проверяем ftyp
            byte[] header = new byte[8];
            raf.readFully(header);
            boolean hasFtyp = header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
            if (!hasFtyp) {
                dbg("isValidMediaFile: file=" + file + " no ftyp magic");
                return false;
            }

            // Ищем moov атом (может быть в начале или конце файла)
            raf.seek(0);
            boolean hasMoov = false;
            long pos = 0;
            byte[] atomHeader = new byte[8];

            // Сканируем атомы файла (максимум 100 итераций для защиты от зацикливания)
            for (int i = 0; i < 100 && pos < fileSize - 8; i++) {
                raf.seek(pos);
                int bytesRead = raf.read(atomHeader);
                if (bytesRead < 8) break;

                // Размер атома (big-endian 32-bit)
                long atomSize = ((atomHeader[0] & 0xFFL) << 24) |
                               ((atomHeader[1] & 0xFFL) << 16) |
                               ((atomHeader[2] & 0xFFL) << 8) |
                               (atomHeader[3] & 0xFFL);

                // Тип атома
                String atomType = new String(atomHeader, 4, 4, StandardCharsets.US_ASCII);

                // Проверяем на moov
                if ("moov".equals(atomType)) {
                    hasMoov = true;
                    dbg("isValidMediaFile: file=" + file + " found moov at pos=" + pos + " size=" + atomSize);
                    break;
                }

                // Размер 0 означает "до конца файла", размер 1 означает 64-bit размер
                if (atomSize == 0) {
                    break;
                } else if (atomSize == 1) {
                    // 64-bit extended size
                    byte[] extSize = new byte[8];
                    raf.read(extSize);
                    atomSize = ((extSize[0] & 0xFFL) << 56) |
                               ((extSize[1] & 0xFFL) << 48) |
                               ((extSize[2] & 0xFFL) << 40) |
                               ((extSize[3] & 0xFFL) << 32) |
                               ((extSize[4] & 0xFFL) << 24) |
                               ((extSize[5] & 0xFFL) << 16) |
                               ((extSize[6] & 0xFFL) << 8) |
                               (extSize[7] & 0xFFL);
                }

                if (atomSize < 8) {
                    dbg("isValidMediaFile: file=" + file + " invalid atom size=" + atomSize + " at pos=" + pos);
                    break;
                }

                pos += atomSize;
            }

            if (!hasMoov) {
                dbg("isValidMediaFile: file=" + file + " no moov atom found (file may be incomplete)");
                return false;
            }

            dbg("isValidMediaFile: file=" + file + " valid MP4 with moov atom");
            return true;

        } catch (Exception e) {
            dbg("isValidMediaFile: file=" + file + " error: " + e.getMessage());
            return false;
        }
    }


    public VideoPlayer(FrameSink sink) {
        this.sink = sink;
    }

    public void start(String url, int blocksW, int blocksH, boolean loop) {
        start(url, blocksW, blocksH, loop, 0L, 1.0f);
    }

    public void start(String url, int blocksW, int blocksH, boolean loop, long startPosMs, float gain) {
        stop(); // Остановка старого потока

        this.startPosMs = Math.max(0L, startPosMs);
        this.gain = Math.max(0f, gain);
        this.startRequestEpochMs = System.currentTimeMillis();

        // Уникальный ID сессии для защиты от дублирования
        final long mySessionId = System.nanoTime();
        this.sessionId = mySessionId;

        final String urlFinal = url;

        running = true;
        thread = new Thread(() -> runLoop(urlFinal, blocksW, blocksH, loop, mySessionId), "Collins-VideoPlayer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY); // высокий приоритет для уменьшения GC пауз
        thread.start();
    }

    public void setGain(float gain) {
        float g = Math.max(0f, gain);
        this.gain = g;

        VideoAudioPlayer a = currentAudio;
        if (a != null) a.setGain(g);
    }

    public void stop() {
        running = false;
        sessionId = 0; // Сброс сессии

        Thread t = thread;
        if (t != null) {
            t.interrupt();
            // Ждём завершения потока (максимум 500мс)
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;

        VideoAudioPlayer a = currentAudio;
        if (a != null) a.shutdownNow();
        currentAudio = null;
    }

    private void runLoop(String url, int blocksW, int blocksH, boolean loop, long mySessionId) {
        // Проверяем что это наша сессия
        if (sessionId != mySessionId) {
            dbg("runLoop: session mismatch, exiting");
            return;
        }

        try {
            boolean first = true;
            int failStreak = 0;
            while (running && sessionId == mySessionId) {
                long seekMs = first ? startPosMs : 0L;
                long requestEpochMs = first ? startRequestEpochMs : 0L;
                first = false;

                boolean ok = playOnce(url, blocksW, blocksH, seekMs, requestEpochMs, mySessionId);
                if (!ok) {
                    failStreak++;
                    if (!loop) break;

                    long backoffMs;
                    if (failStreak <= 1) backoffMs = 500L;
                    else if (failStreak == 2) backoffMs = 1000L;
                    else if (failStreak == 3) backoffMs = 2000L;
                    else if (failStreak == 4) backoffMs = 4000L;
                    else if (failStreak == 5) backoffMs = 8000L;
                    else backoffMs = 10000L;

                    dbg("runLoop: playOnce failed; backoffMs=" + backoffMs + " failStreak=" + failStreak);
                    LockSupport.parkNanos(backoffMs * 1_000_000L);
                    continue;
                }

                // playOnce вернул true — видео успешно закончилось
                // Не перезапускаем — VideoScreen решит нужно ли перезапускать
                dbg("runLoop: playOnce completed successfully, exiting loop");
                break;
            }
        } finally {
            // Очищаем только если это наша сессия
            if (sessionId == mySessionId) {
                currentAudio = null;
                sink.onStop();
            }
        }
    }

    private boolean playOnce(String url, int blocksW, int blocksH, long seekMs, long requestEpochMs, long mySessionId) {
        // Проверка сессии в начале
        if (sessionId != mySessionId || !running) {
            dbg("playOnce: session mismatch or stopped, aborting");
            return false;
        }

        String originalUrl = stripFragment(url);
        url = originalUrl;

        dbg("playOnce: originalUrl=" + originalUrl + " blocks=" + blocksW + "x" + blocksH + " seekMs=" + seekMs);

        // YouTube URL resolution
        if (YouTubeResolver.isYouTubeUrl(originalUrl)) {
            dbg("playOnce: detected YouTube URL, resolving...");
            sink.onDownloadStart("collins.video.youtube_resolving");
            
            YouTubeResolver.YouTubeResult ytResult = YouTubeResolver.resolve(originalUrl);
            
            if (sessionId != mySessionId || !running) {
                dbg("playOnce: session changed during YouTube resolution, aborting");
                return false;
            }
            
            if (ytResult.needsDownload() && !YouTubeResolver.isYtdlpAvailable()) {
                dbg("playOnce: yt-dlp not available, starting download...");
                sink.onDownloadProgress(0, 0, 0);
                YouTubeResolver.downloadYtdlpAsync();
                
                // Wait for yt-dlp download with progress updates
                int maxWait = 120; // 2 minutes max
                int waited = 0;
                while (YouTubeResolver.isDownloading() && waited < maxWait) {
                    if (sessionId != mySessionId || !running) return false;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    waited++;
                    int progress = YouTubeResolver.getDownloadProgress();
                    sink.onDownloadProgress(progress, 0, 0);
                    dbg("playOnce: waiting for yt-dlp download... " + progress + "%");
                }
                
                // Retry resolution after download
                ytResult = YouTubeResolver.resolve(originalUrl);
            }
            
            if (!ytResult.isSuccess()) {
                dbg("playOnce: YouTube resolution failed: " + ytResult.error());
                return false;
            }
            
            url = ytResult.directUrl();
            dbg("playOnce: YouTube resolved to: " + url.substring(0, Math.min(100, url.length())) + "...");
            sink.onDownloadComplete();
            
            // Отправляем duration от yt-dlp на сервер (FFmpeg часто не получает duration из YouTube стримов)
            if (ytResult.durationMs() > 0) {
                dbg("playOnce: YouTube duration from yt-dlp: " + (ytResult.durationMs() / 1000) + "s");
                sink.onDuration(ytResult.durationMs());
            }
            
            // YouTube streams are usually well-supported by FFmpeg directly
            // No need for disk caching - stream directly
        }

        boolean forceMp4Demuxer = false;

        int videoW;
        int videoH;
        double fps;
        long durationMs;

        CachedMeta cached = META_CACHE.get(originalUrl);
        if (cached != null && (System.currentTimeMillis() - cached.cachedAtMs()) <= META_TTL_MS) {
            String cachedResolved = cached.resolvedUrl();
            if (cachedResolved != null && !cachedResolved.isBlank()) {
                if (cachedResolved.startsWith("http://") || cachedResolved.startsWith("https://")) {
                    url = cachedResolved;
                } else {
                    try {
                        Path p = Path.of(cachedResolved);
                        if (Files.isRegularFile(p)) {
                            url = toFFmpegPath(cachedResolved);
                            // Уведомляем о использовании кэшированного файла
                            try {
                                long fileSize = Files.size(p);
                                sink.onCachedFileUsed(cachedResolved, fileSize);
                                dbg("playOnce: using meta-cached local file, notified sink: " + cachedResolved);
                            } catch (Exception ignored) {}
                        } else {
                            cached = null;
                        }
                    } catch (Exception e) {
                        cached = null;
                    }
                }
            }
        }

        if (cached != null) {
            forceMp4Demuxer = cached.forceMp4Demuxer();
            videoW = cached.videoW();
            videoH = cached.videoH();
            fps = cached.fps();
            durationMs = cached.durationMs();
        } else {
            String resolved = tryResolveUrl(url);
            if (resolved != null) {
                dbg("playOnce: resolved url=" + resolved);
                url = resolved;
            }

            ProbeResult pr = probeUrl(url);
            if (pr == null) {
                dbg("playOnce: probeUrl failed for " + url);
                sink.onDownloadStart("collins.video.downloading");
                CacheResult cr = ensureCachedToDiskFallback(originalUrl, url, sink, mySessionId, this);
                if (sessionId != mySessionId || !running) {
                    dbg("playOnce: session changed during fallback, aborting");
                    return false;
                }
                if (cr != null && cr.path() != null) {
                    sink.onDownloadComplete();
                    url = toFFmpegPath(cr.path().toString());
                    dbg("playOnce: fallback cached path=" + url + " ct=" + cr.contentType());
                    try {
                        if (cr.contentType() != null && cr.contentType().toLowerCase(Locale.ROOT).contains("video/mp4")) {
                            forceMp4Demuxer = true;
                        }
                    } catch (Exception ignored) {
                    }
                } else {
                    return false;
                }
            } else {
                if (pr.finalUrl != null && !pr.finalUrl.isBlank()) url = pr.finalUrl;
                dbg("playOnce: probe ok finalUrl=" + pr.finalUrl + " code=" + pr.httpCode + " ct=" + pr.contentType + " range=" + pr.supportsRange + " cd=" + pr.contentDisposition);

                if (pr.httpCode >= 400) {
                    // Явная HTTP ошибка (например 410 Gone для протухших/одноразовых Dropbox ссылок).
                    // Не пытаемся открывать FFmpeg по заведомо мёртвому URL.
                    dbg("playOnce: abort due to http error code=" + pr.httpCode + " url=" + url);
                    try {
                        String hash = sha256Hex(originalUrl.trim());
                        DISK_CACHE_LAST_FAIL_MS.remove(hash);
                    } catch (Exception ignored) {
                    }
                    sink.onDownloadStart("collins.video.downloading");
                    CacheResult cr = ensureCachedToDiskFallback(originalUrl, url, sink, mySessionId, this);
                    if (sessionId != mySessionId || !running) {
                        dbg("playOnce: session changed during http error fallback, aborting");
                        return false;
                    }
                    if (cr != null && cr.path() != null) {
                        sink.onDownloadComplete();
                        url = toFFmpegPath(cr.path().toString());
                        dbg("playOnce: fallback cached path=" + url + " ct=" + cr.contentType());
                    } else {
                        return false;
                    }
                }

                boolean forceCache = false;
                String ctLower = null;
                if (pr.contentType != null) {
                    ctLower = pr.contentType.toLowerCase(Locale.ROOT);
                    // Некоторые хостинги/редиректы отдают text/html на probe,
                    // но по факту по GET возвращают медиа (или файл по редиректу).
                    // Дадим шанс disk-cache fallback (с валидацией контента в ensureCachedToDisk).
                    if (ctLower.contains("video/mp4")) {
                        forceMp4Demuxer = true;
                    }
                }

                if (pr.isHttp) {
                    if (isDropboxDownloadUrl(url)) {
                        // Dropbox direct-download ссылки часто плохо перематываются по сети (нестабильный seek/range).
                        // Кэшируем на диск для стабильной перемотки.
                        forceCache = true;
                    }
                    if (!pr.supportsRange) {
                        forceCache = true;
                    }
                    if (ctLower != null && ctLower.startsWith("text/html")) {
                        forceCache = true;
                    } else if (ctLower != null && !(ctLower.startsWith("video/") || ctLower.contains("video/mp4"))) {
                        if (!ctLower.startsWith("text/")) {
                            forceCache = true;
                        }
                    }
                    if (pr.contentDisposition != null && !pr.contentDisposition.isBlank()) {
                        forceCache = true;
                    }
                }

                dbg("playOnce: forceCache=" + forceCache + " forceMp4Demuxer=" + forceMp4Demuxer + " url=" + url);

                if (forceCache) {
                    // Уведомляем о начале скачивания тяжёлого видео
                    sink.onDownloadStart("collins.video.downloading");

                    Path cachedFile = ensureCachedToDisk(originalUrl, url, pr, sink, mySessionId, this);

                    // Проверяем что сессия ещё активна
                    if (sessionId != mySessionId || !running) {
                        dbg("playOnce: session changed during download, aborting");
                        return false;
                    }

                    if (cachedFile != null) {
                        sink.onDownloadComplete();
                        url = toFFmpegPath(cachedFile.toString());
                        dbg("playOnce: cached path=" + url);
                        // Уведомляем о использовании кэшированного файла
                        try {
                            long fileSize = Files.size(cachedFile);
                            sink.onCachedFileUsed(cachedFile.toString(), fileSize);
                        } catch (Exception ignored) {}
                        if (ctLower != null && ctLower.contains("video/mp4")) {
                            forceMp4Demuxer = true;
                        }
                    } else {
                        dbg("playOnce: ensureCachedToDisk returned null, trying fallback");
                        try {
                            String hash = sha256Hex(originalUrl.trim());
                            DISK_CACHE_LAST_FAIL_MS.remove(hash);
                        } catch (Exception ignored) {
                        }
                        CacheResult cr = ensureCachedToDiskFallback(originalUrl, url, sink, mySessionId, this);

                        // Проверяем что сессия ещё активна
                        if (sessionId != mySessionId || !running) {
                            dbg("playOnce: session changed during fallback download, aborting");
                            return false;
                        }

                        if (cr != null && cr.path() != null) {
                            sink.onDownloadComplete();
                            url = toFFmpegPath(cr.path().toString());
                            dbg("playOnce: fallback cached path=" + url + " ct=" + cr.contentType());
                            // Уведомляем о использовании кэшированного файла
                            try {
                                long fileSize = Files.size(cr.path());
                                sink.onCachedFileUsed(cr.path().toString(), fileSize);
                            } catch (Exception ignored) {}
                            try {
                                if (cr.contentType() != null && cr.contentType().toLowerCase(Locale.ROOT).contains("video/mp4")) {
                                    forceMp4Demuxer = true;
                                }
                            } catch (Exception ignored) {
                            }
                        } else {
                            return false;
                        }
                    }
                }
            }

            // Диагностика локального файла перед FFmpeg
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                try {
                    Path p = Path.of(url);
                    boolean exists = Files.exists(p);
                    long size = exists ? Files.size(p) : -1;
                    boolean readable = Files.isReadable(p);
                    dbg("playOnce: local file check path=" + url + " exists=" + exists + " size=" + size + " readable=" + readable);

                    if (exists && size > 0) {
                        // Читаем первые байты для проверки
                        try (var fis = Files.newInputStream(p)) {
                            byte[] header = new byte[8];
                            int read = fis.read(header);
                            String magic = new String(header, 4, 4, StandardCharsets.US_ASCII);
                            dbg("playOnce: file header read=" + read + " magic=" + magic);
                        }
                    }
                } catch (Exception diagErr) {
                    dbg("playOnce: file diagnostics failed: " + diagErr.getMessage());
                }
            }

            try (FFmpegFrameGrabber meta = new FFmpegFrameGrabber(url)) {
                if (forceMp4Demuxer) {
                    try {
                        meta.setFormat("mp4");
                    } catch (Exception ignored) {
                    }
                }
                applyNetOptions(meta, url);
                meta.start();
                videoW = meta.getImageWidth();
                videoH = meta.getImageHeight();
                fps = meta.getVideoFrameRate();
                long lenUs = meta.getLengthInTime();
                durationMs = lenUs > 0 ? (lenUs / 1000L) : 0L;
                meta.stop();
            } catch (Exception e) {
                dbg("playOnce: FFmpeg meta failed url=" + url + " err=" + e);
                // Если это локальный файл из кэша — удаляем его, он повреждён
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try {
                        Path badFile = Path.of(url);
                        if (Files.exists(badFile)) {
                            dbg("playOnce: deleting corrupted cache file: " + url);
                            Files.deleteIfExists(badFile);
                            // Удаляем из DISK_CACHE_LAST_FAIL_MS чтобы можно было перезагрузить
                            String hash = sha256Hex(originalUrl.trim());
                            DISK_CACHE_LAST_FAIL_MS.remove(hash);
                        }
                    } catch (Exception deleteErr) {
                        dbg("playOnce: failed to delete corrupted cache: " + deleteErr.getMessage());
                    }
                }
                return false;
            }

            long max = 12L * 60L * 60L * 1000L;
            if (durationMs < 0 || durationMs > max) durationMs = 0L;

            if (fps <= 0) fps = 30.0;
            META_CACHE.put(originalUrl, new CachedMeta(url, forceMp4Demuxer, videoW, videoH, fps, durationMs, System.currentTimeMillis()));
        }

        if (videoW <= 0 || videoH <= 0) {
            return false;
        }

        // 2) target размер
        VideoSizeUtil.Size target = VideoSizeUtil.pick(blocksW, blocksH, videoW, videoH);

        // 3) декод
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url)) {
            if (forceMp4Demuxer) {
                try {
                    grabber.setFormat("mp4");
                } catch (Exception ignored) {
                }
            }
            applyNetOptions(grabber, url);
            grabber.setImageWidth(target.w());
            grabber.setImageHeight(target.h());
            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
            grabber.start();
            dbg("playOnce: FFmpeg started url=" + url + " target=" + target.w() + "x" + target.h() + " forceMp4=" + forceMp4Demuxer);

            long openLagMs = (requestEpochMs > 0) ? Math.max(0L, System.currentTimeMillis() - requestEpochMs) : 0L;
            long effectiveSeekMs = seekMs + openLagMs;

            if (effectiveSeekMs > 0) {
                long seekTargetUs = effectiveSeekMs * 1000L;
                try {
                    grabber.setTimestamp(seekTargetUs);
                } catch (Exception e) {
                }

                try {
                    long nowUs = grabber.getTimestamp();
                    if (nowUs >= 0 && nowUs + 50_000L < seekTargetUs) {
                        int skipped = 0;

                        long needUs = Math.max(0L, seekTargetUs - nowUs);
                        long needMs = needUs / 1000L;

                        int maxSkipped = (int) Math.min(20_000L, Math.max(600L, (long) (fps * (needMs / 1000.0) + 120)));

                        long startSkipNs = System.nanoTime();
                        long maxSkipNs = 2_000_000_000L;

                        while (running && skipped < maxSkipped) {
                            if (System.nanoTime() - startSkipNs > maxSkipNs) break;
                            Frame f = grabber.grab();
                            if (f == null) break;

                            long ts = f.timestamp;
                            if (ts <= 0) ts = grabber.getTimestamp();

                            if (ts >= seekTargetUs - 50_000L) {
                                // дальше пойдет обычный decode loop
                                break;
                            }

                            skipped++;
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            int sampleRate = grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 48000;
            int channels = grabber.getAudioChannels() > 0 ? grabber.getAudioChannels() : 2;
            channels = Math.min(2, channels);
            if (fps <= 0) {
                fps = grabber.getVideoFrameRate();
                if (fps <= 0) fps = 30.0;
            }

            // инициализируем видео
            sink.initVideo(videoW, videoH, target.w(), target.h(), fps);
            sink.onDuration(durationMs);

            // кэш для BGR24 данных (не буфер кадров - те теперь в VideoScreen)
            final int pixels = target.w() * target.h();
            final byte[] tmpBytes = new byte[pixels * 3];

            boolean hasAnyAudio = false;
            long wallStartNs = 0;
            boolean wallStarted = false;

            try (VideoAudioPlayer audio = new VideoAudioPlayer(sampleRate, channels)) {
                currentAudio = audio;
                audio.setGain(gain);

                long baseStreamTsUs = Long.MIN_VALUE;
                long videoFrameIndex = 0;

                long lastDecodeLogNs = 0;
                long DECODE_LOG_INTERVAL_NS = 2_000_000_000L;
                long maxGrabUs = 0;
                long maxConvertUs = 0;

                boolean ended = false;

                while (running) {
                    long grabStart = System.nanoTime();
                    Frame frame = grabber.grab();
                    long grabEnd = System.nanoTime();
                    if (frame == null) {
                        ended = true;
                        break;
                    }

                    long tsUsForPace = frame.timestamp;
                    if (tsUsForPace <= 0) tsUsForPace = grabber.getTimestamp();

                    if (tsUsForPace > 0 && baseStreamTsUs == Long.MIN_VALUE) {
                        baseStreamTsUs = tsUsForPace;
                    }

                    if (frame.samples != null) {
                        hasAnyAudio = true;

                        if (!sink.isBufferReady()) {
                            audio.prebufferSamples(frame.samples, channels);
                            continue;
                        }

                        if (!wallStarted) {
                            wallStarted = true;
                            wallStartNs = System.nanoTime();
                            sink.onPlaybackClockStart(wallStartNs);
                        }

                        if (!audio.isStarted()) audio.startPlayback();
                        if (audio.hasPrebuffer()) audio.flushPrebuffer();
                        audio.writeSamples(frame.samples, channels);
                        continue;
                    }

                    if (frame.image == null || frame.image.length == 0) continue;

                    videoFrameIndex++;

                    if (!hasAnyAudio) {
                        // без аудио: декодер бежит пока буфер не полон
                        // пейсинг делается на render thread
                        while (running && !sink.canAcceptFrame()) {
                            // буфер полон - ждём пока render thread освободит место
                            LockSupport.parkNanos(1_000_000L); // 1ms
                            if (Thread.interrupted()) return false;
                        }
                    }

                    long convertStart = System.nanoTime(); // ПОСЛЕ пейсинга

                    // получаем буфер из пула (управляется VideoScreen)
                    int[] out = sink.borrowBuffer();
                    if (out == null) {
                        // пул пуст - ждём
                        LockSupport.parkNanos(1_000_000L);
                        continue;
                    }

                    int w = target.w();
                    int h = target.h();

                    // прямое чтение из ByteBuffer (BGR24 формат)
                    ByteBuffer bb = (ByteBuffer) frame.image[0];
                    if (bb == null) continue;

                    int strideBytes = frame.imageStride;
                    int rowBytes = w * 3;

                    // читаем напрямую через bulk get в кэшированный byte[]
                    if (strideBytes <= 0 || strideBytes == rowBytes) {
                        bb.position(0);
                        bb.get(tmpBytes, 0, Math.min(tmpBytes.length, bb.remaining()));
                    } else {
                        // с учётом stride
                        for (int y = 0; y < h; y++) {
                            bb.position(y * strideBytes);
                            bb.get(tmpBytes, y * rowBytes, Math.min(rowBytes, bb.remaining()));
                        }
                    }

                    // BGR24 -> ABGR (0xAABBGGRR)
                    for (int i = 0, j = 0; i < pixels; i++, j += 3) {
                        int b = tmpBytes[j] & 0xFF;
                        int g = tmpBytes[j + 1] & 0xFF;
                        int r = tmpBytes[j + 2] & 0xFF;
                        out[i] = 0xFF000000 | (b << 16) | (g << 8) | r;
                    }

                    long convertEnd = System.nanoTime();

                    long grabUs = (grabEnd - grabStart) / 1000L;
                    long convertUs = (convertEnd - convertStart) / 1000L; // только конвертация, без пейсинга
                    if (grabUs > maxGrabUs) maxGrabUs = grabUs;
                    if (convertUs > maxConvertUs) maxConvertUs = convertUs;

                    if (convertEnd - lastDecodeLogNs >= DECODE_LOG_INTERVAL_NS) {
                        lastDecodeLogNs = convertEnd;
                        maxGrabUs = 0;
                        maxConvertUs = 0;
                    }

                    long relativeTs = (baseStreamTsUs != Long.MIN_VALUE && tsUsForPace > 0) ? (tsUsForPace - baseStreamTsUs) : 0;
                    sink.onFrame(out, target.w(), target.h(), relativeTs);
                }

                if (ended) {
                    sink.onEnded(durationMs);
                }

            } catch (LineUnavailableException e) {
            } finally {
                currentAudio = null;
                try {
                    grabber.stop();
                } catch (Exception ignored) {
                }
            }

        } catch (Exception e) {
            dbg("playOnce: FFmpeg decode failed url=" + url + " err=" + e);
            return false;
        }

        return true;
    }

    private static void applyNetOptions(FFmpegFrameGrabber g, String url) {
        // Применяем сетевые опции только для HTTP/HTTPS URL
        boolean isHttp = url != null && (url.startsWith("http://") || url.startsWith("https://"));

        try {
            // Эти опции безопасны для любых источников
            g.setOption("probesize", "500000");
            g.setOption("analyzeduration", "500000");
            g.setOption("fflags", "nobuffer");

            // Сетевые опции только для HTTP
            if (isHttp) {
                g.setOption("reconnect", "1");
                g.setOption("reconnect_streamed", "1");
                g.setOption("reconnect_delay_max", "2");
                g.setOption("reconnect_at_eof", "1");

                // timeouts (в микросекундах)
                g.setOption("rw_timeout", "5000000");
                g.setOption("timeout", "5000000");
                g.setOption("stimeout", "5000000");

                // user-agent
                g.setOption("user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

                // try to make http seek/Range work when server supports it
                g.setOption("seekable", "1");
                g.setOption("multiple_requests", "1");
            }
        } catch (Exception ignored) {
        }
    }

    private static String tryResolveUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return null;

        // Некоторые сокращатели/хостинги делают несколько редиректов.
        // FFmpeg умеет редиректы, но иногда долго; попробуем быстро получить финальный URL.
        try {
            String cur = u;
            for (int i = 0; i < 5; i++) {
                URL base = new URL(cur);
                HttpURLConnection c = (HttpURLConnection) base.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod("GET");
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "identity");

                int code = c.getResponseCode();

                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.isBlank()) {
                        c.disconnect();
                        break;
                    }
                    URL next = new URL(base, loc);
                    c.disconnect();
                    cur = next.toString();
                    continue;
                }

                if (code == 401 || code == 403) {
                    c.disconnect();
                    return null;
                }

                c.disconnect();
                return cur.equals(u) ? null : cur;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static final class ProbeResult {
        final String finalUrl;
        final String contentType;
        final boolean supportsRange;
        final boolean isHttp;
        final String contentDisposition;
        final int httpCode;

        private ProbeResult(String finalUrl, String contentType, boolean supportsRange, boolean isHttp, String contentDisposition, int httpCode) {
            this.finalUrl = finalUrl;
            this.contentType = contentType;
            this.supportsRange = supportsRange;
            this.isHttp = isHttp;
            this.contentDisposition = contentDisposition;
            this.httpCode = httpCode;
        }
    }

    private static ProbeResult probeUrl(String url) {
        if (url == null) return null;
        String u = stripFragment(url.trim());
        boolean isHttp = (u.startsWith("http://") || u.startsWith("https://"));
        if (!isHttp) return null;

        try {
            String cur = u;
            for (int i = 0; i < 5; i++) {
                URL base = new URL(cur);
                HttpURLConnection c = (HttpURLConnection) base.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod("GET");
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Range", "bytes=0-1");
                c.setRequestProperty("Accept-Encoding", "identity");

                int code = c.getResponseCode();
                dbg("probe: GET " + cur + " -> " + code);

                if (code == 401 || code == 403 || code == 416) {
                    try { c.disconnect(); } catch (Exception ignored) {}
                    c = (HttpURLConnection) base.openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0");
                    c.setRequestProperty("Accept", "*/*");
                    c.setRequestProperty("Accept-Encoding", "identity");
                    code = c.getResponseCode();
                    dbg("probe: retry GET(no-range) " + cur + " -> " + code);
                }

                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null || loc.isBlank()) return new ProbeResult(cur, null, false, true, null, code);
                    URL next = new URL(base, loc);
                    cur = next.toString();
                    continue;
                }

                if (code < 200 || code >= 400) {
                    String ct = null;
                    try {
                        ct = c.getHeaderField("Content-Type");
                    } catch (Exception ignored) {
                    }
                    dbg("probe: http error code=" + code + " url=" + cur + " ct=" + ct);
                    c.disconnect();
                    return new ProbeResult(cur, ct, false, true, null, code);
                }

                String ct = c.getHeaderField("Content-Type");
                String ar = c.getHeaderField("Accept-Ranges");
                boolean supportsRange = false;
                if (code == 206) supportsRange = true;
                if (ar != null && ar.toLowerCase(Locale.ROOT).contains("bytes")) supportsRange = true;
                String cd = c.getHeaderField("Content-Disposition");
                long len = -1L;
                try {
                    len = c.getContentLengthLong();
                } catch (Exception ignored) {
                }
                dbg("probe: finalUrl=" + cur + " ct=" + ct + " ar=" + ar + " len=" + len + " supportsRange=" + supportsRange + " cd=" + cd);
                c.disconnect();
                return new ProbeResult(cur, ct, supportsRange, true, cd, code);
            }
        } catch (Exception e) {
            dbg("probe: exception " + e + " url=" + u);
        }

        return null;
    }

    private static Path ensureCachedToDisk(String cacheKeyUrl, String downloadUrl, ProbeResult pr,
                                           FrameSink sink, long sessionId, VideoPlayer player) {
        if (cacheKeyUrl == null || cacheKeyUrl.isBlank()) return null;
        if (downloadUrl == null || downloadUrl.isBlank()) return null;
        if (pr == null) return null;

        String key = cacheKeyUrl.trim();
        String u = stripFragment(downloadUrl.trim());
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return null;

        String hash = sha256Hex(key);
        Object lock = DISK_CACHE_LOCKS.computeIfAbsent(hash, k -> new Object());

        synchronized (lock) {
            try {
                dbg("cache: start keyHash=" + hash + " url=" + u);
                Path dir = getCacheDir();
                Files.createDirectories(dir);

                Path partFile = dir.resolve(hash + ".part");
                // Если загрузка в процессе (есть .part файл), ждём завершения
                int waitAttempts = 0;
                while (Files.exists(partFile) && waitAttempts < 300) { // макс 5 минут
                    // Проверяем что сессия ещё активна
                    if (player != null && player.sessionId != sessionId) {
                        dbg("cache: session changed while waiting, aborting");
                        return null;
                    }
                    dbg("cache: waiting for download in progress keyHash=" + hash);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    waitAttempts++;
                }

                Path existing = findExistingCacheFile(dir, hash);
                if (existing != null && Files.isRegularFile(existing)) {
                    try {
                        long sz = Files.size(existing);
                        if (sz > 0 && sz <= DISK_CACHE_MAX_BYTES) {
                            // Проверяем валидность кэша через FFmpeg
                            if (isValidMediaFile(existing)) {
                                try {
                                    Files.setLastModifiedTime(existing, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                                } catch (Exception ignored) {
                                }
                                enforceDiskCacheLimit(dir, DISK_CACHE_MAX_BYTES);
                                DISK_CACHE_LAST_FAIL_MS.remove(hash);
                                dbg("cache: using valid existing file keyHash=" + hash);
                                // Уведомляем о использовании существующего кэша
                                if (sink != null) {
                                    try {
                                        sink.onCachedFileUsed(existing.toString(), sz);
                                    } catch (Exception ignored) {}
                                }
                                return existing;
                            } else {
                                dbg("cache: existing file invalid, re-downloading keyHash=" + hash);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        Files.deleteIfExists(existing);
                    } catch (Exception ignored) {
                    }
                }

                Long lastFail = DISK_CACHE_LAST_FAIL_MS.get(hash);
                if (lastFail != null && (System.currentTimeMillis() - lastFail) < DISK_CACHE_FAIL_COOLDOWN_MS) {
                    dbg("cache: cooldown active keyHash=" + hash);
                    return null;
                }

                enforceDiskCacheLimit(dir, DISK_CACHE_MAX_BYTES);

                Path tmp = dir.resolve(hash + ".part");
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }

                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setRequestMethod("GET");
                c.setConnectTimeout(15_000);
                c.setReadTimeout(60_000); // Увеличен для больших файлов
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "identity");

                int code = c.getResponseCode();
                if (code < 200 || code >= 400) {
                    dbg("cache: non-2xx code=" + code + " url=" + u);
                    c.disconnect();
                    DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                    return null;
                }

                long declaredLen = -1L;
                try {
                    declaredLen = c.getContentLengthLong();
                } catch (Exception ignored) {
                }
                dbg("cache: contentLength=" + declaredLen);
                if (declaredLen > DISK_CACHE_MAX_BYTES) {
                    c.disconnect();
                    DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                    return null;
                }

                String actualCt = null;
                try {
                    actualCt = c.getHeaderField("Content-Type");
                } catch (Exception ignored) {
                }
                dbg("cache: contentType=" + actualCt + " url=" + u);
                String ext = guessCacheExtension(u, actualCt != null ? actualCt : pr.contentType);
                Path dst = dir.resolve(hash + ext);

                long written = 0L;
                long lastProgressLog = 0L;
                try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                    // Если probe видел text/html, но мы всё равно пытаемся кэшировать —
                    // защитимся от сохранения HTML-страницы в кэш.
                    boolean ctHtml = false;
                    try {
                        String baseCt = (actualCt != null) ? actualCt : pr.contentType;
                        if (baseCt != null) {
                            String ct = baseCt.toLowerCase(Locale.ROOT);
                            ctHtml = ct.startsWith("text/html");
                        }
                    } catch (Exception ignored) {
                    }

                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) >= 0) {
                        if (written == 0L && ctHtml) {
                            int n = Math.min(r, 512);
                            String head;
                            try {
                                head = new String(buf, 0, n, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                            } catch (Exception e) {
                                head = "";
                            }
                            if (head.contains("<html") || head.contains("<!doctype") || head.contains("<head") || head.contains("<body")) {
                                try {
                                    Files.deleteIfExists(tmp);
                                } catch (Exception ignored) {
                                }
                                DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                                return null;
                            }
                        }

                        out.write(buf, 0, r);
                        written += r;

                        // Логируем прогресс каждые 10 МБ
                        long progressMb = written / (10L * 1024L * 1024L);
                        if (progressMb > lastProgressLog) {
                            lastProgressLog = progressMb;
                            long writtenMb = written / (1024L * 1024L);
                            long totalMb = declaredLen > 0 ? declaredLen / (1024L * 1024L) : -1;
                            int pct = declaredLen > 0 ? (int) (written * 100L / declaredLen) : -1;

                            if (declaredLen > 0) {
                                dbg("cache: downloading... " + writtenMb + " MB / " + totalMb + " MB (" + pct + "%)");
                            } else {
                                dbg("cache: downloading... " + writtenMb + " MB");
                            }

                            // Отправляем прогресс в sink
                            if (sink != null) {
                                sink.onDownloadProgress(Math.max(0, pct), writtenMb, Math.max(0, totalMb));
                            }

                            // Проверяем что сессия ещё активна
                            if (player != null && player.sessionId != sessionId) {
                                dbg("cache: session changed during download, aborting");
                                try {
                                    Files.deleteIfExists(tmp);
                                } catch (Exception ignored) {
                                }
                                return null;
                            }
                        }

                        if (written > DISK_CACHE_MAX_BYTES) {
                            try {
                                Files.deleteIfExists(tmp);
                            } catch (Exception ignored) {
                            }
                            DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                            return null;
                        }
                    }
                } finally {
                    c.disconnect();
                }

                dbg("cache: downloaded bytes=" + written + " -> " + dst);

                try {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    dbg("cache: atomic move succeeded");
                } catch (Exception e) {
                    dbg("cache: atomic move failed, trying regular move: " + e.getMessage());
                    try {
                        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
                        dbg("cache: regular move succeeded");
                    } catch (Exception moveErr) {
                        dbg("cache: regular move also failed: " + moveErr.getMessage());
                        try {
                            Files.deleteIfExists(tmp);
                        } catch (Exception ignored2) {
                        }
                        DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                        return null;
                    }
                }

                try {
                    long sz = Files.size(dst);
                    dbg("cache: final file size=" + sz);
                    if (sz <= 0 || sz > DISK_CACHE_MAX_BYTES) {
                        dbg("cache: invalid file size, deleting");
                        try {
                            Files.deleteIfExists(dst);
                        } catch (Exception ignored) {
                        }
                        DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                        return null;
                    }
                } catch (Exception e) {
                    dbg("cache: failed to check file size: " + e.getMessage());
                }

                enforceDiskCacheLimit(dir, DISK_CACHE_MAX_BYTES);
                DISK_CACHE_LAST_FAIL_MS.remove(hash);
                dbg("cache: success, returning " + dst);
                return dst;
            } catch (Exception e) {
                dbg("cache: exception " + e);
                DISK_CACHE_LAST_FAIL_MS.put(hash, System.currentTimeMillis());
                return null;
            }
        }
    }

    private static Path findExistingCacheFile(Path dir, String hash) {
        try {
            Path bin = dir.resolve(hash + ".bin");
            if (Files.isRegularFile(bin)) return bin;

            try (var s = Files.list(dir)) {
                return s.filter(p -> {
                            try {
                                if (!Files.isRegularFile(p)) return false;
                                String n = p.getFileName().toString();
                                if (!n.startsWith(hash + ".")) return false;
                                if (n.endsWith(".part")) return false;
                                return true;
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String guessCacheExtension(String url, String contentType) {
        try {
            if (contentType != null) {
                String ct = contentType.toLowerCase(Locale.ROOT);
                if (ct.contains("video/mp4")) return ".mp4";
                if (ct.contains("video/webm")) return ".webm";
                if (ct.contains("matroska") || ct.contains("video/x-matroska")) return ".mkv";
                if (ct.contains("video/quicktime")) return ".mov";
            }

            URL u = new URL(url);
            String path = u.getPath();
            if (path == null) return ".dat";
            int dot = path.lastIndexOf('.');
            if (dot < 0) return ".dat";
            String ext = path.substring(dot);
            if (ext.length() < 2 || ext.length() > 6) return ".dat";
            for (int i = 1; i < ext.length(); i++) {
                char ch = ext.charAt(i);
                if (!(ch >= 'a' && ch <= 'z') && !(ch >= 'A' && ch <= 'Z') && !(ch >= '0' && ch <= '9')) {
                    return ".dat";
                }
            }
            return ext.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return ".dat";
        }
    }

    private static Path getCacheDir() {
        try {
            // Сначала пробуем стандартный путь
            Path gameDir = FabricLoader.getInstance().getGameDir();
            String gameDirStr = gameDir.toString();

            // Проверяем, есть ли не-ASCII символы в пути (проблема с FFmpeg на Windows)
            boolean hasNonAscii = false;
            for (int i = 0; i < gameDirStr.length(); i++) {
                if (gameDirStr.charAt(i) > 127) {
                    hasNonAscii = true;
                    break;
                }
            }

            if (hasNonAscii) {
                // На Windows пробуем получить короткое имя (8.3) через cmd
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                if (os.contains("win")) {
                    try {
                        // Пробуем получить короткий путь через cmd /c for %I
                        Path cacheDir = gameDir.resolve("collins-cache");
                        Files.createDirectories(cacheDir);

                        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "for", "%I", "in",
                            "(\"" + cacheDir.toString() + "\")", "do", "@echo", "%~sI");
                        pb.redirectErrorStream(true);
                        Process p = pb.start();
                        String shortPath = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                        p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

                        if (shortPath != null && !shortPath.isBlank() && !shortPath.contains(" ") && Files.isDirectory(Path.of(shortPath))) {
                            // Проверяем что короткий путь не содержит не-ASCII
                            boolean shortHasNonAscii = false;
                            for (int i = 0; i < shortPath.length(); i++) {
                                if (shortPath.charAt(i) > 127) {
                                    shortHasNonAscii = true;
                                    break;
                                }
                            }
                            if (!shortHasNonAscii) {
                                dbg("getCacheDir: using short path: " + shortPath);
                                return Path.of(shortPath);
                            }
                        }
                    } catch (Exception e) {
                        dbg("getCacheDir: failed to get short path: " + e.getMessage());
                    }

                    // Fallback: используем C:\collins-cache
                    Path fallbackDir = Path.of("C:\\collins-cache");
                    try {
                        Files.createDirectories(fallbackDir);
                        dbg("getCacheDir: using fallback dir (non-ASCII in game path): " + fallbackDir);
                        return fallbackDir;
                    } catch (Exception e) {
                        // Fallback на TEMP
                        String temp = System.getenv("TEMP");
                        if (temp != null && !temp.isBlank()) {
                            Path tempDir = Path.of(temp, "collins-cache");
                            Files.createDirectories(tempDir);
                            dbg("getCacheDir: using TEMP dir: " + tempDir);
                            return tempDir;
                        }
                    }
                }
            }

            return gameDir.resolve("collins-cache");
        } catch (Exception e) {
            return Path.of("collins-cache");
        }
    }

    private static void enforceDiskCacheLimit(Path dir, long maxBytes) {
        try {
            if (!Files.isDirectory(dir)) return;

            List<Path> files = new ArrayList<>();
            long total = 0L;
            try (var s = Files.list(dir)) {
                s.forEach(p -> {
                    try {
                        if (Files.isRegularFile(p) && !p.getFileName().toString().endsWith(".part")) {
                            files.add(p);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }

            for (Path p : files) {
                try {
                    total += Files.size(p);
                } catch (Exception ignored) {
                }
            }

            if (total <= maxBytes) return;

            files.sort(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (Exception e) {
                    return 0L;
                }
            }));

            for (Path p : files) {
                if (total <= maxBytes) break;
                long sz = 0L;
                try {
                    sz = Files.size(p);
                } catch (Exception ignored) {
                }
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
                total -= sz;
            }
        } catch (Exception ignored) {
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // ==================== Дисковый менеджер ====================

    /** Информация о кэше */
    public record CacheInfo(Path cacheDir, long cacheSizeBytes, int fileCount, long freeSpaceBytes) {
        public long cacheSizeMb() { return cacheSizeBytes / (1024L * 1024L); }
        public long freeSpaceMb() { return freeSpaceBytes / (1024L * 1024L); }
        public long freeSpaceGb() { return freeSpaceBytes / (1024L * 1024L * 1024L); }
    }

    /** Получить информацию о кэше */
    public static CacheInfo getCacheInfo() {
        try {
            Path dir = getCacheDir();
            if (!Files.isDirectory(dir)) {
                long freeSpace = dir.getRoot() != null ?
                    dir.getRoot().toFile().getFreeSpace() : 0L;
                return new CacheInfo(dir, 0L, 0, freeSpace);
            }

            long totalSize = 0L;
            int count = 0;
            try (var stream = Files.list(dir)) {
                var files = stream.toList();
                for (Path p : files) {
                    if (Files.isRegularFile(p) && !p.getFileName().toString().endsWith(".part")) {
                        try {
                            totalSize += Files.size(p);
                            count++;
                        } catch (Exception ignored) {}
                    }
                }
            }

            long freeSpace = dir.toFile().getFreeSpace();
            return new CacheInfo(dir, totalSize, count, freeSpace);
        } catch (Exception e) {
            return new CacheInfo(Path.of("collins-cache"), 0L, 0, 0L);
        }
    }

    /** Очистить весь кэш */
    public static long clearCache() {
        try {
            Path dir = getCacheDir();
            if (!Files.isDirectory(dir)) return 0L;

            long deleted = 0L;
            try (var stream = Files.list(dir)) {
                var files = stream.toList();
                for (Path p : files) {
                    if (Files.isRegularFile(p)) {
                        try {
                            long sz = Files.size(p);
                            Files.deleteIfExists(p);
                            deleted += sz;
                        } catch (Exception ignored) {}
                    }
                }
            }
            return deleted;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Удалить конкретный файл из кэша */
    public static boolean deleteCachedFile(String filePath) {
        try {
            Path p = Path.of(filePath);
            if (Files.exists(p)) {
                Files.deleteIfExists(p);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Открыть папку кэша в проводнике */
    public static void openCacheFolder() {
        try {
            Path dir = getCacheDir();
            Files.createDirectories(dir);

            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"explorer", dir.toString()});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", dir.toString()});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", dir.toString()});
            }
        } catch (Exception e) {
            dbg("openCacheFolder: failed " + e.getMessage());
        }
    }
}
