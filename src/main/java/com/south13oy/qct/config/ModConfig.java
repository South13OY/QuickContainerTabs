package com.south13oy.qct.config;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 共享配置（写入 config/qct-common.toml）。
 */
public class ModConfig {

    public static final ModConfigSpec SPEC;
    public static final Values VALUES;

    static {
        final Pair<Values, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Values::new);
        VALUES = pair.getLeft();
        SPEC = pair.getRight();
    }

    public static class Values {
        /** 玩家周围扫描半径（方块数），默认 4 = 生存模式手长（4.5 取整） */
        public final ModConfigSpec.IntValue scanRadius;
        /** 每页标签上限，默认 10 */
        public final ModConfigSpec.IntValue tabsPerPage;
        /** 是否在容器界面显示顶部容器标签 */
        public final ModConfigSpec.BooleanValue showContainerTabs;
        /** 是否在容器界面显示底部工作方块标签 */
        public final ModConfigSpec.BooleanValue showWorkblockTabs;
        /** 是否启用右侧图标设置槽 */
        public final ModConfigSpec.BooleanValue showIconSlot;
        /** 扫描刷新间隔（毫秒），默认 1000 */
        public final ModConfigSpec.IntValue scanIntervalMs;

        Values(ModConfigSpec.Builder builder) {
            builder.push("general");
            scanRadius = builder
                    .comment("玩家周围扫描半径（以玩家所在方块为中心的曼哈顿距离，单位：方块），默认 4 对应生存模式手长（4.5 取整）")
                    .defineInRange("scanRadius", 4, 1, 64);
            tabsPerPage = builder
                    .comment("每个标签区域每页最多显示的标签数量")
                    .defineInRange("tabsPerPage", 10, 1, 24);
            showContainerTabs = builder
                    .comment("是否在界面上方显示容器类标签")
                    .define("showContainerTabs", true);
            showWorkblockTabs = builder
                    .comment("是否在界面下方显示工作方块类标签")
                    .define("showWorkblockTabs", true);
            showIconSlot = builder
                    .comment("是否在界面右侧显示图标设置槽（左键用当前手持物设置图标，右键清除）")
                    .define("showIconSlot", true);
            scanIntervalMs = builder
                    .comment("附近方块扫描刷新间隔（毫秒）")
                    .defineInRange("scanIntervalMs", 1000, 200, 5000);
            builder.pop();
        }
    }

    public static void onLoad(final ModConfigEvent event) {
        // 配置变更时无需额外处理，读取处直接使用最新值
    }
}
