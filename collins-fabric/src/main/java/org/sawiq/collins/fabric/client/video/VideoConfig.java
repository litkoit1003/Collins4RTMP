package org.sawiq.collins.fabric.client.video;

public final class VideoConfig {

    private VideoConfig() {}

    public static final int PX_PER_BLOCK = 128;

    public static final int MIN_W = 256;
    public static final int MIN_H = 144;

    public static final int MAX_W = 1920;
    public static final int MAX_H = 1080;

    public static final int TARGET_FPS = 60;

    public static final double PLANE_EPS = 0.012;
}
