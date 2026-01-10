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

import java.util.*;

public final class CollinsCommand implements TabExecutor {

    private final JavaPlugin plugin;
    private final ScreenStore store;
    private final CollinsMessenger messenger;
    private final SelectionService selection;
    private final CollinsRuntimeState runtime;
    private final Lang lang;

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

                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.screen.created", lang.vars("name", name));
                return true;
            }

            case "seturl" -> {
                if (args.length < 3) { lang.send(p, "error.usage", lang.vars("usage", "/collins seturl <screen> <url>")); return true; }

                String name = args[1];
                String url = args[2];

                Screen s = store.get(name);
                if (s == null) { lang.send(p, "error.screen_not_found", lang.vars("name", name)); return true; }

                Screen updated = new Screen(
                        s.name(), s.world(),
                        s.x1(), s.y1(), s.z1(),
                        s.x2(), s.y2(), s.z2(),
                        s.axis(),
                        url,
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

                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.url.set", lang.vars("name", name));
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
                        s.loop(),
                        s.volume()
                );

                store.put(updated);
                store.save();
                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.playing", lang.vars("name", name));
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
                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.stopped", lang.vars("name", name));
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
                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.paused", lang.vars("name", name));
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
                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.resumed", lang.vars("name", name));
                return true;
            }

            case "volume" -> {
                if (args.length < 2) { lang.send(p, "error.usage", lang.vars("usage", "/collins volume set <0..2> | reset")); return true; }

                String act = args[1].toLowerCase(Locale.ROOT);
                if (act.equals("reset")) {
                    runtime.globalVolume = 1.0f;
                    messenger.broadcastSync();
                    lang.send(p, "cmd.global_volume.reset");
                    return true;
                }

                if (act.equals("set")) {
                    if (args.length < 3) { lang.send(p, "error.usage", lang.vars("usage", "/collins volume set <0..2>")); return true; }
                    float v;
                    try { v = Float.parseFloat(args[2]); }
                    catch (Exception e) { lang.send(p, "error.bad_number"); return true; }

                    v = Math.max(0f, Math.min(2f, v));
                    runtime.globalVolume = v;
                    messenger.broadcastSync();
                    lang.send(p, "cmd.global_volume.set", lang.vars("value", v));
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
                    messenger.broadcastSync();
                    lang.send(p, "cmd.hear_radius.reset");
                    return true;
                }

                if (act.equals("set")) {
                    if (args.length < 3) { lang.send(p, "error.usage", lang.vars("usage", "/collins radius set <1..512>")); return true; }
                    int r;
                    try { r = Integer.parseInt(args[2]); }
                    catch (Exception e) { lang.send(p, "error.bad_number"); return true; }

                    r = Math.max(1, Math.min(512, r));
                    runtime.hearRadius = r;
                    messenger.broadcastSync();
                    lang.send(p, "cmd.hear_radius.set", lang.vars("value", r));
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

                messenger.broadcastSync();
                SelectionVisualizer.stop(p);

                lang.send(p, "cmd.screen.removed", lang.vars("name", name));
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
                    "volume", "radius",
                    "remove", "list"
            ));
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("seturl") || sub.equals("play") || sub.equals("stop") || sub.equals("pause") || sub.equals("resume") || sub.equals("remove")) {
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

    private List<String> startsWith(String token, List<String> options) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t)) out.add(o);
        }
        return out;
    }
}
