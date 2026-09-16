package com.south13oy.qct.ui;

import com.south13oy.qct.config.ModConfig;
import com.south13oy.qct.config.SideStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * “容器/工作方块分类”模组配置界面（游戏内按 B 打开）。
 * <p>罗列模组【会扫描】的全部容器/工作方块类型（按方块注册 ID），每行提供
 * ↑（上方）/ ↓（下方）归属按钮（当前生效方向为灰色选中态）、名称与注册ID，
 * 以及 X 删除按钮（删除后该类型不再被扫描）。列表底部提供等宽的 “+” 按钮：
 * 输入其它模组的容器方块注册ID 即可纳入扫描与标签体系（基础的模组联动接口）。
 */
public class SideConfigScreen extends Screen {

    private static final int ROW_H = 26;
    private static final int LIST_TOP = 38;
    private static final int BTN_ARROW_W = 22;
    private static final int BTN_ARROW_H = 18;
    private static final int BTN_PRI_W = 46;
    private static final int BTN_PRI_H = 18;
    private static final int BTN_X_W = 24;
    private static final int BTN_X_H = 18;
    private static final int BTN_RESET_W = 40;
    private static final int BTN_RESET_H = 22;
    /** 底部三行（扫描距离 / + / 操作栏）统一高度。 */
    private static final int BOTTOM_ROW_H = 22;

    private final Screen previousScreen;

    /** “模组会扫描的全部容器/工作方块”注册ID 枚举缓存（一次枚举，按注册表版本重算）。 */
    private static final List<ResourceLocation> KNOWN_CACHE = new ArrayList<>();
    private static final Map<String, Boolean> KNOWN_IS_CONTAINER = new LinkedHashMap<>();
    private static boolean cached = false;

    private static final class TypeEntry {
        String regId;
        Component name;
        boolean defaultTop;   // 标准配置/未手动指定时的默认方向
        TypeEntry(String regId, Component name, boolean defaultTop) {
            this.regId = regId;
            this.name = name;
            this.defaultTop = defaultTop;
        }
    }

    private final List<TypeEntry> entries = new ArrayList<>();
    private int scrollY = 0;
    private String feedback = "";

    private boolean addingMode = false;
    /** 当前正在编辑优先级的类型 regId（null=未在编辑）。 */
    private String prioEditReg = null;
    private EditBox input;
    /** 底部“扫描距离（格）”输入框（生存模式手长=4 格，默认）。 */
    private EditBox radiusInput;

    public SideConfigScreen() {
        super(Component.literal("便捷容器标签 · 容器/工作方块分类"));
        this.previousScreen = Minecraft.getInstance().screen;
    }

    @Override
    protected void init() {
        super.init();
        int iw = Math.max(120, width - 140);
        this.input = new EditBox(this.font, 16, height - 92, iw, 18,
                Component.literal("方块注册ID，如 minecraft:chest"));
        this.input.setMaxLength(96);
        this.input.setVisible(false);
        // 底部“扫描距离”输入框（仅数字，范围 1-64，默认 4=生存模式手长）。
        // Y 在 render 中按布局随高度调整，这里只定初始值。
        this.radiusInput = new EditBox(this.font, 104, height - 98, 60, 18,
                Component.literal("扫描距离（格）"));
        this.radiusInput.setMaxLength(2);
        this.radiusInput.setValue(Integer.toString(ModConfig.VALUES.scanRadius.get()));
        this.radiusInput.setFilter(s -> s.matches("\\d{0,2}"));
        this.radiusInput.setVisible(false);
        // 进入编辑会话：此后所有修改仅存内存，直到“保存并退出”才落盘
        SideStore.beginSession();
        buildEntries();
    }

