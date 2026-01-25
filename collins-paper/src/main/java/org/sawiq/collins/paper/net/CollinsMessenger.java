package org.sawiq.collins.paper.net;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.state.CollinsRuntimeState;
import org.sawiq.collins.paper.store.ScreenStore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

public final class CollinsMessenger {

    private final JavaPlugin plugin;
    private final ScreenStore store;
    private final CollinsRuntimeState runtime;

    private volatile boolean broadcastScheduled;

    public CollinsMessenger(JavaPlugin plugin, ScreenStore store, CollinsRuntimeState runtime) {
        this.plugin = plugin;
        this.store = store;
        this.runtime = runtime;
    }

    public void sendSync(Player player) {
        try {
            byte[] payload = buildWrappedSyncBytes();
            player.sendPluginMessage(plugin, "collins:main", payload);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send SYNC: " + e.getMessage());
        }
    }

    public void broadcastSync() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendSync(p);
        }
    }
    public void requestBroadcastSync() {
        if (broadcastScheduled) return;
        broadcastScheduled = true;

        Bukkit.getScheduler().runTask(plugin, () -> {
            broadcastScheduled = false;
            broadcastSync();
        });
    }

    // WRAP: magic(4) + len(int) + innerBytes
    private byte[] buildWrappedSyncBytes() throws Exception {
        byte[] inner = buildSyncInnerBytes();

        var bout = new ByteArrayOutputStream();
        var out = new DataOutputStream(bout);

        out.write("COLL".getBytes(StandardCharsets.US_ASCII)); // magic
        out.writeInt(inner.length);
        out.write(inner);
        out.flush();

        return bout.toByteArray();
    }

    /**
     * INNER (v2):
     * byte msg
     * int version
     * float globalVolume
     * int hearRadius
     * long serverNowMs
     * int count
     * repeated screens:
     *   UTF name
     *   UTF world
     *   int x1 y1 z1
     *   int x2 y2 z2
     *   byte axis
     *   UTF url
     *   boolean playing
     *   boolean loop
     *   float volume
     *   long startEpochMs
     *   long basePosMs
     */
    private byte[] buildSyncInnerBytes() throws Exception {
        long now = System.currentTimeMillis();

        var bout = new ByteArrayOutputStream();
        var out = new DataOutputStream(bout);

        out.writeByte(CollinsProtocol.MSG_SYNC);
        out.writeInt(CollinsProtocol.PROTOCOL_VERSION);

        // v2 global config + time anchor
        out.writeFloat(runtime.globalVolume);
        out.writeInt(runtime.hearRadius);
        out.writeLong(now);

        var all = store.all();
        out.writeInt(all.size());

        for (Screen s : all) {
            out.writeUTF(s.name());
            out.writeUTF(s.world());

            out.writeInt(s.x1()); out.writeInt(s.y1()); out.writeInt(s.z1());
            out.writeInt(s.x2()); out.writeInt(s.y2()); out.writeInt(s.z2());

            out.writeByte(s.axis());

            out.writeUTF(s.mp4Url() == null ? "" : s.mp4Url());
            out.writeBoolean(s.playing());
            out.writeBoolean(s.loop());
            out.writeFloat(s.volume());

            CollinsRuntimeState.Playback pb = runtime.get(s.name());
            out.writeLong(pb.startEpochMs);
            out.writeLong(pb.basePosMs);
        }

        out.flush();
        return bout.toByteArray();
    }
}
