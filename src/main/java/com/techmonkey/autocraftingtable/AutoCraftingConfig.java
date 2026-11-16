package com.techmonkey.autocraftingtable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings in config/auto_crafting_table.json. Crafting runs on the server, so on a multiplayer
 * server the server's copy of this file is the one that counts.
 */
public final class AutoCraftingConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AutoCraftingConfig instance = new AutoCraftingConfig();

    /** When true, a redstone signal pauses a terminal, the same way it locks a hopper. Terminals run on their own otherwise. */
    public boolean redstoneControl = true;
    /** When true, a terminal takes one redstone dust from the network for every CRAFTS_PER_DUST crafts. When false, no fuel. */
    public boolean requireFuel = true;
    /** When true, any item the recipe accepts works, like a vanilla crafting table (any planks make a chest). */
    public boolean mixedTypes = true;

    public static AutoCraftingConfig get() {
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(AutoCraftingTable.MOD_ID + ".json");
    }

    public static void load() {
        Path configFile = file();
        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile)) {
                AutoCraftingConfig loaded = GSON.fromJson(reader, AutoCraftingConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            } catch (IOException | RuntimeException exception) {
                AutoCraftingTable.LOGGER.warn("Could not read {}, using defaults", configFile, exception);
            }
        }
        instance.save();
    }

    public void save() {
        try (Writer writer = Files.newBufferedWriter(file())) {
            GSON.toJson(this, writer);
        } catch (IOException exception) {
            AutoCraftingTable.LOGGER.warn("Could not save {}", file(), exception);
        }
    }
}
