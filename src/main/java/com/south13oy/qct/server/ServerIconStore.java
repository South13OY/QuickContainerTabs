package com.south13oy.qct.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 服务端权威图标存储（全服同步）。
 * key = "维度: x,y,z" -> ItemStack
 * 持久化到 config/qct_icons_server.json。
 * 仅服务端逻辑可调用（不依赖 Minecraft 客户端类）。
 * <p>单机升级迁移：server 文件不存在时，尝试读取旧版客户端本地图标文件
 * （config/qct_icons.json）作为初始数据，并立即落盘为权威文件。
 */
public class ServerIconStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static boolean loaded = false;

    private ServerIconStore() {
    }

    private static Path serverFile() {
        return FMLPaths.CONFIGDIR.get().resolve("qct_icons_server.json");
    }

    private static String key(ResourceLocation dim, BlockPos pos) {
        return dim + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void ensureLoaded(RegistryAccess access) {
        if (loaded) return;
        loaded = true;
        Path f = serverFile();
        if (!Files.exists(f)) {
            // 旧版客户端本地图标文件 -> 迁移为服务端权威数据
            Path legacy = FMLPaths.CONFIGDIR.get().resolve("qct_icons.json");
            if (Files.exists(legacy)) f = legacy;
        }
        if (!Files.exists(f)) return;
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(text, JsonObject.class);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                try {
                    ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(
                                    RegistryOps.create(JsonOps.INSTANCE, access), e.getValue())
                            .result().orElse(ItemStack.EMPTY);
                    if (!stack.isEmpty()) {
                        ICONS.put(e.getKey(), stack);
                    }
                } catch (Exception ex) {
                    LOGGER.warn("Skip invalid server icon {}: {}", e.getKey(), ex.toString());
                }
            }
            // 若从旧文件迁移，立即落盘为新权威文件
            if (f != serverFile()) save(access);
        } catch (IOException ex) {
            LOGGER.warn("Failed to load server icons: {}", ex.toString());
        }
    }

    private static void save(RegistryAccess access) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, ItemStack> e : ICONS.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            try {
                JsonElement je = ItemStack.OPTIONAL_CODEC.encodeStart(
                                RegistryOps.create(JsonOps.INSTANCE, access), e.getValue())
                        .result().orElse(null);
                if (je != null) root.add(e.getKey(), je);
            } catch (Exception ex) {
                LOGGER.warn("Failed to save server icon {}: {}", e.getKey(), ex.toString());
            }
        }
        try {
            Files.writeString(serverFile(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("Failed to write server icons: {}", ex.toString());
        }
    }

    /** 查询图标；无则返回 EMPTY。 */
    public static ItemStack getIcon(RegistryAccess access, ResourceLocation dim, BlockPos pos) {
        ensureLoaded(access);
        return ICONS.getOrDefault(key(dim, pos), ItemStack.EMPTY);
    }

    /** 设置图标（EMPTY 即清除），立即落盘。 */
    public static void setIcon(RegistryAccess access, ResourceLocation dim, BlockPos pos, ItemStack stack) {
        ensureLoaded(access);
        if (stack == null || stack.isEmpty()) {
            ICONS.remove(key(dim, pos));
        } else {
            ICONS.put(key(dim, pos), stack.copyWithCount(1));
        }
        save(access);
    }

    /** 全量读取（登录同步用）。 */
    public static Map<String, ItemStack> getAll(RegistryAccess access) {
        ensureLoaded(access);
        return new HashMap<>(ICONS);
    }
}
