package org.sawiq.collins.fabric.client.video;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.ffmpeg.global.avutil;

import javax.sound.sampled.LineUnavailableException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

public final class VideoPlayer {

    static {
        try {
            avutil.av_log_set_level(avutil.AV_LOG_QUIET);
        } catch (Throwable ignored) {
        }
    }

    public interface FrameSink {
        void initVideo(int videoW, int videoH, int targetW, int targetH, double fps);
        void onFrame(int[] argb, int w, int h, long timestampUs);
        void onStop();
        default void onPlaybackClockStart(long wallStartNs) {}
        default void onDuration(long durationMs) {}
        /** true если буфер ещё не полон (декодер может продолжать) */
        default boolean canAcceptFrame() { return true; }
        /** Получить свободный буфер из пула (или null если пул пуст) */
        default int[] borrowBuffer() { return null; }
        /** Вернуть буфер в пул после использования */
        default void returnBuffer(int[] buf) {}
        /** true когда буфер видео готов (можно начинать аудио) */
        default boolean isBufferReady() { return true; }
    }

    private final FrameSink sink;

    private volatile boolean running;
    private Thread thread;

    private volatile long startPosMs = 0;
    private volatile float gain = 1.0f;
    private volatile VideoAudioPlayer currentAudio;
    private volatile long startRequestEpochMs = 0;

    private record CachedMeta(String resolvedUrl, int videoW, int videoH, double fps, long durationMs, long cachedAtMs) {}
    private static final ConcurrentHashMap<String, CachedMeta> META_CACHE = new ConcurrentHashMap<>();
    private static final long META_TTL_MS = 15L * 60L * 1000L;

    public VideoPlayer(FrameSink sink) {
        this.sink = sink;
    }

    public void start(String url, int blocksW, int blocksH, boolean loop) {
        start(url, blocksW, blocksH, loop, 0L, 1.0f);
    }

    public void start(String url, int blocksW, int blocksH, boolean loop, long startPosMs, float gain) {
        stop();
        this.startPosMs = Math.max(0L, startPosMs);
        this.gain = Math.max(0f, gain);
        this.startRequestEpochMs = System.currentTimeMillis();

        final String urlFinal = url;

        running = true;
        thread = new Thread(() -> runLoop(urlFinal, blocksW, blocksH, loop), "Collins-VideoPlayer");
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

        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;

        VideoAudioPlayer a = currentAudio;
        if (a != null) a.shutdownNow();
    }

    private void runLoop(String url, int blocksW, int blocksH, boolean loop) {
        try {
            boolean first = true;
            while (running) {
                long seekMs = first ? startPosMs : 0L;
                long requestEpochMs = first ? startRequestEpochMs : 0L;
                first = false;

                playOnce(url, blocksW, blocksH, seekMs, requestEpochMs);

                if (!loop) break;
            }
        } finally {
            currentAudio = null;
            sink.onStop();
        }
    }

    private void playOnce(String url, int blocksW, int blocksH, long seekMs, long requestEpochMs) {
        String originalUrl = url;

        int videoW;
        int videoH;
        double fps;
        long durationMs;

        CachedMeta cached = META_CACHE.get(originalUrl);
        if (cached != null && (System.currentTimeMillis() - cached.cachedAtMs()) <= META_TTL_MS) {
            url = cached.resolvedUrl();
            videoW = cached.videoW();
            videoH = cached.videoH();
            fps = cached.fps();
            durationMs = cached.durationMs();
        } else {
            String resolved = tryResolveUrl(url);
            if (resolved != null) url = resolved;

            try (FFmpegFrameGrabber meta = new FFmpegFrameGrabber(url)) {
                applyNetOptions(meta);
                meta.start();
                videoW = meta.getImageWidth();
                videoH = meta.getImageHeight();
                fps = meta.getVideoFrameRate();
                long lenUs = meta.getLengthInTime();
                durationMs = lenUs > 0 ? (lenUs / 1000L) : 0L;
                meta.stop();
            } catch (Exception e) {
                return;
            }

            if (fps <= 0) fps = 30.0;
            META_CACHE.put(originalUrl, new CachedMeta(url, videoW, videoH, fps, durationMs, System.currentTimeMillis()));
        }

        if (videoW <= 0 || videoH <= 0) {
            return;
        }

        // 2) target размер
        VideoSizeUtil.Size target = VideoSizeUtil.pick(blocksW, blocksH, videoW, videoH);

        // 3) декод
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url)) {
            applyNetOptions(grabber);
            grabber.setImageWidth(target.w());
            grabber.setImageHeight(target.h());
            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
            grabber.start();

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

                while (running) {
                    long grabStart = System.nanoTime();
                    Frame frame = grabber.grab();
                    long grabEnd = System.nanoTime();
                    if (frame == null) break;

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
                            if (Thread.interrupted()) return;
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

            } catch (LineUnavailableException e) {
            } finally {
                currentAudio = null;
                try { grabber.stop(); } catch (Exception ignored) {}
            }

        } catch (Exception e) {
        }
    }

    private static void applyNetOptions(FFmpegFrameGrabber g) {
        try {
            g.setOption("reconnect", "1");
            g.setOption("reconnect_streamed", "1");
            g.setOption("reconnect_delay_max", "2");

            // timeouts (в микросекундах)
            g.setOption("rw_timeout", "8000000");

            // faster stream detection
            g.setOption("probesize", "2000000");
            g.setOption("analyzeduration", "2000000");

            // user-agent
            g.setOption("user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

            // keep probing minimal
            g.setOption("fflags", "nobuffer");
        } catch (Exception ignored) {
        }
    }

    private static String tryResolveUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return null;

        // Google Drive / usercontent часто делает несколько редиректов.
        // FFmpeg умеет редиректы, но иногда долго; попробуем быстро получить финальный URL.
        try {
            String cur = u;
            for (int i = 0; i < 5; i++) {
                URL base = new URL(cur);
                HttpURLConnection c = (HttpURLConnection) base.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");

                int code;
                try {
                    code = c.getResponseCode();
                } catch (Exception headFailed) {
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {}

                    c = (HttpURLConnection) base.openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0");
                    c.setRequestProperty("Range", "bytes=0-0");
                    code = c.getResponseCode();
                }

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

                c.disconnect();
                return cur.equals(u) ? null : cur;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
