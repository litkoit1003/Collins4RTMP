package org.sawiq.collins.paper.selection;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.sawiq.collins.paper.model.Selection;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SelectionService {
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public Selection get(Player p) {
        return selections.getOrDefault(p.getUniqueId(), new Selection(null, null));
    }

    public void setPos1(Player p, Block b) {
        Selection s = get(p);
        selections.put(p.getUniqueId(), new Selection(b, s.pos2()));
    }

    public void setPos2(Player p, Block b) {
        Selection s = get(p);
        selections.put(p.getUniqueId(), new Selection(s.pos1(), b));
    }
}
