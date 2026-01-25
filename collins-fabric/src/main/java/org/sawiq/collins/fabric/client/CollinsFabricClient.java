package org.sawiq.collins.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import org.sawiq.collins.fabric.client.net.CollinsNet;
import org.sawiq.collins.fabric.client.video.VideoScreenRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.sawiq.collins.fabric.client.command.CollinsClientCommands;
import org.sawiq.collins.fabric.client.config.CollinsClientConfig;
import org.sawiq.collins.fabric.client.video.VideoScreenManager;

public final class CollinsFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CollinsClientConfig.get();
        CollinsNet.initClientReceiver();
        CollinsClientCommands.init();

        ClientTickEvents.END_CLIENT_TICK.register(VideoScreenManager::tick);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            VideoScreenManager.stopAll();
        });
    }
}
