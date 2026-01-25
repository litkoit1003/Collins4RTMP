package org.sawiq.collins.fabric.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class CollinsModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return this::buildScreen;
    }

    private Screen buildScreen(Screen parent) {
        CollinsClientConfig cfg = CollinsClientConfig.get();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("Collins"));

        builder.setSavingRunnable(CollinsClientConfig::save);

        ConfigCategory general = builder.getOrCreateCategory(Text.literal("Video"));
        ConfigEntryBuilder eb = builder.entryBuilder();

        general.addEntry(eb.startIntSlider(Text.literal("Local volume"), cfg.localVolumePercent, 0, 100)
                .setDefaultValue(100)
                .setSaveConsumer(v -> cfg.localVolumePercent = v)
                .build());

        general.addEntry(eb.startBooleanToggle(Text.literal("Render video"), cfg.renderVideo)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.renderVideo = v)
                .build());

        general.addEntry(eb.startBooleanToggle(Text.literal("Actionbar timeline"), cfg.actionbarTimeline)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.actionbarTimeline = v)
                .build());

        return builder.build();
    }
}
