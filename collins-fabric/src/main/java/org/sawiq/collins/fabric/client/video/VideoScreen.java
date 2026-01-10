package org.sawiq.collins.fabric.client.video;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.system.MemoryUtil;
import org.sawiq.collins.fabric.client.state.ScreenState;
import org.sawiq.collins.fabric.mixin.NativeImageAccessor;

import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class VideoScreen implements VideoPlayer.FrameSink {

    private ScreenState state;

    private Identifier texId;
    private NativeImageBackedTexture texture;

    private VideoPlayer player;

    private int texW, texH;

    private long nativePtr = 0;
    private IntBuffer nativeDst = null;

    private volatile boolean started = false;
    private String startedUrl = "";
    private float lastGain = -1f;

    // ===== Очередь кадров для буферизации =====
    private record InitReq(int videoW, int videoH, int targetW, int targetH, double fps) {}
    private record FrameData(int[] abgr, int w, int h, long timestampUs) {}
    // ожидаем ABGR (см. VideoPlayer), timestampUs = позиция кадра в микросекундах

    private final AtomicReference<InitReq> pendingInit = new AtomicReference<>(null);
    private final ConcurrentLinkedQueue<FrameData> frameQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger frameQueueSize = new AtomicInteger(0);
    private final AtomicBoolean pendingStop = new AtomicBoolean(false);
    
    // пул свободных буферов - буферы возвращаются после показа кадра
    private final ConcurrentLinkedQueue<int[]> freeBuffers = new ConcurrentLinkedQueue<>();
    private static final int BUFFER_POOL_SIZE = 60; // должен быть > MAX_BUFFER_FRAMES
    
    // буферизация: ждём пока накопится минимум кадров перед показом
    private static final int MIN_BUFFER_FRAMES = 15; // ~0.5 сек при 30fps
    private static final int MAX_BUFFER_FRAMES = 45; // ~1.5 сек максимум
    private volatile boolean buffering = true; // тру пока буферизуем
    // ====================================================================

    // Пейсинг на render thread
    private double videoFps = 30.0;
    private volatile long playbackStartNs = 0; // время начала воспроизведения (из декодера или локальное)
    private long framesShown = 0;
    
    // Диагностика
    private long lastUploadLogNs = 0;
    private static final long UPLOAD_LOG_INTERVAL_NS = 2_000_000_000L;

    // Диагностика tick() - ищем источник фризов
    private long lastTickNs = 0;
    private long maxTickGapUs = 0;
    private long maxTickDurationUs = 0;
    private long lastTickLogNs = 0;
    private static final long TICK_LOG_INTERVAL_NS = 2_000_000_000L;

    public VideoScreen(ScreenState state) {
        this.state = state;
    }

    public ScreenState state() { return state; }

    public void updateState(ScreenState newState) { this.state = newState; }

    public boolean hasTexture() { return texture != null && texId != null; }

    public Identifier textureId() { return texId; }

    public void tickPlayback(Vec3d playerPos, int radiusBlocks, float globalVolume, long serverNowMs) {
        long tickStart = System.nanoTime();
        
        // диагностика: время между tick() и время внутри tick()
        if (lastTickNs > 0) {
            long gapUs = (tickStart - lastTickNs) / 1000L;
            if (gapUs > maxTickGapUs) maxTickGapUs = gapUs;
        }
        
        // 1) применяем всё, что пришло из декодера (ТОЛЬКО тут)
        applyPendingStop();
        applyPendingInit();

        // 2) управление воспроизведением
        if (state.url() == null || state.url().isEmpty() || !state.playing()) {
            stop();
            return;
        }

        // 2.1) hear radius: вне радиуса полностью отключаем (и видео, и звук)
        if (!isInHearRadius(playerPos, radiusBlocks)) {
            stop();
            return;
        }

        long posMs = currentVideoPosMs(serverNowMs);
        float gain = Math.max(0f, globalVolume) * Math.max(0f, state.volume());

        if (player == null) player = new VideoPlayer(this);

        if (!started || !startedUrl.equals(state.url())) {
            started = true;
            startedUrl = state.url();
            lastGain = gain;
            player.start(state.url(), state.blocksW(), state.blocksH(), state.loop(), posMs, gain);
            return;
        }

        if (Math.abs(gain - lastGain) > 0.001f) {
            lastGain = gain;
            player.setGain(gain);
        }

        // диагностика: лог пиковых значений tick
        long tickEnd = System.nanoTime();
        long durationUs = (tickEnd - tickStart) / 1000L;
        if (durationUs > maxTickDurationUs) maxTickDurationUs = durationUs;
        lastTickNs = tickEnd;

        if (tickEnd - lastTickLogNs >= TICK_LOG_INTERVAL_NS) {
            lastTickLogNs = tickEnd;
            System.out.println("[Collins] tick peak: gap=" + maxTickGapUs + "us duration=" + maxTickDurationUs + "us");
            maxTickGapUs = 0;
            maxTickDurationUs = 0;
        }
    }

    public void renderPlayback() {
        if (!started) return;
        uploadPendingFrameFast();
    }

    private boolean isInHearRadius(Vec3d playerPos, int radiusBlocks) {
        if (playerPos == null) return false;
        if (radiusBlocks <= 0) return true;

        double cx = (state.minX() + state.maxX() + 1) * 0.5;
        double cy = (state.minY() + state.maxY() + 1) * 0.5;
        double cz = (state.minZ() + state.maxZ() + 1) * 0.5;

        double dx = playerPos.x - cx;
        double dy = playerPos.y - cy;
        double dz = playerPos.z - cz;

        double r = (double) radiusBlocks;
        return (dx * dx + dy * dy + dz * dz) <= (r * r);
    }

    private void applyPendingStop() {
        if (!pendingStop.getAndSet(false)) return;

        started = false;
        startedUrl = "";
        lastGain = -1f;

        frameQueue.clear();
        frameQueueSize.set(0);
        buffering = true;
        playbackStartNs = 0;
        framesShown = 0;
        lastUploadLogNs = 0;
    }

    private void applyPendingInit() {
        InitReq req = pendingInit.getAndSet(null);
        if (req == null) return;

        this.texW = req.targetW();
        this.texH = req.targetH();
        this.videoFps = req.fps();

        if (texId == null) {
            texId = Identifier.of("collins", "screen/" + state.name().toLowerCase());
        }

        if (texture != null) {
            texture.close();
            texture = null;
        }

        texture = new NativeImageBackedTexture("collins:" + texId, texW, texH, true);
        MinecraftClient.getInstance().getTextureManager().registerTexture(texId, texture);

        NativeImage imgForPtr = texture.getImage();
        if (imgForPtr != null) {
            nativePtr = ((NativeImageAccessor) (Object) imgForPtr).collins$getPointer();
            nativeDst = MemoryUtil.memIntBuffer(nativePtr, texW * texH);
        } else {
            nativePtr = 0;
            nativeDst = null;
        }

        // быстро заливаем цветом (без двойных циклов)
        NativeImage img = texture.getImage();
        if (img != null) {
            img.fillRect(0, 0, texW, texH, 0xFFFF00FF);
        }

        texture.upload();

        // очередь кадров и сбрасываем пейсинг
        frameQueue.clear();
        frameQueueSize.set(0);
        buffering = true;
        playbackStartNs = 0;
        framesShown = 0;
        lastUploadLogNs = 0;
        
        // пул буферов
        freeBuffers.clear();
        int pixels = texW * texH;
        for (int i = 0; i < BUFFER_POOL_SIZE; i++) {
            freeBuffers.offer(new int[pixels]);
        }

        System.out.println("[Collins] initVideo " + texW + "x" + texH + " fps=" + videoFps + " pool=" + BUFFER_POOL_SIZE + " buffering...");
    }

    /**
     * Берём кадр из очереди с пейсингом по fps видео.
     * Буферизация: ждём пока накопится MIN_BUFFER_FRAMES перед началом показа.
     */
    private void uploadPendingFrameFast() {
        if (texture == null) return;

        int queueSize = frameQueueSize.get();
        
        // Буферизация:
        if (buffering) {
            long now = System.nanoTime();
            if (now - lastUploadLogNs >= UPLOAD_LOG_INTERVAL_NS) {
                lastUploadLogNs = now;
                System.out.println("[Collins] buffering... " + queueSize + "/" + MIN_BUFFER_FRAMES + " frames");
            }
            if (queueSize < MIN_BUFFER_FRAMES) {
                return; // ещё буферизуем
            }
            buffering = false;
            if (playbackStartNs == 0) playbackStartNs = System.nanoTime();
            framesShown = 0;
            System.out.println("[Collins] buffering done, queue=" + queueSize + " frames, fps=" + videoFps);
        }

        if (playbackStartNs == 0) playbackStartNs = System.nanoTime();

        long now = System.nanoTime();
        long elapsedUs = (now - playbackStartNs) / 1000L;

        FrameData frame = frameQueue.peek();
        if (frame == null) return;

        FrameData chosen = null;
        while (true) {
            FrameData next = frameQueue.peek();
            if (next == null) break;
            if (next.timestampUs() > elapsedUs) break;

            chosen = frameQueue.poll();
            if (chosen == null) break;
            frameQueueSize.decrementAndGet();

            FrameData peekAfter = frameQueue.peek();
            if (peekAfter != null && peekAfter.timestampUs() <= elapsedUs) {
                freeBuffers.offer(chosen.abgr());
                chosen = null;
            }
        }

        if (chosen == null) return;
        frame = chosen;
        framesShown++;

        int w = frame.w();
        int h = frame.h();
        int[] abgr = frame.abgr();
        
        if (w != texW || h != texH) {
            // Размер не совпадает - возвращаем буфер в пул и пропускаем
            freeBuffers.offer(abgr);
            return;
        }

        IntBuffer dst = nativeDst;
        if (dst == null) {
            freeBuffers.offer(abgr);
            return;
        }
        int pixels = texW * texH;

        long copyStart = System.nanoTime();
        dst.position(0);
        dst.put(abgr, 0, pixels);

        long uploadStart = System.nanoTime();
        texture.upload();
        long end = System.nanoTime();
        
        // ВАЖНО: возвращаем буфер в пул после использования
        freeBuffers.offer(abgr);

        if (end - lastUploadLogNs >= UPLOAD_LOG_INTERVAL_NS) {
            lastUploadLogNs = end;
            long lagUs = elapsedUs - frame.timestampUs();
            System.out.println("[Collins] frame " + framesShown + " ts=" + (frame.timestampUs()/1000) + "ms lag=" + (lagUs/1000) + "ms queue=" + queueSize);
        }
    }

    private long currentVideoPosMs(long serverNowMs) {
        long base = Math.max(0L, state.basePosMs());
        if (serverNowMs <= 0 || state.startEpochMs() <= 0) return base;
        return base + Math.max(0L, serverNowMs - state.startEpochMs());
    }

    public void stop() {
        if (player != null) player.stop();

        started = false;
        startedUrl = "";
        lastGain = -1f;

        frameQueue.clear();
        frameQueueSize.set(0);
        buffering = true;

        playbackStartNs = 0;
        framesShown = 0;
        lastUploadLogNs = 0;
    }

    // ===== FrameSink: эти методы могут вызываться ИЗ ДЕКОДЕР-ПОТОКА =====

    @Override
    public void initVideo(int videoW, int videoH, int targetW, int targetH, double fps) {
        pendingInit.set(new InitReq(videoW, videoH, targetW, targetH, fps));
    }

    @Override
    public void onFrame(int[] abgr, int w, int h, long timestampUs) {
        if (abgr == null) return;
        
        // Ограничиваем размер очереди чтобы не съесть всю память
        if (frameQueueSize.get() >= MAX_BUFFER_FRAMES) {
            // Очередь полна - декодер должен ждать
            freeBuffers.offer(abgr);
            return;
        }
        
        frameQueue.offer(new FrameData(abgr, w, h, timestampUs));
        frameQueueSize.incrementAndGet();
    }

    @Override
    public void onStop() {
        pendingStop.set(true);
    }

    @Override
    public void onPlaybackClockStart(long wallStartNs) {
        this.playbackStartNs = wallStartNs;
    }

    @Override
    public boolean canAcceptFrame() {
        return frameQueueSize.get() < MAX_BUFFER_FRAMES;
    }

    @Override
    public int[] borrowBuffer() {
        return freeBuffers.poll();
    }

    @Override
    public void returnBuffer(int[] buf) {
        if (buf != null) {
            freeBuffers.offer(buf);
        }
    }

    @Override
    public boolean isBufferReady() {
        // Буфер готов когда буферизация закончена
        return !buffering;
    }

    public int texW() { return texW; }
    public int texH() { return texH; }
}
