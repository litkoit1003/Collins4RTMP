package org.sawiq.collins.paper.model;

public record Screen(
        String name,
        String world,
        int x1, int y1, int z1,
        int x2, int y2, int z2,
        byte axis,          // 0=XY,1=XZ,2=YZ
        String mp4Url,
        boolean playing,
        boolean loop,
        float volume
) {}
