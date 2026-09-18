package com.south13oy.qct.ui;

import com.south13oy.qct.config.ModConfig;
import com.south13oy.qct.config.SideStore;
import com.south13oy.qct.icon.IconStore;
import com.south13oy.qct.network.ModNetworking;
import com.south13oy.qct.network.RequestOpenMenuPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 核心 UI：在 AbstractContainerScreen 上绘制上下两侧可点击的快速切换标签页，
 * 并处理点击、独立翻页、悬停名称与右侧图标设置槽。
 */
public class NearbyTabs {
    private static final Logger LOGGER = LogUtils.getLogger();

    // 原版创造标签页：宽 26 高 32，相邻步进 28（与 CreativeModeInventoryScreen 一致）
    public static final int TAB_W = 26;
    public static final int TAB_H = 32;
    public static final int TAB_GAP = 2;

    // 右上角工具按钮（收藏 / 左翻页 / 右翻页）：参考“一键背包整理”的物品栏右上角三小键布局
    public static final int BTN_W = 11;
    public static final int BTN_H = 11;
    public static final int BTN_GAP = 2;
    // 三按钮组相对 GUI 右上角向内收的距离：物品栏四周带一圈高光阴影边框纹理，
    // 按钮不能直接贴死右上角，内收几个像素避开高光阴影区（仿“一键背包整理”的留白观感）。
    private static final int BTN_INSET_X = 5;
    private static final int BTN_INSET_Y = 4;

    // 原版创造标签页 sprite（container/creative_inventory/tab_*_1..7）
    private static final ResourceLocation[] UNTOP = sprite7("tab_top_unselected");
    private static final ResourceLocation[] SELTOP = sprite7("tab_top_selected");
    private static final ResourceLocation[] UNBOT = sprite7("tab_bottom_unselected");
    private static final ResourceLocation[] SELBOT = sprite7("tab_bottom_selected");

    // 原版按钮纹理（Button 组件所用 sprite）——右上角三小键使用官方样式
    private static final ResourceLocation NAV_BTN = ResourceLocation.fromNamespaceAndPath("minecraft", "widget/button");
    private static final ResourceLocation NAV_BTN_DISABLED = ResourceLocation.fromNamespaceAndPath("minecraft", "widget/button_disabled");
    private static final ResourceLocation NAV_BTN_HOVER = ResourceLocation.fromNamespaceAndPath("minecraft", "widget/button_highlighted");

    private static ResourceLocation[] sprite7(String base) {
        ResourceLocation[] arr = new ResourceLocation[7];
        for (int i = 0; i < 7; i++) {
            arr[i] = ResourceLocation.fromNamespaceAndPath("minecraft",
                    "container/creative_inventory/" + base + "_" + (i + 1));
        }
        return arr;
    }

    private List<NearbyEntry> containers = List.of();
    private List<NearbyEntry> workblocks = List.of();
    private int containerPage = 0;
    private int workPage = 0;
    // 关闭界面时是否豁免页码复位：点击标签切换新容器时置 true —— 本次 removed
    // 是“新 screen 替换旧 screen”触发，属于切换而非关闭，页码应保留；
    // 用户 Esc / 关闭容器 / 打开背包时无此标记 → 复位回第一页。
    private boolean pageResetSuppressed = false;
    private long lastScan = 0L;
    // 5+1 收藏：上方方向（容器）与下方方向（工作方块）各一个收藏坐标。
    // 跨 screen 实例共享（static）：收藏行为与寻找行为由模组记住，重开 GUI 仍在；
    // 显示与否以“当前扫描列表是否包含该坐标”为准 —— 离开交互范围（超出扫描半径）自动隐藏，
    // 回到范围内自动再现；若离开期间收藏被替换，新坐标上位，旧收藏不再重现。
    private static volatile BlockPos favContainer = null;
    private static volatile BlockPos favWork = null;
    // 当前交互方向：纯背包未选中时翻页上下同步；选中上/下某容器后翻页仅控该侧。
    private static volatile boolean activeTop = true;
    // 切换目标坐标必须跨 screen 实例共享：点击标签会由 mixin 打开一个全新的
    // AbstractContainerScreen（各持有独立 NearbyTabs 实例），只有静态字段才能让
    // 新实例的 getCurrentContainerPos 兜底解析到切换目标，否则 current 解析为 null，
    // 导致选中高亮丢失、图标设置槽（个性化按键）不出现。
    private static volatile BlockPos pendingOpenPos = null;
    // 已警告过“当前容器不在扫描列表”的坐标，避免每帧刷日志
    private BlockPos lastWarnedNotInList = null;
    private final Map<BlockPos, ItemStack> defaultIconCache = new HashMap<>();
    // 最近一次鼠标按下是否已被本模组消费（图标槽/标签/按钮）。mouseReleased 判定时，
    // 只要按下已被消费，释放即一并吞掉（即使松开点恰好移出交互区 1px），避免 carried
    // 被原版 PICKUP@-999 丢出、或残留拖拽状态干扰后续点击（如右键重置图标）。
    private boolean pressConsumed = false;

    // 切换容器后还原鼠标：记录点击时 GUI 坐标，目标容器打开后把被吸回中心的光标复位。
    // 目标同时通过静态请求发给 MouseHandlerMixin.releaseMouse（根因拦截），
    // 供其 TAIL 处即时消费，避免被引擎固有行为拉回中心后再逐帧矫正的抖动。
    private double lastSwitchMouseX = 0.0;
    private double lastSwitchMouseY = 0.0;
    private long switchedAtMs = 0L;
    private boolean pendingRestoreMouse = false;

    // 当前容器坐标缓存：同一 screen 实例期间坐标保持不变，
    // 避免每帧反射解析导致“选中高亮闪烁 / 图标 key 抖动”。
    private AbstractContainerScreen<?> cachedCurrentScreen = null;
    private BlockPos cachedCurrentPos = null;
    private boolean cachedCurrentSet = false;

