package com.south13oy.qct.util;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 统一解析"该坐标上可打开的菜单"，让客户端扫描（NearbyEntry.scan）与服务端切换
 * （ModNetworking.openNearbyMenu）行为一致。
 * <p>常规方块走 {@code getMenuProvider}；末影箱是唯一例外——EnderChestBlock 未实现
 * getMenuProvider（菜单在其 useWithoutItem 中直接构造绑定玩家末影箱库存），若不加兜底
 * 扫描会把它跳过（上方容器数因此少 1 个），点击标签切换也会因 provider==null 打开失败。
 */
public final class MenuBridge {
    private MenuBridge() {
    }

    public static MenuProvider providerFor(Level level, BlockPos pos, BlockState state) {
        MenuProvider provider = state.getMenuProvider(level, pos);
        if (provider != null) return provider;
        if (state.getBlock() == Blocks.ENDER_CHEST) {
            return new SimpleMenuProvider(
                    (containerId, inv, player) ->
                            ChestMenu.threeRows(containerId, inv, player.getEnderChestInventory()),
                    Component.translatable("container.enderchest"));
        }
        return null;
    }
}
