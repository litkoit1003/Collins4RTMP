package org.sawiq.collins.paper.net;

public final class CollinsProtocol {
    private CollinsProtocol() {}

    public static final String NAMESPACE = "collins";
    public static final String PATH_MAIN = "main";

    public static final int PROTOCOL_VERSION = 2;

    // S2C (сервер -> клиент)
    public static final byte MSG_SYNC = 1;

    // C2S (клиент -> сервер)
    public static final byte MSG_VIDEO_ENDED = 2; // клиент сообщает что видео закончилось
    public static final byte MSG_VIDEO_DURATION = 3; // клиент сообщает длительность видео
}