    // 跨 mixin 的鼠标还原请求（GUI 坐标）
    private static volatile boolean restorePending = false;
    private static volatile double restoreTargetX = 0.0;
    private static volatile double restoreTargetY = 0.0;

    // “当前容器坐标”的兜底来源：物理右键打开的方块坐标（客户端事件记录）。
    // 客户端由 OpenScreen 包构造的菜单（如 ChestMenu）内不含真 BlockEntity 引用，
    // 反射解析必然失败，因此用最近一次右键方块坐标兜底；与切换专用的
    // pendingOpenPos 以时间戳比较，谁最新谁胜出。
    private static volatile BlockPos lastRightClicked = null;
    private static volatile long manualAt = -1L;
    private static volatile long pendingAt = -1L;
    // 右键打开容器后强制下一次扫描立即执行（置顶即时性），扫完即清。
    private static volatile boolean manualOpenPending = false;

    /** 客户端右键方块时由事件回调记录（物理打开容器的主要途径）。 */
    public static void recordManualOpen(BlockPos pos) {
        lastRightClicked = pos;
        manualAt = System.currentTimeMillis();
        // 置顶即时性：右键打开容器的瞬间，扫描应立即可见“置顶结果”，而不是等下一个
        // 扫描周期（默认 1s）。置位强制重扫标志，下一次 renderCommon 的 scanIfNeeded
        // 立即忽略间隔重扫一次并清除。
        manualOpenPending = true;
    }

    /** 记录一次鼠标还原请求（点击切换标签时调用）。 */
    public static void requestMouseRestore(double x, double y) {
        restoreTargetX = x;
        restoreTargetY = y;
        restorePending = true;
    }

    /** 消费并清除还原请求；无请求返回 null。 */
    public static double[] takeMouseRestore() {
        if (!restorePending) return null;
        restorePending = false;
        return new double[]{restoreTargetX, restoreTargetY};
    }

