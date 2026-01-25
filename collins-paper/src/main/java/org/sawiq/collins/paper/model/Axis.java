package org.sawiq.collins.paper.model;

public enum Axis {
    XY((byte) 0),
    XZ((byte) 1),
    YZ((byte) 2);

    public final byte id;

    Axis(byte id) {
        this.id = id;
    }
}
