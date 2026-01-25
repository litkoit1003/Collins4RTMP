package org.sawiq.collins.paper.model;

import org.bukkit.block.Block;

public record Selection(Block pos1, Block pos2) {
    public boolean complete() {
        return pos1 != null && pos2 != null;
    }
}