    // ---------------- 反射字段辅助 ----------------
    private static Object fieldValue(Object obj, Class<?> type, int maxDepth, String... names) {
        Class<?> c = obj.getClass();
        for (int depth = 0; depth < maxDepth && c != null && c != Object.class; depth++, c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!type.isAssignableFrom(f.getType())) continue;
                for (String n : names) {
                    if (f.getName().equals(n)) {
                        try {
                            f.setAccessible(true);
                            return f.get(obj);
                        } catch (ReflectiveOperationException ex) {
                            return null;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static int intField(Object obj, String... names) {
        Object v = fieldValue(obj, int.class, 4, names);
        return v instanceof Integer i ? i : Integer.MIN_VALUE;
    }

    /** 反射获取当前菜单对应的方块坐标（绕过内部字段名差异）。
     * 依次尝试：值为 BlockEntity 的字段 → 值为 BlockEntity 的 Container 字段
     * → GlobalPos → ContainerLevelAccess。 */
    private static BlockPos menuContainerPos(AbstractContainerMenu menu) {
        if (menu == null) return null;
        Class<?> c = menu.getClass();
        for (int depth = 0; depth < 4 && c != null && c != Object.class; depth++, c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(menu);
                    Class<?> ft = f.getType();
                    BlockPos found = null;
                    if (BlockEntity.class.isAssignableFrom(ft)) {
                        if (v instanceof BlockEntity be) found = be.getBlockPos();
                    } else if (Container.class.isAssignableFrom(ft) && v instanceof BlockEntity be) {
                        found = be.getBlockPos();
                    } else if (ft == net.minecraft.core.GlobalPos.class && v instanceof net.minecraft.core.GlobalPos gp) {
                        found = gp.pos();
                    } else if (v instanceof net.minecraft.world.inventory.ContainerLevelAccess cla) {
                        found = cla.evaluate((lvl, pos) -> pos).orElse(null);
                    }
                    if (found != null) return found;
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }

    // ---------------- 扫描 ----------------
    private void scanIfNeeded(Minecraft mc) {
        long now = System.currentTimeMillis();
        long interval = ModConfig.VALUES.scanIntervalMs.get();
        // 右键打开容器后强制立即重扫（置顶即时性），本次忽略扫描间隔。
        if (manualOpenPending) {
            manualOpenPending = false;
        } else if (now - lastScan < interval) {
            return;
        }
        lastScan = now;
        if (mc.level == null || mc.player == null) return;
        int radius = ModConfig.VALUES.scanRadius.get();
        BlockPos center = mc.player.blockPosition();
        List<NearbyEntry> all = NearbyEntry.scan(mc.level, center, radius);
        List<NearbyEntry> cs = new ArrayList<>();
        List<NearbyEntry> wb = new ArrayList<>();
        for (NearbyEntry e : all) {
            // 配置界面中“X”删除的类型：不再被扫描
            String regId = BuiltInRegistries.BLOCK.getKey(e.state().getBlock()).toString();
            if (SideStore.isExcluded(regId) || SideStore.isDefaultExcluded(regId)) continue;
            // 方向：手动设置 > 标准配置（以用户清单为准） > 默认分类兜底（容器=上方/工作方块=下方）
            String side = SideStore.getDirection(regId);
            boolean top;
            if (side != null) {
                top = side.equals("top");
            } else {
                Boolean stdTop = SideStore.defaultTopOf(regId);
                top = stdTop != null ? stdTop : e.isContainer();
            }
            if (top) cs.add(e);
            else wb.add(e);
        }
        // 排序置顶：仅“最近一次物理右键打开的容器”（lastRightClicked）参与置顶，且只在
        // 容器 UI 会话期间生效（打开纯背包 = 上一会话已结束，恢复纯优先级排序，上次右键的
        // 容器不再占第一）。标签切换目标（pendingOpenPos）不参与置顶 —— 通过标签切到别的
        // 工作方块时不会再把新界面顶到第一位，第一位保持为本次右键打开的容器，直到关闭
        // 界面后再次右键其它的工作方块才更新。
        BlockPos pin = (mc.screen instanceof InventoryScreen) ? null : lastRightClicked;
        Comparator<NearbyEntry> order = Comparator
                .comparingInt((NearbyEntry e) -> pin != null && e.pos().equals(pin) ? -1 : 0)
                .thenComparingInt(e -> SideStore.getPriority(
                        BuiltInRegistries.BLOCK.getKey(e.state().getBlock()).toString()))
                .thenComparingDouble(NearbyEntry::distSq);
        cs.sort(order);
        wb.sort(order);
        containers = cs;
        workblocks = wb;
        LOGGER.debug("QCT lists updated: center={} containers={} workblocks={}",
                center.toShortString(), cs.size(), wb.size());
        defaultIconCache.values().removeIf(ItemStack::isEmpty);
    }

    private ItemStack defaultIcon(NearbyEntry entry) {
        return defaultIconCache.computeIfAbsent(entry.pos(), p -> {
            BlockState st = entry.state();
            ItemStack s = st.getBlock().asItem() != Blocks.AIR.asItem()
                    ? new ItemStack(st.getBlock().asItem())
                    : ItemStack.EMPTY;
            return s;
        });
    }

    private ItemStack pageItemIcon(Minecraft mc, NearbyEntry entry) {
        ItemStack custom = IconStore.getIcon(mc, mc.level.dimension(), entry.pos());
        if (!custom.isEmpty()) return custom;
        return defaultIcon(entry);
    }

    // ---------------- 公共入口（分层） ----------------
    // 渲染拆成两层注入，复现原版创造的“未选中标签被背景盖、选中标签盖在背景上”的融合分层：
    //   Under 层（renderBg 之前）：未选中标签 —— 底部 4px 被随后绘制的原生 GUI 纹理覆盖，根部淡入边框；
    //   Top 层（renderBg 之后）：选中标签 + 翻页按钮 + 图标槽 —— 完整叠在 GUI 背景之上，亮色根部衔接顶边框。
    public void renderUnderLayer(AbstractContainerScreen<?> screen, GuiGraphics gg, int mouseX, int mouseY) {
        renderCommon(screen, gg, mouseX, mouseY, true, false);
    }

    public void renderTopLayer(AbstractContainerScreen<?> screen, GuiGraphics gg, int mouseX, int mouseY) {
        renderCommon(screen, gg, mouseX, mouseY, false, true);
    }

    private void renderCommon(AbstractContainerScreen<?> screen, GuiGraphics gg,
                              int mouseX, int mouseY, boolean under, boolean top) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // 创造模式：与原版创造物品栏的标签图层冲突，整套 UI 直接关闭
        if (mc.player.getAbilities().instabuild) return;

        // 切换容器后把被吸到屏幕中心的光标还原回点击位置（仅 Under 层做一次）
        if (under) restoreMouseAfterSwitch(mc);

        int left = intField(screen, "leftPos", "guiLeft");
        int topPos = intField(screen, "topPos", "guiTop");
        int iw = intField(screen, "imageWidth");
        int ih = intField(screen, "imageHeight");
        int width = intField(screen, "width");
        int height = intField(screen, "height");
        if (left == Integer.MIN_VALUE || topPos == Integer.MIN_VALUE || iw == Integer.MIN_VALUE) return;

        scanIfNeeded(mc);

        ModConfig.Values cfg = ModConfig.VALUES;
        BlockPos current = getCurrentContainerPos(screen);

        // 定位日志：当前容器若不在扫描列表，标签将无法高亮
        if (current != null && !current.equals(lastWarnedNotInList)
                && !containsPos(containers, current) && !containsPos(workblocks, current)) {
            LOGGER.warn("QCT current={} 不在扫描列表（containers={} workblocks={}），标签无法高亮",
                    current.toShortString(), containers.size(), workblocks.size());
            lastWarnedNotInList = current;
        }

        // 两个区域都无可切换方块（或均被关闭）时，提示用户 mod 已生效（只画一次，防重影）
        boolean hasContainer = cfg.showContainerTabs.get() && !containers.isEmpty();
        boolean hasWork = cfg.showWorkblockTabs.get() && !workblocks.isEmpty();
        if (under && !hasContainer && !hasWork) {
            Component hint = Component.literal("便捷容器标签：周围 "
                    + cfg.scanRadius.get() + " 格无容器/工作方块，请靠近箱子或工作台");
            gg.drawString(mc.font, hint, 4, 4, 0xFFAAAAAA);
        }

        // 上方容器标签（原版创造标签页造型）
        if (hasContainer) {
            int y = Math.max(2, topPos - TAB_H + 4);
            if (y + TAB_H < height) {
                renderTabRow(screen, gg, mouseX, mouseY, mc, left, y, iw,
                        containers, containerPage, current, true, under, top, favContainer);
            }
        }
        // 下方工作方块标签
        if (hasWork && cfg.showWorkblockTabs.get()) {
            int y = topPos + ih - 4;
            if (y + TAB_H < height) {
                renderTabRow(screen, gg, mouseX, mouseY, mc, left, y, iw,
                        workblocks, workPage, current, false, under, top, favWork);
            }
        }
        // 右上角三按钮（收藏 / 左翻 / 右翻）：仅 Top 层绘制，跟随当前交互方向
        if (top && (hasContainer || hasWork)) {
            int bSize = activeTop ? containers.size() : workblocks.size();
            int bPage = activeTop ? containerPage : workPage;
            boolean favActive = current != null
                    && (current.equals(favContainer) || current.equals(favWork));
            drawTopButtons(gg, left, topPos, iw, bSize, bPage, mc, mouseX, mouseY, favActive);
        }
        // 右侧图标设置槽：仅在 Top 层、且当前确实选中了某一容器时才绘制。
        // 纯背包（无容器选中，current==null）不画，杜绝上次容器的图标残留。
        if (top && cfg.showIconSlot.get() && current != null) {
            drawIconSlot(screen, gg, mouseX, mouseY, mc, left, topPos, iw, ih, current);
        }
    }

    /** 单行标签区：Under 层画未选中标签，Top 层画选中标签（含收藏槽）。 */
    private void renderTabRow(AbstractContainerScreen<?> screen, GuiGraphics gg, int mouseX, int mouseY,
                              Minecraft mc, int left, int y, int iw,
                              List<NearbyEntry> list, int page, BlockPos current, boolean topSide,
                              boolean under, boolean topLayer, BlockPos fav) {
        drawTabAreaBase(screen, gg, mouseX, mouseY, mc, left, y, iw,
                list, page, current, topSide, topLayer, fav);
    }

    /** 画一个标签格（sprite + 图标 + 悬停提示），基础标签与收藏槽共用。 */
    private void drawTabBox(GuiGraphics gg, int tx, int ty, boolean topSide, boolean isCurrent,
                            int spriteIdx, Minecraft mc, Font font, ItemStack icon,
                            boolean hover, int mouseX, int mouseY, Component name) {
        // 原版创造标签页造型：上方容器用 tab_top_*，下方工作方块用 tab_bottom_*，
        // 选中态切到 *_selected_*（高亮完全由原版 sprite 表达，不加额外 fill），
        // 外观按列取模避免都是同一个造型。
        ResourceLocation[] arr = topSide
                ? (isCurrent ? SELTOP : UNTOP)
                : (isCurrent ? SELBOT : UNBOT);
        gg.blitSprite(arr[spriteIdx % arr.length], tx, ty, TAB_W, TAB_H);
        // 图标偏移对齐原版：上方 tab 偏下、下方 tab 偏上
        int iconX = tx + 5;
        int iconY = ty + (topSide ? 9 : 7);
        if (!icon.isEmpty()) {
            gg.renderItem(icon, iconX, iconY);
        }
        if (hover && name != null) {
            gg.renderTooltip(font, name, mouseX, mouseY);
        }
    }

    /** 绘制一行标签：基础标签按页填（selectedOnly=true 只画选中标签；false 只画未选中标签）
     *  收藏槽固定在行尾（不随翻页）。 */
    private void drawTabAreaBase(AbstractContainerScreen<?> screen, GuiGraphics gg, int mouseX, int mouseY,
                                 Minecraft mc, int left, int y, int iw,
                                 List<NearbyEntry> list, int page, BlockPos current, boolean topSide,
                                 boolean selectedOnly, BlockPos fav) {
        int pp = perPage(iw);
        int size = list.size();
        boolean favVisible = fav != null && containsPos(list, fav);
        Row row = layoutRow(left, iw, size, favVisible);
        Font font = mc.font;
        int start = page * pp;
        int base = Math.min(pp, size);
        for (int i = 0; i < base; i++) {
            int idx = start + i;
            if (idx >= size) break;
            // 单行：第二页在同一行位置整行替换
            int tx = row.startX + i * (TAB_W + TAB_GAP);
            int ty = y;
            NearbyEntry e = list.get(idx);
            boolean hover = mouseX >= tx && mouseX < tx + TAB_W && mouseY >= ty && mouseY < ty + TAB_H;
            boolean isCurrent = e.pos().equals(current);
            // 本层只画对应角色：选中标签只在 Top 层画，未选中只在 Under 层画
            if (isCurrent != selectedOnly) continue;
            drawTabBox(gg, tx, ty, topSide, isCurrent, start + i, mc, font,
                    pageItemIcon(mc, e), hover, mouseX, mouseY, e.provider().getDisplayName());
        }
        // 收藏槽：本页基础位之后固定一格，收藏不被翻页翻转
        if (favVisible) {
            NearbyEntry fe = null;
            for (NearbyEntry e : list) {
                if (e.pos().equals(fav)) { fe = e; break; }
            }
            if (fe != null) {
                int fx = row.favX;
                boolean fhover = mouseX >= fx && mouseX < fx + TAB_W && mouseY >= y && mouseY < y + TAB_H;
                boolean isCurrent = fav.equals(current);
                if (isCurrent == selectedOnly) {
                    drawTabBox(gg, fx, y, topSide, isCurrent, 6, mc, font,
                            pageItemIcon(mc, fe), fhover, mouseX, mouseY, fe.provider().getDisplayName());
                }
            }
        }
    }

    /** 右上角三按钮整组：收藏 / 左翻页 / 右翻页（参考“一键背包整理”布局）。 */
    private void drawTopButtons(GuiGraphics gg, int left, int top, int iw, int size, int page,
                                Minecraft mc, int mouseX, int mouseY, boolean favActive) {
        int x0 = topButtonsX0(left, iw);
        int y = top + BTN_INSET_Y;
        // 按钮1：收藏（★，当前聚焦容器已被收藏时亮色；再点一次取消收藏）
        drawSmallButton(gg, x0, y, "★", true, favActive, mouseX, mouseY,
                Component.literal(favActive ? "取消收藏" : "收藏当前容器"));
        // 按钮2/3：左右翻页（仅控制当前交互方向）
        boolean canPrev = page > 0;
        boolean canNext = page < totalPages(size, iw) - 1;
        drawSmallButton(gg, x0 + BTN_W + BTN_GAP, y, "‹", canPrev, false, mouseX, mouseY,
                Component.literal("上一页"));
        drawSmallButton(gg, x0 + 2 * (BTN_W + BTN_GAP), y, "›", canNext, false, mouseX, mouseY,
                Component.literal("下一页"));
    }

    /** 原版按钮外观的小工具按钮（widget/button sprite + 居中字形）。 */
    private void drawSmallButton(GuiGraphics gg, int x, int y, String glyph,
                                 boolean enabled, boolean lit, int mouseX, int mouseY, Component tip) {
        boolean hover = hitRect(mouseX, mouseY, x, y, BTN_W, BTN_H);
        ResourceLocation sp = enabled
                ? (hover ? NAV_BTN_HOVER : NAV_BTN)
                : NAV_BTN_DISABLED;
        gg.blitSprite(sp, x, y, BTN_W, BTN_H);
        int color = !enabled ? 0xFF909090 : (lit ? 0xFFFFC000 : 0xFFFFFFFF);
        gg.drawCenteredString(Minecraft.getInstance().font, glyph, x + BTN_W / 2, y + BTN_H / 2 - 4, color);
        if (hover && tip != null) {
            gg.renderTooltip(Minecraft.getInstance().font, tip, mouseX, mouseY);
        }
    }

    /** 切换容器后，把被 Minecraft 重置到屏幕中心的光标还原到点击位置。
     * <p>还原必须是一次性的、尽快释放占用：光标一旦贴近目标就立即解除，
     * 后续完全不干预用户移动；仅在切屏后极短的 400ms 窗口内做一次强制补偿
     * （兜底 releaseMouse 拦截失效的情况），写一次即解除，超时立刻放弃。
     * 旧实现只要光标偏离目标就持续回拉、且要等 2s 超时才放手，
     * 直接造成“切换后鼠标被锁死两三秒”的观感。 */
    private void restoreMouseAfterSwitch(Minecraft mc) {
        if (!pendingRestoreMouse) return;
        if (mc.mouseHandler == null || mc.getWindow() == null) {
            pendingRestoreMouse = false;
            return;
        }
        try {
            MouseHandler mh = mc.mouseHandler;
            Field fx = MouseHandler.class.getDeclaredField("xpos");
            Field fy = MouseHandler.class.getDeclaredField("ypos");
            Field fg = MouseHandler.class.getDeclaredField("mouseGrabbed");
            fx.setAccessible(true);
            fy.setAccessible(true);
            fg.setAccessible(true);
            double cx = (Double) fx.get(mh);
            double cy = (Double) fy.get(mh);
            boolean grabbed = (Boolean) fg.get(mh);
            double targetX = lastSwitchMouseX;
            double targetY = lastSwitchMouseY;

            // 光标已贴近目标：一次性对准（releaseMouse 拦截通常已把 xpos/ypos
            // 写回点击位置），随即解除占用，之后不再干预，用户可自由移动。
            if (Math.abs(cx - targetX) < 1.0 && Math.abs(cy - targetY) < 1.0) {
                fx.set(mh, targetX);
                fy.set(mh, targetY);
                pendingRestoreMouse = false;
                return;
            }
            // 切屏后极短窗口内的强制补偿；无论是否写入成功都立即解除占用，
            // 绝不把鼠标持续锁在目标位置等待超时。
            long elapsed = System.currentTimeMillis() - switchedAtMs;
            if (elapsed < 400L) {
                if (grabbed) {
                    fg.set(mh, false);
                    GLFW.glfwSetInputMode(mc.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
                }
                fx.set(mh, targetX);
                fy.set(mh, targetY);
                double scale = mc.getWindow().getGuiScale();
                long win = mc.getWindow().getWindow();
                GLFW.glfwSetCursorPos(win, targetX * scale, targetY * scale);
            }
            pendingRestoreMouse = false;
        } catch (Exception ex) {
            pendingRestoreMouse = false;
        }
    }

    /** 鼠标点击处理，返回 true 表示已消费。 */
    public boolean mouseClicked(AbstractContainerScreen<?> screen, double mx, double my, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        // 创造模式：与原版创造物品栏标签冲突，不响应本模组 UI 点击
        if (mc.player.getAbilities().instabuild) return false;
        pressConsumed = false;

        int left = intField(screen, "leftPos", "guiLeft");
        int top = intField(screen, "topPos", "guiTop");
        int iw = intField(screen, "imageWidth");
        int ih = intField(screen, "imageHeight");
        if (left == Integer.MIN_VALUE) return false;

        scanIfNeeded(mc);
        ModConfig.Values cfg = ModConfig.VALUES;
        BlockPos current = getCurrentContainerPos(screen);
        updateActiveSide(current);

        // 右上角三按钮（收藏 / 左翻页 / 右翻页）：控制当前交互方向，优先于标签区
        if (cfg.showContainerTabs.get() || cfg.showWorkblockTabs.get()) {
            int by = top + BTN_INSET_Y;
            int bx0 = topButtonsX0(left, iw);
            if (hitRect(mx, my, bx0, by, BTN_W, BTN_H)) {
                pressConsumed = true;
                if (button == 0) toggleFavorite(current);
                return true;
            }
            if (hitRect(mx, my, bx0 + BTN_W + BTN_GAP, by, BTN_W, BTN_H)) {
                pressConsumed = true;
                if (button == 0) pageBoth(-1, left, iw, current);
                return true;
            }
            if (hitRect(mx, my, bx0 + 2 * (BTN_W + BTN_GAP), by, BTN_W, BTN_H)) {
                pressConsumed = true;
                if (button == 0) pageBoth(+1, left, iw, current);
                return true;
            }
        }

        // 顶部容器标签区（含收藏槽）
        if (cfg.showContainerTabs.get() && !containers.isEmpty()) {
            int y = Math.max(2, top - TAB_H + 4);
            if (handleTabAreaClick(screen, mx, my, button, left, y, iw,
                    containers, containerPage, favContainer, true, mc)) {
                pressConsumed = true;
                return true;
            }
        }
        // 底部工作方块标签区（含收藏槽）
        if (cfg.showWorkblockTabs.get() && !workblocks.isEmpty()) {
            int y = top + ih - 4;
            if (handleTabAreaClick(screen, mx, my, button, left, y, iw,
                    workblocks, workPage, favWork, false, mc)) {
                pressConsumed = true;
                return true;
            }
        }
        // 图标设置槽：按几何区域命中判定，即使 current 暂未解析也要吞掉点击，
        // 避免点击穿透到原版容器槽位触发“放置物品”动画/丢物品。
        if (cfg.showIconSlot.get()) {
            int slot = 18;
            int sx = left + iw + 8;
            int sy = top + ih / 2 - slot / 2;
            boolean hit = mx >= sx && mx < sx + slot && my >= sy && my < sy + slot;
            if (hit) {
                pressConsumed = true;
                if (current != null) {
                    return handleIconSlotClick(mx, my, button, mc, left, top, iw, ih, current);
                }
                // current 未知但点中图标槽区域：仅消费点击，不改动任何图标
                return true;
            }
        }
        return false;
    }

    /** 坐标是否落在本模组的可交互区域（标签区 / 翻页按钮 / 图标设置槽）。
     * <p>Mixin 在 mouseReleased 的 HEAD 调用：鼠标按下已被本模组消费
     * （例如点中图标槽/标签）后，松开事件也必须被吞掉。否则原版
     * AbstractContainerScreen.mouseReleased 会执行到
     * {@code else if (!menu.getCarried().isEmpty())} 分支，把“游标吸附的物品”
     * 以 ClickType.PICKUP + slot=-999（窗口外）整叠丢出世界——这就是
     * 点击图标槽后 carried 物品被丢出的根因（丢出发生在 release，不在 click）。 */
    public boolean overInteractiveArea(AbstractContainerScreen<?> screen, double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        if (mc.player.getAbilities().instabuild) return false;
        // 若本次按下已被本模组消费，释放一律按“交互区内”处理并清除标记：
        // 保证“按下在图标槽/标签/按钮、松开时鼠标恰好移出 1px”也不触发原版
        // mouseReleased 的 carried 丢出（PICKUP@-999），杜绝右键重置等操作被副作用干扰。
        boolean consumedPress = pressConsumed;
        pressConsumed = false;

        int left = intField(screen, "leftPos", "guiLeft");
        int top = intField(screen, "topPos", "guiTop");
        int iw = intField(screen, "imageWidth");
        int ih = intField(screen, "imageHeight");
        if (left == Integer.MIN_VALUE || top == Integer.MIN_VALUE || iw == Integer.MIN_VALUE) return false;

        ModConfig.Values cfg = ModConfig.VALUES;
        // 右上角三按钮（收藏 / 左翻 / 右翻）：几何命中即视为交互区，防止点击穿透
        if ((cfg.showContainerTabs.get() && !containers.isEmpty())
                || (cfg.showWorkblockTabs.get() && !workblocks.isEmpty())) {
            int bx0 = topButtonsX0(left, iw);
            if (hitRect(mx, my, bx0, top + BTN_INSET_Y, 3 * BTN_W + 2 * BTN_GAP, BTN_H)) return true;
        }
        // 顶部容器标签区（含收藏槽，按实际居中布局精确判定）
        if (cfg.showContainerTabs.get() && !containers.isEmpty()) {
            int y = Math.max(2, top - TAB_H + 4);
            if (inTabRow(mx, my, left, y, iw, containers, containerPage, favContainer)) return true;
        }
        // 底部工作方块标签区（含收藏槽）
        if (cfg.showWorkblockTabs.get() && !workblocks.isEmpty()) {
            int y = top + ih - 4;
            if (inTabRow(mx, my, left, y, iw, workblocks, workPage, favWork)) return true;
        }
        // 图标设置槽
        if (cfg.showIconSlot.get()) {
            int slot = 18;
            int sx = left + iw + 8;
            int sy = top + ih / 2 - slot / 2;
            if (mx >= sx && mx < sx + slot && my >= sy && my < sy + slot) return true;
        }
        // 未命中任何几何区域时，若本次按下已被本模组消费，仍按“交互区内”处理（吞掉释放）
        return consumedPress;
    }

    /** 整行标签区（基础标签格 + 收藏槽）的矩形判定，使用与点击一致的实际居中布局。 */
    private static boolean inTabRow(double mx, double my, int left, int y, int iw,
                                    List<NearbyEntry> list, int page, BlockPos fav) {
        int pp = perPage(iw);
        int size = list.size();
        boolean favVisible = fav != null && containsPos(list, fav);
        Row row = layoutRow(left, iw, size, favVisible);
        int base = Math.min(pp, size);
        int start = page * pp;
        if (base > 0 && start < size) {
            int shown = Math.min(base, size - start);
            double x0 = row.startX;
            double x1 = row.startX + shown * (TAB_W + TAB_GAP) - TAB_GAP + TAB_W;
            if (mx >= x0 && mx < x1 && my >= y && my < y + TAB_H) return true;
        }
        if (favVisible) {
            double fx = row.favX;
            if (mx >= fx && mx < fx + TAB_W && my >= y && my < y + TAB_H) return true;
        }
        return false;
    }

    // ---------------- 当前菜单坐标 ----------------
    private BlockPos getCurrentContainerPos(AbstractContainerScreen<?> screen) {
        // 同一 screen 实例内缓存结果，保证选中高亮与图标 key 稳定
        if (cachedCurrentScreen == screen && cachedCurrentSet) {
            return cachedCurrentPos;
        }
        // 纯背包（按 E 打开、未选中任何容器）强制视为“无当前容器”：
        // 不落入下方的静态兜底坐标（lastRightClicked / pendingOpenPos），否则关闭容器后
        // 再开纯背包，兜底坐标仍指向上次容器 → 图标槽残留上次修改的图标。
        if (screen instanceof InventoryScreen) {
            cachedCurrentScreen = screen;
            cachedCurrentPos = null;
            cachedCurrentSet = true;
            return null;
        }
        BlockPos p = null;
        Object menu = fieldValue(screen, AbstractContainerMenu.class, 6, "menu");
        if (menu instanceof AbstractContainerMenu m) {
            p = menuContainerPos(m);
        }
        if (p == null) {
            // 反射失败（绝大多数客户端菜单，如 ChestMenu）→ 取“最近一次打开来源”：
            // 物理右键打开 → lastRightClicked；点击本模组标签切换 → pendingOpenPos；谁新谁优先。
            if (manualAt > pendingAt) p = lastRightClicked;
            else p = pendingOpenPos;
        }
        cachedCurrentScreen = screen;
        cachedCurrentPos = p;
        cachedCurrentSet = true;
        LOGGER.debug("QCT current resolve: current={} manual={} manualTs={} pending={} pendingTs={}",
                p == null ? "null" : p.toShortString(),
                lastRightClicked == null ? "-" : lastRightClicked.toShortString(), manualAt,
                pendingOpenPos == null ? "-" : pendingOpenPos.toShortString(), pendingAt);
        return p;
    }

    private void requestOpen(Minecraft mc, BlockPos pos) {
        if (mc.getConnection() == null) return;
        mc.getConnection().send(new RequestOpenMenuPayload(pos));
        pendingOpenPos = pos;
        pendingAt = System.currentTimeMillis();
        // 本次打开是标签切换：随后旧 screen 的 removed() 为切换触发，豁免页码复位
        pageResetSuppressed = true;
    }

    /** 界面关闭回调（由 AbstractContainerScreenMixin 注入 Screen.removed 调用）。
     * <p>真正关闭容器 / 打开背包 / Esc 退出后，翻页复位回第一页（修复“重开界面残留
     * 上次页码”的缓存 bug）；若本次 removed 是标签点击切换新容器触发（见
     * {@link #pageResetSuppressed}），则保留当页页码。 */
    public void onScreenClosed() {
        if (pageResetSuppressed) {
            pageResetSuppressed = false;
            return;
        }
        containerPage = 0;
        workPage = 0;
    }

    // ---------------- 分页布局（5+1：5 个基础扫描槽 + 1 收藏槽，单行） ----------------
    private static int maxPerRow(int iw) {
        // 单行可容纳总标签数（含 1 收藏位）；iw=176 时 = 6（5 基础 + 1 收藏）
        int pp = (iw - 2) / (TAB_W + TAB_GAP) - 1;
        return Math.max(1, pp);
    }

    /** 每页基础标签数（收藏位独立，不参与翻页），随窗口宽度自适应。 */
    private static int perPage(int iw) {
        return maxPerRow(iw);
    }

    /** 标签区纵向高度：单行。 */
    private static int areaHeight() {
        return TAB_H;
    }

    private static int totalPages(int size, int iw) {
        int pp = perPage(iw);
        return Math.max(0, (size + pp - 1) / pp);
    }

    /** 整行布局结果：模拟原版创造 5+2 的“分组对齐”——基础标签组紧密贴 GUI 左缘，
     *  收藏槽单独贴 GUI 右缘，左右两组之间自然留出间隙。 */
    private static class Row {
        int startX;        // 基础标签组起点（贴 GUI 左缘）
        int favX;          // 收藏槽 x（贴 GUI 右缘）；无收藏槽时 = -1
    }

    private static Row layoutRow(int left, int iw, int baseSize, boolean favVisible) {
        Row r = new Row();
        r.startX = left;
        r.favX = favVisible ? left + iw - TAB_W : -1;
        return r;
    }

    private static class ClickResult {
        boolean hit;
        int tabIndex = -1;   // list 内基础标签索引；-1 表示未命中
        boolean favHit;      // 命中收藏槽
    }

    private ClickResult classifyClick(double mx, double my, int left, int y, int iw,
                                      List<NearbyEntry> list, int page, BlockPos fav) {
        ClickResult r = new ClickResult();
        int pp = perPage(iw);
        int size = list.size();
        boolean favVisible = fav != null && containsPos(list, fav);
        Row row = layoutRow(left, iw, size, favVisible);
        int base = Math.min(pp, size);
        int start = page * pp;
        double y0 = y;
        for (int i = 0; i < base; i++) {
            int idx = start + i;
            if (idx >= size) break;
            double tx = row.startX + i * (TAB_W + TAB_GAP);
            if (mx >= tx && mx < tx + TAB_W && my >= y0 && my < y0 + TAB_H) {
                r.hit = true;
                r.tabIndex = idx;
                return r;
            }
        }
        if (favVisible) {
            double fx = row.favX;
            if (mx >= fx && mx < fx + TAB_W && my >= y0 && my < y0 + TAB_H) {
                r.hit = true;
                r.favHit = true;
                return r;
            }
        }
        return r;
    }

    /** 处理一个方向（topSide）的标签区点击：基础标签切换 / 收藏槽打开。点击即消费。 */
    private boolean handleTabAreaClick(AbstractContainerScreen<?> screen, double mx, double my, int button,
                                       int left, int y, int iw, List<NearbyEntry> list, int page,
                                       BlockPos fav, boolean topSide, Minecraft mc) {
        // 先判定是否真的点中标签区矩形：未命中一律交还原版（左键/右键都放行），
        // 否则只要容器标签在显示，任何右键点击都会被当成"标签区已消费"吞掉，
        // 导致原版背包右键（对半分、游标吸附时放一个物品）整体失效。
        ClickResult r = classifyClick(mx, my, left, y, iw, list, page, fav);
        if (!r.hit) return false;
        // 点中标签区但非左键（右键/中键）：本模组不处理该按键，仅消费掉避免穿透到底层槽位
        if (button != 0) return true;
        BlockPos target = null;
        if (r.favHit) {
            target = fav;                 // 收藏槽：打开收藏容器
        } else if (r.tabIndex >= 0 && r.tabIndex < list.size()) {
            target = list.get(r.tabIndex).pos();
        }
        if (target != null) {
            activeTop = topSide;
            // 记录点击时的光标位置，目标容器打开后用于还原被重置到中心的鼠标
            lastSwitchMouseX = mx;
            lastSwitchMouseY = my;
            switchedAtMs = System.currentTimeMillis();
            pendingRestoreMouse = true;
            // 同时登记静态还原请求，releaseMouse（打开新 GUI）时立即消费
            requestMouseRestore(mx, my);
            requestOpen(mc, target);
        }
        return true;
    }

    // ---------------- 右上角三按钮（收藏 / 左翻页 / 右翻页） ----------------
    /** 三个按钮横排起点 x：GUI 右上角。 */
    private static int topButtonsX0(int left, int iw) {
        return left + iw - (3 * BTN_W + 2 * BTN_GAP) - BTN_INSET_X;
    }

    private static boolean hitRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 收藏/取消：仅当聚焦了某一容器时可操作。再次点击已收藏的当前容器 = 取消收藏；
     *  点击未收藏的容器 = 收藏之。取消后收藏槽消失（白星），切回被收藏容器金星复现。 */
    private void toggleFavorite(BlockPos current) {
        if (current == null) return;                 // 纯背包无可收藏目标
        if (containsPos(containers, current)) {
            favContainer = current.equals(favContainer) ? null : current;
        } else if (containsPos(workblocks, current)) {
            favWork = current.equals(favWork) ? null : current;
        }
    }

    /** 同步当前交互方向：选中上/下某容器后翻转页按钮只控该侧；纯背包保持上次方向。 */
    private void updateActiveSide(BlockPos current) {
        if (current == null) return;
        if (containsPos(containers, current)) activeTop = true;
        else if (containsPos(workblocks, current)) activeTop = false;
    }

    /** 翻页：纯背包（current==null）时上下同步翻页；选中某侧容器后仅翻当前交互方向。 */
    private void pageBoth(int delta, int left, int iw, BlockPos current) {
        if (current == null) {
            containerPage = clampPage(containerPage + delta, containers.size(), iw);
            workPage = clampPage(workPage + delta, workblocks.size(), iw);
        } else if (activeTop) {
            containerPage = clampPage(containerPage + delta, containers.size(), iw);
        } else {
            workPage = clampPage(workPage + delta, workblocks.size(), iw);
        }
    }

    private static int clampPage(int page, int size, int iw) {
        int tp = totalPages(size, iw);
        return Math.max(0, Math.min(page, tp - 1));
    }

    // ---------------- 绘制 ----------------
    // ---------------- 图标设置槽 ----------------
    private NearbyEntry findEntry(BlockPos pos, boolean container) {
        List<NearbyEntry> list = container ? containers : workblocks;
        for (NearbyEntry e : list) {
            if (e.pos().equals(pos)) return e;
        }
        return null;
    }

    private void drawIconSlot(AbstractContainerScreen<?> screen, GuiGraphics gg, int mouseX, int mouseY,
                              Minecraft mc, int left, int top, int iw, int ih, BlockPos current) {
        int slot = 18;
        int sx = left + iw + 8;
        int sy = top + ih / 2 - slot / 2;

        boolean hover = mouseX >= sx && mouseX < sx + slot && mouseY >= sy && mouseY < sy + slot;
        int border = hover ? 0xFFFFFFFF : 0xFF8B8B8B;
        gg.fill(sx, sy, sx + slot, sy + slot, 0x906E6E6E);
        gg.fill(sx, sy, sx + slot, sy + 1, border);
        gg.fill(sx, sy, sx + 1, sy + slot, border);
        gg.fill(sx + slot - 1, sy, sx + slot, sy + slot, border);
        gg.fill(sx, sy + slot - 1, sx + slot, sy + slot, border);

        ItemStack icon = IconStore.getIcon(mc, mc.level.dimension(), current);
        if (icon.isEmpty()) {
            NearbyEntry e = findEntry(current, true);
            if (e == null) e = findEntry(current, false);
            if (e != null) icon = defaultIcon(e);
        }
        if (!icon.isEmpty()) {
            gg.renderItem(icon, sx + 1, sy + 1);
        }

        if (hover) {
            gg.renderTooltip(mc.font,
                    Component.literal("游标吸附物品点击：替换图标    空游标点击：重置图标"),
                    mouseX, mouseY);
        }
    }

    private boolean handleIconSlotClick(double mx, double my, int button, Minecraft mc,
                                        int left, int top, int iw, int ih, BlockPos current) {
        int slot = 18;
        int sx = left + iw + 8;
        int sy = top + ih / 2 - slot / 2;
        if (mx < sx || mx >= sx + slot || my < sy || my >= sy + slot) return false;

        Player player = mc.player;
        if (player == null) return false;
        // 读取“游标吸附的物品”（carried），而非主手物品；只读取其图标快照，不消耗物品。
        // 快照（copyWithCount(1)）在 IconStore.setIcon 内部完成，这里不做任何显式"复制/放置"，
        // UI 只会直接刷新标签页图标，无移动动画。
        ItemStack carried = player.containerMenu == null
                ? ItemStack.EMPTY
                : player.containerMenu.getCarried();
        // 统一语义：游标吸附有物品 → 点击替换图标；游标为空 → 点击重置图标（回到方块默认）。
        // 左键右键一致、不依赖具体按键，规避右键事件被吞导致的“重置失效”。
        // 本地立即更新缓存（即时反馈），同时发送 C2S 请求，服务端权威存储并全服广播同步。
        if (carried.isEmpty()) {
            IconStore.setIcon(mc, mc.level.dimension(), current, ItemStack.EMPTY);
            ModNetworking.sendIconChange(mc.level.dimension(), current, ItemStack.EMPTY);
            player.displayClientMessage(Component.literal("已重置图标"), true);
        } else {
            IconStore.setIcon(mc, mc.level.dimension(), current, carried);
            ModNetworking.sendIconChange(mc.level.dimension(), current, carried);
        }
        return true;
    }

    private static boolean containsPos(List<NearbyEntry> list, BlockPos pos) {
        if (pos == null) return false;
        for (NearbyEntry e : list) {
            if (e.pos().equals(pos)) return true;
        }
        return false;
    }
}
