package com.south13oy.qct.icon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 容器自定义图标存储。
 * 持久化到 config/qct_icons.json
 * key = "维度: x,y,z" -> ItemStack
 */
public class IconStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static boolean loaded = false;

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("qct_icons.json");
    }

    private static String key(ResourceKey<Level> dim, BlockPos pos) {
        return dim.location() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void ensureLoaded(Minecraft mc) {
        if (loaded) return;
        loaded = true;
        if (mc == null) return;
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(text, JsonObject.class);
            if (root == null) return;
            RegistryAccess access = mc.level != null ? mc.level.registryAccess() : null;
            if (access == null) return;
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                try {
                    ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(
                                    RegistryOps.create(JsonOps.INSTANCE, access), e.getValue())
                            .result().orElse(ItemStack.EMPTY);
                    if (!stack.isEmpty()) {
                        ICONS.put(e.getKey(), stack);
                    }
                } catch (Exception ex) {
                    LOGGER.warn("Skip invalid icon {}: {}", e.getKey(), ex.toString());
                }
            }
        } catch (IOException ex) {
            LOGGER.warn("Failed to load icons: {}", ex.toString());
        }
    }

    private static void save(Minecraft mc) {
        if (mc == null) return;
        RegistryAccess access = mc.level != null ? mc.level.registryAccess() : null;
        if (access == null) return;
        JsonObject root = new JsonObject();
        for (Map.Entry<String, ItemStack> e : ICONS.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            try {
                JsonElement je = ItemStack.OPTIONAL_CODEC.encodeStart(
                                RegistryOps.create(JsonOps.INSTANCE, access), e.getValue())
                        .result().orElse(null);
                if (je != null) root.add(e.getKey(), je);
            } catch (Exception ex) {
                LOGGER.warn("Failed to save icon {}: {}", e.getKey(), ex.toString());
            }
        }
        try {
            Path f = file();
            Files.writeString(f, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("Failed to write icons: {}", ex.toString());
        }
    }

    /** 获取图标；无则返回 EMPTY。 */
    public static ItemStack getIcon(Minecraft mc, ResourceKey<Level> dim, BlockPos pos) {
        ensureLoaded(mc);
        return ICONS.getOrDefault(key(dim, pos), ItemStack.EMPTY);
    }

    /** 设置图标；传入 EMPTY 即清除。 */
    public static void setIcon(Minecraft mc, ResourceKey<Level> dim, BlockPos pos, ItemStack stack) {
        ensureLoaded(mc);
        if (stack == null || stack.isEmpty()) {
            ICONS.remove(key(dim, pos));
        } else {
            ICONS.put(key(dim, pos), stack.copyWithCount(1));
        }
        save(mc);
    }

    public static void clearCache() {
        loaded = false;
        ICONS.clear();
    }
}
