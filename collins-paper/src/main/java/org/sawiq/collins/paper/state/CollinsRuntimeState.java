package org.sawiq.collins.paper.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CollinsRuntimeState {
    public volatile float globalVolume = 1.0f;
    public volatile int hearRadius = 100;

    public static final class Playback {
        public volatile long startEpochMs = 0; // когда “пошло”
        public volatile long basePosMs = 0;    // накопленная позиция (для resume)
    }

    private final Map<String, Playback> playback = new ConcurrentHashMap<>();

    public Playback get(String screenName) {
        return playback.computeIfAbsent(screenName.toLowerCase(), k -> new Playback());
    }

    public void resetPlayback(String screenName) {
        Playback p = get(screenName);
        p.startEpochMs = 0;
        p.basePosMs = 0;
    }
}
