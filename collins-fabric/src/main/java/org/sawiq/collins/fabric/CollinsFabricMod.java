package org.sawiq.collins.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.sawiq.collins.fabric.net.CollinsMainS2CPayload;

public final class CollinsFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(CollinsMainS2CPayload.ID, CollinsMainS2CPayload.CODEC);
    }
}
