package com.south13oy.qct.mixin;

import com.south13oy.qct.ui.NearbyTabs;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 对 AbstractContainerScreen（覆盖背包、箱子、熔炉及各类工作方块界面）注入
 * 快速切换标签的渲染与点击处理。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    // 共享单例：必须 static，让所有 AbstractContainerScreen 实例共用同一 NearbyTabs。
    // 否则每次点击标签切换打开的新 screen 都会 new 一个独立实例，翻页位置
    // (containerPage/workPage) 被重置回第一页，current 坐标还原也失效。
    @Unique
    private static final NearbyTabs qct$nearbyTabs = new NearbyTabs();

    // 注入到 renderBackground 中 renderBg 调用之前：此时 renderTransparentBackground 已把背景画好，
    // 但原生界面纹理尚未绘制。这一层画【未选中】标签（底部 4px 会被随后绘制的原生 GUI 纹理
    // 覆盖 → 复现原版创造“未选中标签根部融入界面边框”的融合观感）。
    // 注意：1.21.x 中 renderBg 由 renderBackground 调用，render 方法体内并无 renderBg。
    @Inject(method = "renderBackground",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderBg(Lnet/minecraft/client/gui/GuiGraphics;FII)V",
                    shift = At.Shift.BEFORE))
    private void qct$renderUnder(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        qct$nearbyTabs.renderUnderLayer((AbstractContainerScreen) (Object) this, guiGraphics, mouseX, mouseY);
    }

    // 注入到 renderBackground 中 renderBg 调用之后（原生 GUI 背景纹理已全部画完）：
    // 这一层画【选中标签】+ 翻页按钮 + 右侧图标槽。与原版创造完全一致——选中标签
    // 绘制在 GUI 背景之上，底部 4px 亮色根部直接盖在界面顶边框上，形成“标签从 GUI
    // 里长出”的嵌入衔接，而不是被背景压暗的悬浮重叠。
    @Inject(method = "renderBackground",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderBg(Lnet/minecraft/client/gui/GuiGraphics;FII)V",
                    shift = At.Shift.AFTER))
    private void qct$renderTop(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        qct$nearbyTabs.renderTopLayer((AbstractContainerScreen) (Object) this, guiGraphics, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void qct$mouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (qct$nearbyTabs.mouseClicked((AbstractContainerScreen) (Object) this, mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }

    // 关键配套：按下已被本模组消费后，松开也必须在本模组区域被吞掉。
    // 否则原版 mouseReleased 会在“游标吸附物品非空 + 松开点在窗口外(slot=-999)”
    // 时把 carried 整叠丢出世界——正是“点击图标槽后物品被丢出”的根因。
    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void qct$mouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (qct$nearbyTabs.overInteractiveArea((AbstractContainerScreen) (Object) this, mouseX, mouseY)) {
            cir.setReturnValue(true);
        }
    }

    // 界面关闭（Esc / 关闭容器 / 打开背包均触发 Screen.removed）：翻页复位回第一页，
    // 修复“翻页后关闭、重开界面仍残留上次页码”的缓存 bug。标签点击切换新容器时
    // 由 NearbyTabs.requestOpen 置豁免标记，切换不重置页码。
    @Inject(method = "removed", at = @At("HEAD"))
    private void qct$removed(CallbackInfo ci) {
        qct$nearbyTabs.onScreenClosed();
    }
}
