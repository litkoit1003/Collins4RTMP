package org.sawiq.collins.paper.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CollinsRuntimeState {
    public volatile float globalVolume = 1.0f;
    public volatile int hearRadius = 100;

    public static final class Playback {
        public volatile long startEpochMs = 0; // когда “пошло”
        public volatile long basePosMs = 0;    // накопленная позиция (для resume)
        public volatile long durationMs = 0;   // длительность видео (от клиента)
    }

    private final Map<String, Playback> playback = new ConcurrentHashMap<>();

    public Playback get(String screenName) {
        return playback.computeIfAbsent(screenName.toLowerCase(), k -> new Playback());
    }

    public void resetPlayback(String screenName) {
        Playback p = get(screenName);
        p.startEpochMs = 0;
        p.basePosMs = 0;
        p.durationMs = 0;
    }

    public void setDuration(String screenName, long durationMs) {
        Playback p = get(screenName);
        if (durationMs > 0) {
            // Принимаем если:
            // - ещё не установлено (0)
            // - запрос FFprobe уже отправлен (-1)
            // - новое значение близко к старому (±5сек)
            if (p.durationMs <= 0 || Math.abs(p.durationMs - durationMs) < 5000) {
                p.durationMs = durationMs;
            }
        }
    }

    /** Установить duration от FFprobe (приоритет над клиентом) */
    public void setDurationFromServer(String screenName, long durationMs) {
        if (durationMs > 0) {
            get(screenName).durationMs = durationMs;
        }
    }

    /** Возвращает текущую позицию видео в миллисекундах */
    public long getCurrentPosMs(String screenName) {
        Playback p = get(screenName);
        if (p.startEpochMs <= 0) return p.basePosMs;
        return p.basePosMs + (System.currentTimeMillis() - p.startEpochMs);
    }

    /** Проверяет закончилось ли видео (если известна длительность) */
    public boolean isVideoEnded(String screenName) {
        Playback p = get(screenName);
        if (p.durationMs <= 0) return false; // Длительность неизвестна
        if (p.startEpochMs <= 0) return false; // Видео не запущено
        long currentPos = getCurrentPosMs(screenName);
        // Добавляем буфер 1 секунду для надёжности
        return currentPos >= (p.durationMs - 1000);
    }

    /** Возвращает длительность видео или 0 если неизвестна */
    public long getDurationMs(String screenName) {
        return get(screenName).durationMs;
    }
}
