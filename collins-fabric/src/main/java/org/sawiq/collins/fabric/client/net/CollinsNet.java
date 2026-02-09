package org.sawiq.collins.fabric.client.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.sawiq.collins.fabric.client.state.ScreenState;
import org.sawiq.collins.fabric.net.CollinsMainS2CPayload;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CollinsNet {

    private CollinsNet() {}

    private static final boolean DEBUG = false;

    public static final int MAX_PACKET_BYTES = 5_000_000;

    public static final Map<String, ScreenState> SCREENS = new ConcurrentHashMap<>();

    // v2 globals
    public static volatile float GLOBAL_VOLUME = 1.0f;
    public static volatile int HEAR_RADIUS = 100;

    // time anchor (v2)
    public static volatile long SERVER_NOW_MS = 0;
    public static volatile long CLIENT_RECV_MS = 0;

    public static void initClientReceiver() {
        if (DEBUG) System.out.println("[Collins] Client init: registering receiver collins:main");

        ClientPlayNetworking.registerGlobalReceiver(CollinsMainS2CPayload.ID, (payload, context) -> {
            byte[] bytes = payload.data();

            context.client().execute(() -> {
                try {
                    parseWrapped(bytes);
                } catch (Exception e) {
                    if (DEBUG) System.out.println("[Collins] Failed to parse packet: " + e.getMessage());
                }
            });
        });
    }

    private static void parseWrapped(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length < 8) return; // 4 magic + 4 len

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = new byte[4];
            in.readFully(magic);

            String m = new String(magic, StandardCharsets.US_ASCII);
            if (!m.equals("COLL")) {
                return;
            }

            int len = in.readInt();
            if (len < 0 || len > MAX_PACKET_BYTES) {
                if (DEBUG) System.out.println("[Collins] Bad len=" + len);
                return;
            }

            int available = in.available();
            if (available < len) {
                if (DEBUG) System.out.println("[Collins] Not enough bytes. need=" + len + " avail=" + available);
                return;
            }

            byte[] inner = new byte[len];
            in.readFully(inner);

            parseInner(inner);
        }
    }

    private static void parseInner(byte[] inner) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(inner))) {
            byte msg = in.readByte();
            int version = in.readInt();

            if (msg != 1) {
                if (DEBUG) System.out.println("[Collins] Unsupported msg=" + msg + " ver=" + version);
                return;
            }

            if (version == 1) {
                // v1: только экраны, без таймера и глобальных настроек
                int count = in.readInt();
                if (count < 0 || count > 10_000) {
                    if (DEBUG) System.out.println("[Collins] Bad screen count=" + count);
                    return;
                }

                GLOBAL_VOLUME = 1.0f;
                HEAR_RADIUS = 100;
                SERVER_NOW_MS = 0;
                CLIENT_RECV_MS = 0;

                SCREENS.clear();
                for (int i = 0; i < count; i++) {
                    String name = in.readUTF();
                    String world = in.readUTF();

                    int x1 = in.readInt(), y1 = in.readInt(), z1 = in.readInt();
                    int x2 = in.readInt(), y2 = in.readInt(), z2 = in.readInt();

                    byte axis = in.readByte();
                    String url = in.readUTF();

                    boolean playing = in.readBoolean();
                    boolean loop = in.readBoolean();
                    float volume = in.readFloat();

                    SCREENS.put(name.toLowerCase(), new ScreenState(
                            name, world,
                            x1, y1, z1,
                            x2, y2, z2,
                            axis,
                            url,
                            playing,
                            loop,
                            volume,
                            0L,
                            0L
                    ));
                }

                org.sawiq.collins.fabric.client.video.VideoScreenManager.applySync(SCREENS);
                if (DEBUG) System.out.println("[Collins] SYNC v1 received: " + count + " screens");
                return;
            }

            if (version != 2) {
                if (DEBUG) System.out.println("[Collins] Unsupported msg=" + msg + " ver=" + version);
                return;
            }

            // v2: глобальные настройки + якорь времени + экраны с таймером
            GLOBAL_VOLUME = in.readFloat();
            HEAR_RADIUS = in.readInt();
            SERVER_NOW_MS = in.readLong();
            CLIENT_RECV_MS = System.currentTimeMillis();

            int count = in.readInt();
            if (count < 0 || count > 10_000) {
                if (DEBUG) System.out.println("[Collins] Bad screen count=" + count);
                return;
            }

            SCREENS.clear();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                String world = in.readUTF();

                int x1 = in.readInt(), y1 = in.readInt(), z1 = in.readInt();
                int x2 = in.readInt(), y2 = in.readInt(), z2 = in.readInt();

                byte axis = in.readByte();
                String url = in.readUTF();

                boolean playing = in.readBoolean();
                boolean loop = in.readBoolean();
                float volume = in.readFloat();

                long startEpochMs = in.readLong();
                long basePosMs = in.readLong();

                SCREENS.put(name.toLowerCase(), new ScreenState(
                        name, world,
                        x1, y1, z1,
                        x2, y2, z2,
                        axis,
                        url,
                        playing,
                        loop,
                        volume,
                        startEpochMs,
                        basePosMs
                ));
            }

            org.sawiq.collins.fabric.client.video.VideoScreenManager.applySync(SCREENS);
            if (DEBUG) System.out.println("[Collins] SYNC v2 received: " + count + " screens");
        }
    }

    // ==================== C2S (клиент -> сервер) ====================

    private static final byte MSG_VIDEO_ENDED = 2;
    private static final byte MSG_VIDEO_DURATION = 3;
    private static final int PROTOCOL_VERSION = 2;

    /**
     * Отправляет сообщение серверу об окончании видео на экране
     */
    public static void sendVideoEnded(String screenName) {
        try {
            java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bout);

            // Inner payload
            out.writeByte(MSG_VIDEO_ENDED);
            out.writeInt(PROTOCOL_VERSION);
            out.writeUTF(screenName);
            out.flush();
            byte[] inner = bout.toByteArray();

            // Wrapped payload
            bout = new java.io.ByteArrayOutputStream();
            out = new java.io.DataOutputStream(bout);
            out.write("COLL".getBytes(StandardCharsets.US_ASCII));
            out.writeInt(inner.length);
            out.write(inner);
            out.flush();

            byte[] payload = bout.toByteArray();
            ClientPlayNetworking.send(new org.sawiq.collins.fabric.net.CollinsMainC2SPayload(payload));

            if (DEBUG) System.out.println("[Collins] Sent VIDEO_ENDED for screen: " + screenName);
        } catch (Exception e) {
            if (DEBUG) System.out.println("[Collins] Failed to send VIDEO_ENDED: " + e.getMessage());
        }
    }

    /**
     * Отправляет серверу длительность видео (для автоматического завершения)
     */
    public static void sendVideoDuration(String screenName, long durationMs) {
        if (durationMs <= 0) return;

        try {
            java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bout);

            // Inner payload
            out.writeByte(MSG_VIDEO_DURATION);
            out.writeInt(PROTOCOL_VERSION);
            out.writeUTF(screenName);
            out.writeLong(durationMs);
            out.flush();
            byte[] inner = bout.toByteArray();

            // Wrapped payload
            bout = new java.io.ByteArrayOutputStream();
            out = new java.io.DataOutputStream(bout);
            out.write("COLL".getBytes(StandardCharsets.US_ASCII));
            out.writeInt(inner.length);
            out.write(inner);
            out.flush();

            byte[] payload = bout.toByteArray();
            ClientPlayNetworking.send(new org.sawiq.collins.fabric.net.CollinsMainC2SPayload(payload));

            if (DEBUG) System.out.println("[Collins] Sent VIDEO_DURATION for screen: " + screenName + " duration=" + durationMs + "ms");
        } catch (Exception e) {
            if (DEBUG) System.out.println("[Collins] Failed to send VIDEO_DURATION: " + e.getMessage());
        }
    }
}
