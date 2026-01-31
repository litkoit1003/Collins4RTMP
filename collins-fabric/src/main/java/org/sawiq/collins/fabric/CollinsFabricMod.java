package org.sawiq.collins.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.sawiq.collins.fabric.net.CollinsMainC2SPayload;
import org.sawiq.collins.fabric.net.CollinsMainS2CPayload;

public final class CollinsFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // S2C - сервер -> клиент
        PayloadTypeRegistry.playS2C().register(CollinsMainS2CPayload.ID, CollinsMainS2CPayload.CODEC);
        // C2S - клиент -> сервер
        PayloadTypeRegistry.playC2S().register(CollinsMainC2SPayload.ID, CollinsMainC2SPayload.CODEC);
    }
}
