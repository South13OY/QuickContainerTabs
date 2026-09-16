package com.south13oy.qct.mixin;

import com.south13oy.qct.ui.NearbyTabs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鼠标吸回中心的根因修复：
 * Minecraft 在打开 GUI（setScreen）时调用 MouseHandler.releaseMouse()，
 * 它先把内部 xpos/ypos 写为屏幕中心并对 GLFW 重新设置光标，这正是
 * 切换容器后鼠标被强制拉回屏幕中心的原因。
 *
 * 在 releaseMouse 的 TAIL 处，若 QCT 点击切换标签时登记了还原请求
 * （点击处 GUI 坐标），立即把光标与内部坐标复位到点击位置，一次到位。
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Shadow @Final
    private Minecraft minecraft;

    @Shadow
    private double xpos;

    @Shadow
    private double ypos;

    @Shadow
    private boolean mouseGrabbed;

    @Inject(method = "releaseMouse", at = @At("TAIL"))
    private void qct$restoreAfterRelease(CallbackInfo ci) {
        double[] t = NearbyTabs.takeMouseRestore();
        if (t == null) return;
        try {
            if (minecraft.getWindow() == null) return;
            double scale = minecraft.getWindow().getGuiScale();
            long win = minecraft.getWindow().getWindow();

            mouseGrabbed = false;
            xpos = t[0];
            ypos = t[1];
            GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            GLFW.glfwSetCursorPos(win, t[0] * scale, t[1] * scale);
        } catch (Exception ignored) {
        }
    }
}
