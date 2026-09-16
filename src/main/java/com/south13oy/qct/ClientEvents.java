package com.south13oy.qct;

import com.south13oy.qct.client.QCTKeys;
import com.south13oy.qct.ui.NearbyTabs;
import com.south13oy.qct.ui.SideConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 客户端事件订阅。
 *
 * 物理右键打开容器（如点箱子、熔炉、工作台）是进入相关界面最常见的方式，
 * 但客户端通过 OpenScreen 包构造的菜单内并不带 BlockEntity 引用，反射无法
 * 解析坐标。这里在右键方块事件发生时记下坐标，作为 QCT 判断"当前容器"
 * 的兜底来源（NearbyTabs.recordManualOpen）。
 */
@EventBusSubscriber(modid = QCTMod.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) {
            NearbyTabs.recordManualOpen(event.getPos());
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        if (!QCTKeys.OPEN_CONFIG.consumeClick()) return;
        Minecraft mc = Minecraft.getInstance();
        // 输入框聚焦时（聊天/书/命令输入等）不拦截 B 键输入；已在配置界面内不重复打开
        if (mc.screen != null && (mc.screen instanceof SideConfigScreen
                || mc.screen.getFocused() instanceof EditBox)) return;
        // 打开配置界面时把游标还原到屏幕居中位置。
        // 注意：grab 模式下 mouseHandler.xpos/ypos 保存的是物理像素中心（未除以 GUI 缩放），
        // 直接透传给 MouseHandlerMixin 会被当成逻辑坐标再乘 scale，光标会错位到窗口右下角。
        // 这里显式用 GUI 逻辑中心坐标发起还原请求。
        NearbyTabs.requestMouseRestore(mc.getWindow().getGuiScaledWidth() / 2.0,
                mc.getWindow().getGuiScaledHeight() / 2.0);
        mc.setScreen(new SideConfigScreen());
    }
}