    // ---------------- 类型枚举 ----------------
    /**
     * 判定一个方块类型是否属于“模组会扫描”的对象：
     * ① 方块自身提供菜单（如工作台/附魔台，implements MenuProvider）；
     * ② 有方块实体且该实体是 Container 或提供菜单（如箱子/熔炉/漏斗等）。
     */
    private static boolean isScanTarget(Block b) {
        if (b instanceof MenuProvider) return true;
        if (b instanceof EntityBlock eb) {
            try {
                BlockEntity be = eb.newBlockEntity(BlockPos.ZERO, b.defaultBlockState());
                if (be instanceof Container || be instanceof MenuProvider) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static boolean isContainerType(Block b) {
        if (b instanceof EntityBlock eb) {
            try {
                BlockEntity be = eb.newBlockEntity(BlockPos.ZERO, b.defaultBlockState());
                if (be instanceof Container) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 全注册表枚举“模组会扫描的全部容器/工作方块类型”（带缓存）。 */
    private static List<TypeEntry> enumerateAll() {
        if (!cached) {
            cached = true;
            KNOWN_CACHE.clear();
            KNOWN_IS_CONTAINER.clear();
            for (ResourceLocation rl : BuiltInRegistries.BLOCK.keySet()) {
                Block b = BuiltInRegistries.BLOCK.get(rl);
                if (b == null || b == Blocks.AIR) continue;
                if (!isScanTarget(b)) continue;
                KNOWN_CACHE.add(rl);
                KNOWN_IS_CONTAINER.put(rl.toString(), isContainerType(b));
            }
            KNOWN_CACHE.sort(Comparator.comparing(ResourceLocation::toString));
        }
        List<TypeEntry> out = new ArrayList<>();
        for (ResourceLocation rl : KNOWN_CACHE) {
            String reg = rl.toString();
            Block b = BuiltInRegistries.BLOCK.get(rl);
            out.add(new TypeEntry(reg, b.getName(), KNOWN_IS_CONTAINER.getOrDefault(reg, false)));
        }
        return out;
    }

    // ---------------- 列表构建 ----------------
    private void buildEntries() {
        entries.clear();
        Set<String> seen = new java.util.LinkedHashSet<>();
        // 以模组内置标准清单为骨架：标准类型（含铁砧/附魔台/末影箱/切石机/制图台/
        // 锻造台/砂轮/织布机等）始终出现在列表中，即使运行时枚举判定收不到也固定显示，
        // 保证「全部重置」后即恢复内置标准配置。
        for (String reg : SideStore.DEFAULT_PRIORITIES.keySet()) {
            if (!seen.add(reg) || SideStore.isExcluded(reg) || SideStore.isDefaultExcluded(reg)) continue;
            boolean top = SideStore.DEFAULT_TOP.getOrDefault(reg, false);
            if (SideStore.getDirection(reg) != null) top = SideStore.getDirection(reg).equals("top");
            ResourceLocation rl = ResourceLocation.tryParse(reg);
            Component nm = Component.literal(reg);
            if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
                nm = BuiltInRegistries.BLOCK.get(rl).getName();
            }
            entries.add(new TypeEntry(reg, nm, top));
        }
        // 注册表枚举出的「标准清单之外」的其它可扫描类型（如其它原版容器等，1000 兜底殿后）
        for (TypeEntry te : enumerateAll()) {
            String reg = te.regId;
            if (!seen.add(reg) || SideStore.isExcluded(reg) || SideStore.isDefaultExcluded(reg)) continue;
            boolean top = effectiveTop(te);
            entries.add(new TypeEntry(reg, te.name, top));
        }
        // 手动添加过但未出现在枚举中的类型（可预先配置其它 mod 的容器）
        for (Map.Entry<String, String> d : SideStore.getDirections().entrySet()) {
            String reg = d.getKey();
            if (seen.contains(reg) || SideStore.isExcluded(reg)) continue;
            ResourceLocation rl = ResourceLocation.tryParse(reg);
            Component nm = Component.literal(reg);
            if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
                nm = BuiltInRegistries.BLOCK.get(rl).getName();
            }
            entries.add(new TypeEntry(reg, nm, d.getValue().equals("top")));
        }
        // 界面显示顺序与显示优先级绑定：先按方向分组（上方组在前、下方组在后），
        // 同组内按优先级升序（越小越靠前），优先级相同按注册ID稳定排序。
        // 这样「全部重置」后列表即恢复模组内置标准配置的固定顺序（锁死在模组本身，
        // 不随历史配置文件改变）。
        entries.sort(Comparator
                .comparingInt((TypeEntry e) -> effectiveTop(e) ? 0 : 1)
                .thenComparingInt(e -> SideStore.getPriority(e.regId))
                .thenComparing(e -> e.regId));
    }

    private boolean effectiveTop(TypeEntry te) {
        String side = SideStore.getDirection(te.regId);
        return side != null ? side.equals("top") : te.defaultTop;
    }

    // ---------------- 渲染 ----------------
    /**
     * 禁用原版 Screen 对背景的高斯模糊：有 level 时原版 Screen 会在整个画面上叠加
     * blur shader（看起来像打了马赛克）。改为清晰的半透明暗化渐变，背后游戏画面保持可见。
     */
    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        gg.fillGradient(0, 0, this.width, this.height, 0x38000000, 0x6C000000);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg, mouseX, mouseY, partialTick);
        gg.drawString(font, "便捷容器标签 · 容器/工作方块分类  (按 ESC 返回，不保存修改)", 16, 10, 0xFFFFFF);
        gg.drawString(font, "↑/↓ 方向；数字=优先级(越小越靠前，点击可改)；X=删除；重置=恢复默认；修改仅在“保存并退出”后生效",
                16, 24, 0xA9A9A9);
        if (!feedback.isEmpty()) {
            gg.drawString(font, feedback, 16, height - 126, 0xFFFFC000);
        }

        int viewH = viewportHeight();
        int contentH = entries.size() * ROW_H;
        int maxS = Math.max(0, contentH - viewH);
        scrollY = Math.max(0, Math.min(scrollY, maxS));

        int first = scrollY / ROW_H;
        int last = Math.min(entries.size(), (scrollY + viewH + ROW_H - 1) / ROW_H + 1);
        for (int i = first; i < entries.size() && i < last; i++) {
            int y = LIST_TOP + i * ROW_H - scrollY;
            if (y + ROW_H < LIST_TOP || y > LIST_TOP + viewH) continue;
            renderRow(gg, entries.get(i), y, mouseX, mouseY);
        }

        // 底部“+”按钮（与行等宽）；下方留出“扫描距离”行与操作栏的空间
        int plusY = plusY();
        if (entries.isEmpty()) {
            gg.drawString(font, "未发现可扫描的容器/工作方块类型", 16, LIST_TOP + 4, 0x9A9A9A);
        }
        boolean plusHover = hit(mouseX, mouseY, 16, plusY, width - 32, 22);
        fillBox(gg, 16, plusY, width - 32, 22, addingMode, plusHover);
        gg.drawCenteredString(font, (addingMode ? "+ 正在添加……" : "+ 添加其它模组的容器类型（注册ID）"),
                width / 2, plusY + 6, 0xFFFFFF);

        if (addingMode) {
            int iiY = plusY - 28;
            input.setY(iiY);
            input.setVisible(true);
            input.render(gg, mouseX, mouseY, partialTick);
            boolean addHover = hit(mouseX, mouseY, width - 120, iiY, 104, 18);
            fillBox(gg, width - 120, iiY, 104, 18, false, addHover);
            gg.drawCenteredString(font, "添加", width - 68, iiY + 5, 0xFFFFFF);
        } else if (prioEditReg != null) {
            // 优先级编辑框的位置在 renderRow 中按行设置
            input.setVisible(true);
            input.render(gg, mouseX, mouseY, partialTick);
        } else {
            input.setVisible(false);
        }

        // 底部“扫描距离（格）”行：标签 + 数字输入框 + 重置按钮。
        // 仅非“正在添加”时显示（添加模式输入框占同一水平区域，避免重叠）。
        if (!addingMode) {
            int rY = plusY() - 30;
            radiusInput.setX(104);
            radiusInput.setY(rY);
            radiusInput.setWidth(60);
            radiusInput.setHeight(BOTTOM_ROW_H);
            radiusInput.setVisible(true);
            gg.drawString(font, "扫描距离(格)：", 16, rY + (BOTTOM_ROW_H - font.lineHeight) / 2, 0xFFFFFF);
            radiusInput.render(gg, mouseX, mouseY, partialTick);
            int rrX = 104 + 60 + 6;
            boolean rrHover = hit(mouseX, mouseY, rrX, rY, BTN_RESET_W, BTN_RESET_H);
            fillBox(gg, rrX, rY, BTN_RESET_W, BTN_RESET_H, false, rrHover);
            gg.drawCenteredString(font, "重置", rrX + BTN_RESET_W / 2,
                    rY + (BOTTOM_ROW_H - font.lineHeight) / 2, 0x9FE0A0);
        } else if (radiusInput != null) {
            radiusInput.setVisible(false);
        }

        // 底部常驻操作栏（不随列表滚动）
        renderBottomBar(gg, mouseX, mouseY);

        super.render(gg, mouseX, mouseY, partialTick);
    }

    /** 底部操作栏：全部重置 / 不保存并退出 / 保存并退出（固定在界面最底部，不随滚轮滑动）。 */
    private void renderBottomBar(GuiGraphics gg, int mouseX, int mouseY) {
        int y = barY();
        int margin = 16;
        int gap = 8;
        int bw = (width - margin * 2 - gap * 2) / 3;
        String[] labels = {"全部重置", "不保存并退出", "保存并退出"};
        int[] xs = {margin, margin + bw + gap, margin + 2 * (bw + gap)};
        for (int i = 0; i < 3; i++) {
            boolean hv = hit(mouseX, mouseY, xs[i], y, bw, BOTTOM_ROW_H);
            // 三个按钮初始均为普通态，仅悬停时高亮；“保存并退出”不作为默认选中项
            fillBox(gg, xs[i], y, bw, BOTTOM_ROW_H, false, hv);
            gg.drawCenteredString(font, labels[i], xs[i] + bw / 2,
                    y + (BOTTOM_ROW_H - font.lineHeight) / 2, 0xFFFFFF);
        }
    }

    private int viewportHeight() {
        // 下方预留：“扫描距离”行(18) + “+”按钮行(22) + 间距
        return Math.max(0, height - LIST_TOP - 108);
    }

    /** 最右侧“重置”按钮的 X（位于 X 删除按钮右侧）。 */
    private int resetX() {
        return width - 48;
    }

    /** X 删除按钮的 X（位于重置左侧）。 */
    private int xX() {
        return resetX() - 4 - BTN_X_W;
    }

    /** 每行优先级框的 X 坐标（位于 X 按钮左侧）。 */
    private int priX() {
        return xX() - 4 - BTN_PRI_W;
    }

    /** 底部常驻操作栏的 Y（不随列表滚动）。 */
    private int barY() {
        return height - 32;
    }

    /** 底部“+”按钮行的 Y：与上方“扫描距离”行、下方操作栏间距统一为 8px。 */
    private int plusY() {
        return height - 62;
    }

    private void renderRow(GuiGraphics gg, TypeEntry te, int y, int mouseX, int mouseY) {
        boolean top = effectiveTop(te);
        boolean upHover = hit(mouseX, mouseY, 16, y, BTN_ARROW_W, BTN_ARROW_H);
        boolean downHover = hit(mouseX, mouseY, 16 + BTN_ARROW_W + 4, y, BTN_ARROW_W, BTN_ARROW_H);
        fillBox(gg, 16, y, BTN_ARROW_W, BTN_ARROW_H, top, upHover);
        gg.drawCenteredString(font, "↑", 16 + BTN_ARROW_W / 2, y + 4, 0xFFFFFF);
        fillBox(gg, 16 + BTN_ARROW_W + 4, y, BTN_ARROW_W, BTN_ARROW_H, !top, downHover);
        gg.drawCenteredString(font, "↓", 16 + BTN_ARROW_W + 4 + BTN_ARROW_W / 2, y + 4, 0xFFFFFF);

        // 优先级框（X 按钮左侧）
        int px = priX();
        boolean editingRow = prioEditReg != null && prioEditReg.equals(te.regId);
        if (editingRow) {
            input.setX(px);
            input.setY(y);
            input.setWidth(BTN_PRI_W);
            input.setHeight(BTN_PRI_H);
            input.setVisible(true);
            fillBox(gg, px, y, BTN_PRI_W, BTN_PRI_H, true, false);
        } else {
            boolean priHover = hit(mouseX, mouseY, px, y, BTN_PRI_W, BTN_PRI_H);
            boolean isCustom = SideStore.isPriorityCustom(te.regId);
            fillBox(gg, px, y, BTN_PRI_W, BTN_PRI_H, priHover, priHover);
            gg.drawCenteredString(font, String.valueOf(SideStore.getPriority(te.regId)),
                    px + BTN_PRI_W / 2, y + 4, isCustom ? 0xFFFFD54F : 0x9A9A9A);
        }

        int nameX = 16 + (BTN_ARROW_W + 4) * 2 + 4;
        int textRight = px - 8;
        int maxW = Math.max(0, textRight - nameX);
        String name = te.name.getString();
        if (name.isEmpty()) name = te.regId;
        String nameS = font.plainSubstrByWidth(name, maxW);
        gg.drawString(font, nameS, nameX, y + 5, 0xFFFFFF);
        int idX = nameX + font.width(nameS) + 8;
        String idS = font.plainSubstrByWidth("(" + te.regId + ")", Math.max(0, textRight - idX));
        gg.drawString(font, idS, idX, y + 9, 0x8A8A8A);

        int xx = xX();
        boolean xHover = hit(mouseX, mouseY, xx, y, BTN_X_W, BTN_X_H);
        fillBox(gg, xx, y, BTN_X_W, BTN_X_H, false, xHover);
        gg.drawCenteredString(font, "X", xx + BTN_X_W / 2, y + 4, 0xFF7070);

        // 重置按钮（X 右侧）：该类型恢复默认
        int rx = resetX();
        boolean resetHover = hit(mouseX, mouseY, rx, y, BTN_RESET_W, BTN_RESET_H);
        fillBox(gg, rx, y, BTN_RESET_W, BTN_RESET_H, false, resetHover);
        gg.drawCenteredString(font, "重置", rx + BTN_RESET_W / 2, y + 4, 0x9FE0A0);
    }

    private void fillBox(GuiGraphics gg, int x, int y, int w, int h, boolean selected, boolean hover) {
        int bg = selected ? 0xFF606060 : (hover ? 0xFF4A4A4A : 0xFF2E2E2E);
        int border = selected ? 0xFFE0E0E0 : (hover ? 0xFFCFCFCF : 0xFF8A8A8A);
        gg.fill(x, y, x + w, y + h, bg);
        gg.fill(x, y, x + w, y + 1, border);
        gg.fill(x, y, x + 1, y + h, border);
        gg.fill(x + w - 1, y, x + w, y + h, border);
        gg.fill(x, y + h - 1, x + w, y + h, border);
    }

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ---------------- 交互 ----------------
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        if (input != null && input.isVisible() && input.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (radiusInput != null && radiusInput.isVisible()
                && radiusInput.mouseClicked(mouseX, mouseY, button)) {
            cancelPrioEdit();
            return true;
        }

        int viewH = viewportHeight();
        int first = scrollY / ROW_H;
        int last = Math.min(entries.size(), (scrollY + viewH + ROW_H - 1) / ROW_H + 1);
        for (int i = first; i < entries.size() && i < last; i++) {
            int y = LIST_TOP + i * ROW_H - scrollY;
            if (y + ROW_H < LIST_TOP || y > LIST_TOP + viewH) continue;
            TypeEntry te = entries.get(i);
            if (hit(mouseX, mouseY, 16, y, BTN_ARROW_W, BTN_ARROW_H)) {
                cancelPrioEdit();
                setTop(te, true);
                return true;
            }
            if (hit(mouseX, mouseY, 16 + BTN_ARROW_W + 4, y, BTN_ARROW_W, BTN_ARROW_H)) {
                cancelPrioEdit();
                setTop(te, false);
                return true;
            }
            if (hit(mouseX, mouseY, priX(), y, BTN_PRI_W, BTN_PRI_H)) {
                startPrioEdit(te);
                return true;
            }
            int xx = xX();
            if (hit(mouseX, mouseY, xx, y, BTN_X_W, BTN_X_H)) {
                cancelPrioEdit();
                removeEntry(te);
                return true;
            }
            int rx = resetX();
            if (hit(mouseX, mouseY, rx, y, BTN_RESET_W, BTN_RESET_H)) {
                cancelPrioEdit();
                resetEntry(te);
                return true;
            }
        }

        int plusY = plusY();
        if (hit(mouseX, mouseY, 16, plusY, width - 32, 22)) {
            cancelPrioEdit();
            addingMode = !addingMode;
            if (addingMode) {
                input.setMaxLength(96);
                input.setValue("");
                input.setFocused(true);
                feedback = "输入要添加的容器方块注册ID（如 minecraft:chest），点“添加”或按回车";
            }
            return true;
        }

        // “扫描距离”行右侧的“重置”按钮（恢复默认 4=生存模式手长）
        if (!addingMode) {
            int rY = plusY() - 26;
            int rrX = 104 + 60 + 6;
            if (hit(mouseX, mouseY, rrX, rY, BTN_RESET_W, BTN_RESET_H)) {
                cancelPrioEdit();
                radiusInput.setValue(Integer.toString(ModConfig.VALUES.scanRadius.getDefault()));
                radiusInput.setFocused(false);
                feedback = "扫描距离已恢复默认（生存模式手长 " + ModConfig.VALUES.scanRadius.getDefault() + " 格）";
                return true;
            }
        }

        // 底部常驻操作栏（不随列表滚动）
        int barY_ = barY();
        int margin = 16;
        int gap = 8;
        int bw = (width - margin * 2 - gap * 2) / 3;
        int[] barX = {margin, margin + bw + gap, margin + 2 * (bw + gap)};
        if (hit(mouseX, mouseY, barX[0], barY_, bw, BOTTOM_ROW_H)) {
            cancelPrioEdit();
            resetAllConfig();
            return true;
        }
        if (hit(mouseX, mouseY, barX[1], barY_, bw, BOTTOM_ROW_H)) {
            cancelPrioEdit();
            doExit(false);
            return true;
        }
        if (hit(mouseX, mouseY, barX[2], barY_, bw, BOTTOM_ROW_H)) {
            cancelPrioEdit();
            doExit(true);
            return true;
        }

        // 点击空白处结束优先级编辑
        cancelPrioEdit();
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int viewH = viewportHeight();
        int contentH = entries.size() * ROW_H;
        int maxS = Math.max(0, contentH - viewH);
        scrollY = Math.max(0, Math.min(scrollY - (int) (verticalAmount * 20), maxS));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // “扫描距离”输入框聚焦时优先处理
        if (radiusInput != null && radiusInput.isVisible() && radiusInput.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                applyRadiusInput();
                return true;
            }
            if (radiusInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        if (input != null && input.isVisible()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (addingMode) {
                    tryAdd();
                    return true;
                }
                if (prioEditReg != null) {
                    commitPrioEdit();
                    return true;
                }
            }
            if (input.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (radiusInput != null && radiusInput.isVisible() && radiusInput.isFocused()
                && radiusInput.charTyped(codePoint, modifiers)) {
            return true;
        }
        if (input != null && input.isVisible() && input.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    /** 进入某类型行优先级编辑模式（复用底部输入框，移动到对应行）。 */
    private void startPrioEdit(TypeEntry te) {
        addingMode = false;
        prioEditReg = te.regId;
        input.setMaxLength(8);
        input.setValue(Integer.toString(SideStore.getPriority(te.regId)));
        input.setFocused(true);
        String nm = te.name.getString().isEmpty() ? te.regId : te.name.getString();
        feedback = "编辑 " + nm + " 的显示优先级（数字越小越靠前，最小0，可并列）后回车";
    }

    /** 回车提交优先级修改。 */
    private void commitPrioEdit() {
        if (prioEditReg == null) return;
        String s = input.getValue().trim();
        int v;
        try {
            v = Integer.parseInt(s);
            if (v < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            feedback = "无效优先级，请输入 0 或更大的整数";
            return; // 保持编辑状态，让用户修正
        }
        SideStore.setPriority(prioEditReg, v);
        String nm = prioEditReg;
        ResourceLocation rl = ResourceLocation.tryParse(prioEditReg);
        if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
            nm = BuiltInRegistries.BLOCK.get(rl).getName().getString();
        }
        if (nm.isEmpty()) nm = prioEditReg;
        feedback = "已设置 " + nm + " 优先级 = " + v;
        prioEditReg = null;
        input.setFocused(false);
        buildEntries();
    }

    /** 取消优先级编辑（不保存）。 */
    private void cancelPrioEdit() {
        if (prioEditReg == null) return;
        prioEditReg = null;
        input.setFocused(false);
    }

    /** 设置类型方向（上/下），全局生效。 */
    private void setTop(TypeEntry te, boolean top) {
        String side = top ? "top" : "bottom";
        SideStore.setDirection(te.regId, side);
        feedback = "已设置 " + (te.name.getString().isEmpty() ? te.regId : te.name.getString())
                + " -> " + (top ? "上方" : "下方");
        buildEntries();
    }

    /** X 删除：该类型不再被扫描（从列表移除）。 */
    private void removeEntry(TypeEntry te) {
        SideStore.setExcluded(te.regId, true);
        feedback = "已删除 " + (te.name.getString().isEmpty() ? te.regId : te.name.getString())
                + "（该类型不再被扫描）";
        buildEntries();
    }

    /** 行内“重置”：该类型恢复默认（取消排除、清除手动方向与优先级）。 */
    private void resetEntry(TypeEntry te) {
        SideStore.resetType(te.regId);
        String nm = te.name.getString().isEmpty() ? te.regId : te.name.getString();
        feedback = "已重置 " + nm + " 为默认（方向/优先级/排除）";
        buildEntries();
    }

    /** 全部重置：清除全部用户覆盖，恢复到标准配置（待保存后生效）。 */
    private void resetAllConfig() {
        SideStore.resetAll();
        feedback = "已全部重置为标准配置，点“保存并退出”生效";
        buildEntries();
    }

    /** 退出配置界面：save=true 保存本次修改后退出；false 丢弃修改（不保存）后退出。 */
    private void doExit(boolean save) {
        if (save) {
            // 先提交“扫描距离”输入（即使未按回车也生效）；值非法时不退出，让用户修正
            String s = radiusInput.getValue().trim();
            if (!s.isEmpty()) {
                int v;
                try {
                    v = Integer.parseInt(s);
                    if (v < 1 || v > 64) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    feedback = "扫描距离须为 1-64 的整数，请修正后再保存";
                    return;
                }
                ModConfig.VALUES.scanRadius.set(v);
                ModConfig.SPEC.save();
            }
            SideStore.commitSession();
        } else {
            SideStore.discardSession();
        }
        onClose();
    }

    /** 提交“扫描距离”输入框（回车触发）：校验 1-64 后写配置并落盘。 */
    private void applyRadiusInput() {
        String s = radiusInput.getValue().trim();
        if (s.isEmpty()) {
            feedback = "扫描距离为空，请输入 1-64 的整数（默认 4=生存模式手长）";
            return;
        }
        int v;
        try {
            v = Integer.parseInt(s);
            if (v < 1 || v > 64) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            feedback = "扫描距离须为 1-64 的整数";
            return;
        }
        ModConfig.VALUES.scanRadius.set(v);
        ModConfig.SPEC.save();
        feedback = "扫描距离已设为 " + v + " 格（保存并退出后彻底生效）";
        radiusInput.setFocused(false);
    }

    /** + 添加其它模组的容器类型。 */
    private void tryAdd() {
        String s = input.getValue().trim().toLowerCase();
        if (s.isEmpty()) return;
        ResourceLocation rl = ResourceLocation.tryParse(s);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            feedback = "无效的方块注册ID（示例：minecraft:chest / 其它mod:方块id）";
            return;
        }
        SideStore.setDirection(rl.toString(), "top");
        Block b = BuiltInRegistries.BLOCK.get(rl);
        Component nm = b != Blocks.AIR ? b.getName() : Component.literal(rl.toString());
        feedback = "已添加 " + nm.getString() + "（上方，可点 ↓ 改到下方）";
        input.setValue("");
        addingMode = false;
        buildEntries();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // 会话尚未通过“保存并退出”提交时，按“不保存”处理（ESC 等关闭路径不落盘）
        SideStore.discardSession();
        Minecraft mc = Minecraft.getInstance();
        if (previousScreen != null) {
            mc.setScreen(previousScreen);
        } else {
            mc.setScreen(null);
        }
        super.onClose();
    }
}
