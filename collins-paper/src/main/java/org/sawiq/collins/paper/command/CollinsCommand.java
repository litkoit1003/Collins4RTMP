package org.sawiq.collins.paper.command;

import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.sawiq.collins.paper.model.Screen;
import org.sawiq.collins.paper.net.CollinsMessenger;
import org.sawiq.collins.paper.selection.SelectionService;
import org.sawiq.collins.paper.selection.SelectionVisualizer;
import org.sawiq.collins.paper.state.CollinsRuntimeState;
import org.sawiq.collins.paper.store.ScreenStore;
import org.sawiq.collins.paper.util.Lang;
import org.sawiq.collins.paper.util.ScreenFactory;

import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.*;

public final class CollinsCommand implements TabExecutor {

    private static final Pattern SCREEN_NAME_RE = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    private final JavaPlugin plugin;
    private final ScreenStore store;
    private final CollinsMessenger messenger;
    private final SelectionService selection;
    private final CollinsRuntimeState runtime;
    private final Lang lang;

    private final Map<UUID, Long> lastCommandAtMs = new ConcurrentHashMap<>();

    public CollinsCommand(JavaPlugin plugin,
                          ScreenStore store,
                          CollinsMessenger messenger,
                          SelectionService selection,
                          CollinsRuntimeState runtime,
                          Lang lang) {
        this.plugin = plugin;
        this.store = store;
        this.messenger = messenger;
        this.selection = selection;
        this.runtime = runtime;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (!p.hasPermission("collins.admin")) {
            lang.send(p, "error.no_permission");
            return true;
        }

        long nowMs = System.currentTimeMillis();
        int rateLimitMs = cfgInt("security.commandRateLimitMs", 250);
        if (rateLimitMs > 0) {
            long last = lastCommandAtMs.getOrDefault(p.getUniqueId(), 0L);
            long since = nowMs - last;
            if (since >= 0 && since < rateLimitMs) {
                lang.send(p, "error.rate_limited", lang.vars("ms", (rateLimitMs - since)));
                return true;
            }
            lastCommandAtMs.put(p.getUniqueId(), nowMs);
        }

        if (args.length == 0) {
            lang.send(p, "cmd.help");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "pos1" -> {
                Block target = p.getTargetBlockExact(200);
                if (target == null) { lang.send(p, "error.no_target_block"); return true; }
                selection.setPos1(p, target);
                lang.send(p, "cmd.pos1.set", lang.vars(
                        "x", target.getX(),
                        "y", target.getY(),
                        "z", target.getZ()
                ));
                return true;
            }

            case "pos2" -> {
                Block target = p.getTargetBlockExact(200);
                if (target == null) { lang.send(p, "error.no_target_block"); return true; }
                selection.setPos2(p, target);
                lang.send(p, "cmd.pos2.set", lang.vars(
                        "x", target.getX(),
                        "y", target.getY(),
                        "z", target.getZ()
                ));

                var sel = selection.get(p);
                if (sel.complete() && sel.pos1().getWorld().equals(sel.pos2().getWorld())) {
                    SelectionVisualizer.showFrame(
                            plugin, p,
                            sel.pos1().getX(), sel.pos1().getY(), sel.pos1().getZ(),
                            sel.pos2().getX(), sel.pos2().getY(), sel.pos2().getZ(),
                            200L
                    );
                }
                return true;
            }

            case "create" -> {
                if (args.length < 2) { lang.send(p, "error.usage", lang.vars("usage", "/collins create <name>")); return true; }
                String name = args[1];

                if (!isValidScreenName(name)) {
                    lang.send(p, "error.invalid_screen_name", lang.vars("name", name));
                    return true;
                }

                var sel = selection.get(p);
                if (!sel.complete()) {
                    lang.send(p, "error.selection_not_complete");
                    return true;
                }
                if (!sel.pos1().getWorld().equals(sel.pos2().getWorld())) {
                    lang.send(p, "error.selection_world_mismatch");
                    return true;
                }

                Screen screen = ScreenFactory.create(name, sel.pos1(), sel.pos2());
                if (screen == null) {
                    lang.send(p, "error.selection_invalid");
                    return true;
                }

                store.put(screen);
                store.save();

                runtime.resetPlayback(screen.name()); // чтобы новый экран не унаследовал таймер

                messenger.requestBroadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.screen.created", lang.vars("name", name));
                plugin.getLogger().info(p.getName() + " created screen '" + name + "'");
                return true;
            }

            case "seturl" -> {
                if (args.length < 3) { lang.send(p, "error.usage", lang.vars("usage", "/collins seturl <screen> <url>")); return true; }

                String name = args[1];
                String url = args[2];

                String safeUrl = validateAndNormalizeUrl(url);
                if (safeUrl == null) {
                    lang.send(p, "error.invalid_url");
                    plugin.getLogger().info(p.getName() + " rejected URL for screen '" + name + "'");
                    return true;
                }

                Screen s = store.get(name);
                if (s == null) { lang.send(p, "error.screen_not_found", lang.vars("name", name)); return true; }

                Screen updated = new Screen(
                        s.name(), s.world(),
                        s.x1(), s.y1(), s.z1(),
                        s.x2(), s.y2(), s.z2(),
                        s.axis(),
                        safeUrl,
                        s.playing(),
                        s.loop(),
                        s.volume()
                );

                // Смена url => сброс таймера, иначе будет seek в старую позицию другого файла
                runtime.resetPlayback(s.name());
                if (updated.playing()) {
                    CollinsRuntimeState.Playback pb = runtime.get(s.name());
                    pb.startEpochMs = System.currentTimeMillis();
                    pb.basePosMs = 0;
                }

                store.put(updated);
                store.save();

                messenger.requestBroadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.url.set", lang.vars("name", name));
                plugin.getLogger().info(p.getName() + " seturl for screen '" + name + "'");
                return true;
            }

            case "play" -> {
                if (args.length < 2) { lang.send(p, "error.usage", lang.vars("usage", "/collins play <screen>")); return true; }
                String name = args[1];

                Screen s = store.get(name);
                if (s == null) { lang.send(p, "error.screen_not_found", lang.vars("name", name)); return true; }

                // play = старт с нуля
                CollinsRuntimeState.Playback pb = runtime.get(s.name());
                pb.basePosMs = 0;
                pb.startEpochMs = System.currentTimeMillis();

                Screen updated = new Screen(
                        s.name(), s.world(),
                        s.x1(), s.y1(), s.z1(),
                        s.x2(), s.y2(), s.z2(),
                        s.axis(),
                        s.mp4Url(),
                        true,
                        isStreamingUrl(s.mp4Url()) || s.loop(),
                        s.volume()
                );

                store.put(updated);
                store.save();
                messenger.requestBroadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.playing", lang.vars("name", name));
                plugin.getLogger().info(p.getName() + " play '" + name + "'");
                return true;
            }

            case "stop" -> {
                if (args.length < 2) { lang.send(p, "error.usage", lang.vars("usage", "/collins stop <screen>")); return true; }
                String name = args[1];

                Screen s = store.get(name);
                if (s == null) { lang.send(p, "error.screen_not_found", lang.vars("name", name)); return true; }

                // stop = выключить и сбросить позицию на 0
                runtime.resetPlayback(s.name());

                Screen updated = new Screen(
                        s.name(), s.world(),
                        s.x1(), s.y1(), s.z1(),
                        s.x2(), s.y2(), s.z2(),
                        s.axis(),
                        s.mp4Url(),
                        false,
                        s.loop(),
                        s.volume()
                );

                store.put(updated);
                store.save();
                messenger.requestBroadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.stopped", lang.vars("name", name));
                plugin.getLogger().info(p.getName() + " stop '" + name + "'");
                return true;
            }

            case "pause" -> {
                if (args.length < 2) { lang.send(p, "error.usage", lang.vars("usage", "/collins pause <screen>")); return true; }
                String name = args[1];

                Screen s = store.get(name);
                if (s == null) { lang.send(p, "error.screen_not_found", lang.vars("name", name)); return true; }

                // pause = выключить, но сохранить позицию
                long now = System.currentTimeMillis();
                CollinsRuntimeState.Playback pb = runtime.get(s.name());
                if (s.playing() && pb.startEpochMs > 0) {
                    pb.basePosMs += Math.max(0, now - pb.startEpochMs);
                }
                pb.startEpochMs = 0;

                Screen updated = new Screen(
                        s.name(), s.world(),
                        s.x1(), s.y1(), s.z1(),
                        s.x2(), s.y2(), s.z2(),
                        s.axis(),
                        s.mp4Url(),
                        false,
                        s.loop(),
                        s.volume()
                );

                store.put(updated);
                store.save();
                messenger.requestBroadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.paused", lang.vars("name", name));
                plugin.getLogger().info(p.getName() + " pause '" + name + "'");
                return true;
            }

            case "resume" -> {
                if (args.length < 2) { lang.send(p, "error.usage", lang.vars("usage", "/collins resume <screen>")); return true; }
                String name = args[1];

                Screen s = store.get(name);
                if (s == null) { lang.send(p, "error.screen_not_found", lang.vars("name", name)); return true; }

                CollinsRuntimeState.Playback pb = runtime.get(s.name());
                pb.startEpochMs = System.currentTimeMillis();

                Screen updated = new Screen(
                        s.name(), s.world(),
                        s.x1(), s.y1(), s.z1(),
                        s.x2(), s.y2(), s.z2(),
                        s.axis(),
                        s.mp4Url(),
                        true,
                        s.loop(),
                        s.volume()
                );

                store.put(updated);
                store.save();
                messenger.requestBroadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.resumed", lang.vars("name", name));
                plugin.getLogger().info(p.getName() + " resume '" + name + "'");
                return true;
            }

            case "seek" -> {
                if (args.length < 3) { lang.send(p, "error.usage", lang.vars("usage", "/collins seek <screen> <seconds>")); return true; }
                String name = args[1];

                Screen s = store.get(name);
                if (s == null) { lang.send(p, "error.screen_not_found", lang.vars("name", name)); return true; }

                if (isStreamingUrl(s.mp4Url())) {
                    lang.send(p, "error.cannot_seek_stream", lang.vars("name", name));
                    return true;
                }

                double seconds;
                try { seconds = Double.parseDouble(args[2]); }
                catch (Exception e) { lang.send(p, "error.bad_number"); return true; }

                long maxSeekSeconds = cfgLong("security.maxSeekSeconds", 3600L);
                if (!Double.isFinite(seconds) || Math.abs(seconds) > (double) maxSeekSeconds) {
                    lang.send(p, "error.seek_too_large", lang.vars("max", maxSeekSeconds));
                    return true;
                }

                long maxDeltaMs = Math.max(0L, maxSeekSeconds) * 1000L;
                long rawDeltaMs = (long) Math.floor(seconds * 1000.0);
                long deltaMs = clamp(rawDeltaMs, -maxDeltaMs, maxDeltaMs);
                long now = System.currentTimeMillis();

                CollinsRuntimeState.Playback pb = runtime.get(s.name());
                long curMs = pb.basePosMs;
                if (s.playing() && pb.startEpochMs > 0) {
                    curMs += Math.max(0L, now - pb.startEpochMs);
                }

                long nextMs = Math.max(0L, curMs + deltaMs);
                pb.basePosMs = nextMs;
                pb.startEpochMs = s.playing() ? now : 0L;

                messenger.requestBroadcastSync();

                lang.send(p, "cmd.seeked", lang.vars(
                        "name", s.name(),
                        "from", formatMs(curMs),
                        "to", formatMs(nextMs),
                        "pos", formatMs(nextMs)
                ));
                plugin.getLogger().info(p.getName() + " seek '" + name + "' " + seconds + "s");
                return true;
            }

            case "back" -> {
                if (args.length < 3) { lang.send(p, "error.usage", lang.vars("usage", "/collins back <screen> <seconds>")); return true; }
                String name = args[1];

                Screen s = store.get(name);
                if (s == null) { lang.send(p, "error.screen_not_found", lang.vars("name", name)); return true; }

                if (isStreamingUrl(s.mp4Url())) {
                    lang.send(p, "error.cannot_seek_stream", lang.vars("name", name));
                    return true;
                }

                double seconds;
                try { seconds = Double.parseDouble(args[2]); }
                catch (Exception e) { lang.send(p, "error.bad_number"); return true; }

                long maxSeekSeconds = cfgLong("security.maxSeekSeconds", 3600L);
                if (!Double.isFinite(seconds) || Math.abs(seconds) > (double) maxSeekSeconds) {
                    lang.send(p, "error.seek_too_large", lang.vars("max", maxSeekSeconds));
                    return true;
                }

                long maxDeltaMs = Math.max(0L, maxSeekSeconds) * 1000L;
                long rawDeltaMs = -(long) Math.floor(Math.abs(seconds) * 1000.0);
                long deltaMs = clamp(rawDeltaMs, -maxDeltaMs, maxDeltaMs);
                long now = System.currentTimeMillis();

                CollinsRuntimeState.Playback pb = runtime.get(s.name());
                long curMs = pb.basePosMs;
                if (s.playing() && pb.startEpochMs > 0) {
                    curMs += Math.max(0L, now - pb.startEpochMs);
                }

                long nextMs = Math.max(0L, curMs + deltaMs);
                pb.basePosMs = nextMs;
                pb.startEpochMs = s.playing() ? now : 0L;

                messenger.requestBroadcastSync();

                lang.send(p, "cmd.seeked", lang.vars(
                        "name", s.name(),
                        "from", formatMs(curMs),
                        "to", formatMs(nextMs),
                        "pos", formatMs(nextMs)
                ));
                plugin.getLogger().info(p.getName() + " back '" + name + "' " + seconds + "s");
                return true;
            }

            case "volume" -> {
                if (args.length < 2) { lang.send(p, "error.usage", lang.vars("usage", "/collins volume set <0..2> | reset")); return true; }

                String act = args[1].toLowerCase(Locale.ROOT);
                if (act.equals("reset")) {
                    runtime.globalVolume = 1.0f;
                    messenger.requestBroadcastSync();
                    lang.send(p, "cmd.global_volume.reset");
                    plugin.getLogger().info(p.getName() + " volume reset");
                    return true;
                }

                if (act.equals("set")) {
                    if (args.length < 3) { lang.send(p, "error.usage", lang.vars("usage", "/collins volume set <0..2>")); return true; }
                    float v;
                    try { v = Float.parseFloat(args[2]); }
                    catch (Exception e) { lang.send(p, "error.bad_number"); return true; }

                    v = Math.max(0f, Math.min(2f, v));
                    runtime.globalVolume = v;
                    messenger.requestBroadcastSync();
                    lang.send(p, "cmd.global_volume.set", lang.vars("value", v));
                    plugin.getLogger().info(p.getName() + " volume set " + v);
                    return true;
                }

                lang.send(p, "error.usage", lang.vars("usage", "/collins volume set <0..2> | reset"));
                return true;
            }

            case "radius" -> {
                if (args.length < 2) { lang.send(p, "error.usage", lang.vars("usage", "/collins radius set <1..512> | reset")); return true; }

                String act = args[1].toLowerCase(Locale.ROOT);
                if (act.equals("reset")) {
                    runtime.hearRadius = 100;
                    messenger.requestBroadcastSync();
                    lang.send(p, "cmd.hear_radius.reset");
                    plugin.getLogger().info(p.getName() + " radius reset");
                    return true;
                }

                if (act.equals("set")) {
                    if (args.length < 3) { lang.send(p, "error.usage", lang.vars("usage", "/collins radius set <1..512>")); return true; }
                    int r;
                    try { r = Integer.parseInt(args[2]); }
                    catch (Exception e) { lang.send(p, "error.bad_number"); return true; }

                    r = Math.max(1, Math.min(512, r));
                    runtime.hearRadius = r;
                    messenger.requestBroadcastSync();
                    lang.send(p, "cmd.hear_radius.set", lang.vars("value", r));
                    plugin.getLogger().info(p.getName() + " radius set " + r);
                    return true;
                }

                lang.send(p, "error.usage", lang.vars("usage", "/collins radius set <1..512> | reset"));
                return true;
            }

            case "remove" -> {
                if (args.length < 2) { lang.send(p, "error.usage", lang.vars("usage", "/collins remove <screen>")); return true; }
                String name = args[1];

                Screen removed = store.remove(name);
                if (removed == null) { lang.send(p, "error.screen_not_found", lang.vars("name", name)); return true; }

                store.save();
                runtime.resetPlayback(removed.name());

                messenger.requestBroadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.screen.removed", lang.vars("name", name));
                plugin.getLogger().info(p.getName() + " removed screen '" + name + "'");
                return true;
            }

            case "list" -> {
                lang.send(p, "cmd.screens.header");
                for (Screen s : store.all()) {
                    lang.send(p, "cmd.screens.item", lang.vars(
                            "name", s.name(),
                            "url", (s.mp4Url() == null ? "" : s.mp4Url()),
                            "playing", s.playing()
                    ));
                }
                return true;
            }

            default -> {
                lang.send(p, "error.usage", lang.vars("usage", "/collins"));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return List.of();

        if (args.length == 1) {
            return startsWith(args[0], List.of(
                    "pos1", "pos2", "create", "seturl",
                    "play", "stop", "pause", "resume",
                    "seek", "back",
                    "volume", "radius",
                    "remove", "list"
            ));
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("seturl") || sub.equals("play") || sub.equals("stop") || sub.equals("pause") || sub.equals("resume") || sub.equals("remove") || sub.equals("seek") || sub.equals("back")) {
                List<String> names = new ArrayList<>();
                for (Screen s : store.all()) names.add(s.name());
                return startsWith(args[1], names);
            }
            if (sub.equals("volume") || sub.equals("radius")) {
                return startsWith(args[1], List.of("set", "reset"));
            }
        }

        return List.of();
    }

    private String formatMs(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000L;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;

        if (hours > 0) {
            return hours + ":" + pad2(minutes) + ":" + pad2(seconds);
        }
        return minutes + ":" + pad2(seconds);
    }

    private String pad2(long v) {
        return v < 10 ? ("0" + v) : Long.toString(v);
    }

    private int cfgInt(String path, int def) {
        try {
            return plugin.getConfig().getInt(path, def);
        } catch (Exception ignored) {
            return def;
        }
    }

    private long cfgLong(String path, long def) {
        try {
            return plugin.getConfig().getLong(path, def);
        } catch (Exception ignored) {
            return def;
        }
    }

    private boolean cfgBool(String path, boolean def) {
        try {
            return plugin.getConfig().getBoolean(path, def);
        } catch (Exception ignored) {
            return def;
        }
    }

    private long clamp(long v, long min, long max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private boolean isValidScreenName(String name) {
        if (name == null) return false;
        return SCREEN_NAME_RE.matcher(name).matches();
    }

    private String validateAndNormalizeUrl(String raw) {
        if (raw == null) return null;

        String u = raw.trim();
        if (u.isEmpty()) return null;

        int maxLen = cfgInt("security.maxUrlLength", 2048);
        if (maxLen > 0 && u.length() > maxLen) return null;

        if (u.indexOf('\n') >= 0 || u.indexOf('\r') >= 0 || u.indexOf('\0') >= 0) return null;

        final URI uri;
        try {
            uri = URI.create(u);
        } catch (Exception ignored) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);

        if (!(scheme.equals("http") || scheme.equals("https") ||
                scheme.equals("rtmp") || scheme.equals("rtmps") ||
                scheme.equals("rtsp") || scheme.equals("rtsps"))) {
            return null;
        }

        if (uri.getRawUserInfo() != null) return null;

        String host = uri.getHost();
        if (host == null || host.isBlank()) return null;
        if (host.equalsIgnoreCase("localhost")) return null;

        if (scheme.startsWith("rtmp") || scheme.startsWith("rtsp")) {
            try {
                return uri.toASCIIString();
            } catch (Exception ignored) {
                return u;
            }
        }

        boolean allowPrivate = cfgBool("security.allowPrivateUrls", false);
        boolean dnsResolve = cfgBool("security.dnsResolve", false);

        if (!allowPrivate) {
            InetAddress[] addrs = null;

            boolean looksLikeIp = host.indexOf(':') >= 0 || host.matches("^[0-9.]+$");
            if (looksLikeIp) {
                try {
                    addrs = new InetAddress[] { InetAddress.getByName(host) };
                } catch (Exception ignored) {
                    return null;
                }
            } else if (dnsResolve) {
                try {
                    addrs = InetAddress.getAllByName(host);
                } catch (Exception ignored) {
                    return null;
                }
            }

            if (addrs != null) {
                for (InetAddress a : addrs) {
                    if (a.isAnyLocalAddress()) return null;
                    if (a.isLoopbackAddress()) return null;
                    if (a.isLinkLocalAddress()) return null;
                    if (a.isSiteLocalAddress()) return null;
                    if (a.isMulticastAddress()) return null;
                }
            }
        }

        try {
            return uri.toASCIIString();
        } catch (Exception ignored) {
            return u;
        }
    }

    private List<String> startsWith(String token, List<String> options) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t)) out.add(o);
        }
        return out;
    }

    private boolean isStreamingUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.startsWith("rtmp://") ||
                u.startsWith("rtmps://") ||
                u.startsWith("rtsp://") ||
                u.startsWith("rtsps://") ||
                u.contains(".m3u8");
    }
}
