package com.south13oy.qct.util;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;

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

    /**
     * 判断 provider 是否为 framework 系“数据菜单”（如 refurbished_furniture 的
     * workbench：必须经 FrameworkAPI.openMenuWithData 打开，携带 createSyncData 数据）。
     * <p>此类菜单直连 {@code player.openMenu(provider)} 会发送 null extraData，
     * 客户端在 MenuType.create -> IContainerFactory.create 解码 NPE 被踢线，
     * 因此任何“回退直接打开”的路径都必须先排除。反射探测 public 无参
     * {@code createSyncData()} 方法，框架未加载/无该方法一律视为普通菜单。
     */
    public static boolean isFrameworkDataMenu(MenuProvider provider) {
        try {
            for (Method m : provider.getClass().getMethods()) {
                if (m.getParameterCount() == 0
                        && m.getName().equals("createSyncData")
                        && m.getReturnType() != void.class) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 第三方实现细节差异或安全限制：按普通菜单处理
        }
        return false;
    }

    /**
     * 判定目标方块是否正被【其它玩家】独占使用。
     * <p>不直接编译期依赖第三方框架（如 refurbished_furniture 的 workbench），
     * 而是反射探测方块实体的“独占占用”信号：
     * <ul>
     *   <li>存在 public 无参 {@code boolean isOccupied()}（workbench 即此类）→ true 表示已被占用；</li>
     *   <li>存在 public 无参 {@code getUser()} 返回非 null 玩家 → 已被占用；若等于请求者自己视为未被他占；</li>
     *   <li>两者都没有 → 非独占逻辑方块，返回 false。</li>
     * </ul>
     * 反射失败或异常一律按“未占用”处理，不影响正常打开路径。
     */
    public static boolean isOccupiedByOther(Level level, BlockPos pos, BlockState state, Player self) {
        if (!(level.getBlockEntity(pos) instanceof BlockEntity be)) return false;
        Class<?> c = be.getClass();
        try {
            for (Method m : c.getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() == boolean.class && m.getName().equals("isOccupied")) {
                    Object r = m.invoke(be);
                    return Boolean.TRUE.equals(r);
                }
            }
            for (Method m : c.getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (Player.class.isAssignableFrom(m.getReturnType()) && m.getName().equals("getUser")) {
                    Object r = m.invoke(be);
                    return r instanceof Player p && !p.equals(self);
                }
            }
        } catch (Exception ignored) {
            // 第三方实现细节差异或安全限制：不阻断正常打开
        }
        return false;
    }
}
