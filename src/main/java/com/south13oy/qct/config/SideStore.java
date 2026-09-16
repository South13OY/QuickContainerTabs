package com.south13oy.qct.config;

import com.google.gson.Gson;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 容器/工作方块【类型级】分类配置持久化（JSON）。
 * <p>以方块注册 ID（如 minecraft:chest）为键，存储两类规则：
 * <ul>
 *   <li>directions：该类型标签出现在背包界面上方(top)还是下方(bottom)（含“+”手动添加的类型）；</li>
 *   <li>excluded：被“X”删除的类型，不再被模组扫描。</li>
 * </ul>
 * 配置界面（按 B）与 NearbyTabs 扫描分类都在消费这套规则。
 */
public final class SideStore {

    private static final String FILE_NAME = "qct_side_config.json";

    /** regId -> 方向覆盖（含手动添加）。 */
    private static final Map<String, String> DIRECTIONS = new LinkedHashMap<>();
    /** 被排除、不再扫描的注册ID。 */
    private static final Set<String> EXCLUDED = new LinkedHashSet<>();
    /** 用户为类型指定的显示优先级覆盖（数字越小越靠前）。 */
    private static final Map<String, Integer> PRIORITIES = new LinkedHashMap<>();

    // ---------------- 会话机制（配置界面编辑期暂存，退出时才落盘） ----------------
    /** 当前是否处于配置界面的编辑会话中（编辑期修改仅改内存，不直接写盘）。 */
    private static boolean sessionActive = false;
    /** 会话内是否发生过修改（决定 commit 时是否落盘）。 */
    private static boolean sessionDirty = false;
    /** 会话开始时的方向快照（用于“不保存并退出”回滚）。 */
    private static Map<String, String> snapDirections;
    /** 会话开始时的排除快照。 */
    private static Set<String> snapExcluded;
    /** 会话开始时的优先级快照。 */
    private static Map<String, Integer> snapPriorities;

    // ---------------- 标准配置（默认方向 + 默认显示优先级） ----------------
    /** 标准配置：方块注册ID -> 默认标签方向（上/下）。 */
    public static final Map<String, Boolean> DEFAULT_TOP = new LinkedHashMap<>();
    /** 标准配置：方块注册ID -> 默认显示优先级（越小越靠前，0 为最高）。 */
    public static final Map<String, Integer> DEFAULT_PRIORITIES = new LinkedHashMap<>();
    /** 未列入标准配置的类型（如其它 mod 容器）的兜底优先级：总是排在标准类型之后。 */
    public static final int DEFAULT_PRIORITY_FALLBACK = 1000;

    /** 内置永久排除的原版类型（发布规范）：完全不进入配置界面、也不被就近扫描，
     *  且“全部重置”后依然保持排除（属于模组硬规则，用户侧无法恢复）。 */
    public static final Set<String> BUILTIN_EXCLUDED = Set.of(
            "minecraft:chiseled_bookshelf",  // 雕纹书架
            "minecraft:decorated_pot",       // 装饰陶罐
            "minecraft:jukebox",             // 唱片机
            "minecraft:lectern");            // 讲台

    static {
        // ==== 上方（priority 0~2） ====
        // 箱子/陷阱箱 0、木桶/末影箱 1、17 潜影盒 2
        int p = 0;
        putDefault("minecraft:chest", true, p);            // 箱子
        putDefault("minecraft:trapped_chest", true, p);    // 陷阱箱
        p++;
        putDefault("minecraft:barrel", true, p);           // 木桶
        putDefault("minecraft:ender_chest", true, p);      // 末影箱
        p++;
        // 潜影盒（基础无色 + 16 色，同一优先级 2，共用 p）
        int shulkerP = p;
        putDefault("minecraft:shulker_box", true, shulkerP);
        String[] shulkerColors = {
                "white", "orange", "magenta", "light_blue", "yellow",
                "lime", "pink", "gray", "light_gray", "cyan",
                "purple", "blue", "brown", "green", "red", "black"};
        for (String c : shulkerColors) {
            putDefault("minecraft:" + c + "_shulker_box", true, shulkerP);
        }

        // ==== 下方（priority 0~16，与上方列表相互独立） ====
        p = 0;
        putDefault("minecraft:crafting_table", false, p++);    // 工作台
        putDefault("minecraft:stonecutter", false, p++);       // 切石机
        putDefault("minecraft:cartography_table", false, p++); // 制图台
        putDefault("minecraft:smithing_table", false, p++);    // 锻造台
        putDefault("minecraft:grindstone", false, p++);        // 砂轮
        putDefault("minecraft:loom", false, p++);              // 织布机
        putDefault("minecraft:furnace", false, p++);           // 熔炉
        putDefault("minecraft:smoker", false, p++);            // 烟熏炉
        putDefault("minecraft:blast_furnace", false, p++);     // 高炉
        putDefault("minecraft:anvil", false, p);               // 铁砧
        putDefault("minecraft:chipped_anvil", false, p);       // 开裂的铁砧
        putDefault("minecraft:damaged_anvil", false, p++);     // 损坏的铁砧
        putDefault("minecraft:enchanting_table", false, p++);  // 附魔台
        putDefault("minecraft:brewing_stand", false, p++);     // 酿造台
        putDefault("minecraft:beacon", false, p++);            // 信标
        putDefault("minecraft:dropper", false, p++);           // 投掷器
        putDefault("minecraft:dispenser", false, p++);         // 发射器
        putDefault("minecraft:crafter", false, p++);           // 合成器（1.21）
        putDefault("minecraft:hopper", false, p);              // 漏斗
    }

    private static void putDefault(String regId, boolean top, int priority) {
        DEFAULT_TOP.put(regId, top);
        DEFAULT_PRIORITIES.put(regId, priority);
    }

    /** 该类型是否在标准配置中明确定义过方向。 */
    public static boolean hasDefault(String regId) {
        return DEFAULT_TOP.containsKey(regId);
    }

    /** 标准配置给出的默认方向（未列入标准则 null）。 */
    public static Boolean defaultTopOf(String regId) {
        return DEFAULT_TOP.get(regId);
    }

    // ---------------- 路径与读写 ----------------
    private static final Gson GSON = new Gson();
    private static Path configPath;
    private static Path configDir;
    private static boolean loaded = false;

    private SideStore() {
    }

    // ---------------- 路径与读写 ----------------
    private static Path configDir() {
        if (configDir == null) {
            configDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve("config").resolve("qct");
            try {
                Files.createDirectories(configDir);
            } catch (IOException ignored) {
            }
        }
        return configDir;
    }

    private static Path configPath() {
        if (configPath == null) configPath = configDir().resolve(FILE_NAME);
        return configPath;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path p = configPath();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Map<?, ?> root = GSON.fromJson(r, Map.class);
            if (root != null) {
                Object dirs = root.get("directions");
                if (dirs instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) dirs).entrySet()) {
                        Object v = e.getValue();
                        String side = v == null ? null : v.toString();
                        if ("top".equals(side) || "bottom".equals(side)) {
                            DIRECTIONS.put(e.getKey().toString(), side);
                        }
                    }
                }
                Object excl = root.get("excluded");
                if (excl instanceof Iterable) {
                    for (Object o : (Iterable<?>) excl) {
                        if (o != null) EXCLUDED.add(o.toString());
                    }
                }
                Object pris = root.get("priorities");
                if (pris instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) pris).entrySet()) {
                        if (e.getValue() != null) {
                            try {
                                PRIORITIES.put(e.getKey().toString(), ((Number) e.getValue()).intValue());
                            } catch (ClassCastException ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("directions", new LinkedHashMap<>(DIRECTIONS));
        root.put("excluded", new LinkedHashSet<>(EXCLUDED));
        root.put("priorities", new LinkedHashMap<>(PRIORITIES));
        try (Writer w = Files.newBufferedWriter(configPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        } catch (IOException ignored) {
        }
    }

    // ---------------- 查询 ----------------
    /** 该类型是否被模组【内置永久排除】。与 isExcluded（用户“X”删除）不同，
     *  内置排除不进入配置界面、不随“全部重置”清除，是发布期的硬性过滤规则。 */
    public static boolean isDefaultExcluded(String regId) {
        return BUILTIN_EXCLUDED.contains(regId);
    }

    /** 该类型是否被排除（不再扫描）。 */
    public static boolean isExcluded(String regId) {
        ensureLoaded();
        return EXCLUDED.contains(regId);
    }

    /** 该类型被用户指定的方向；未指定返回 null。 */
    public static String getDirection(String regId) {
        ensureLoaded();
        return DIRECTIONS.get(regId);
    }

    /** 已配置的全部方向覆盖（含手动添加类型）。 */
    public static Map<String, String> getDirections() {
        ensureLoaded();
        return new LinkedHashMap<>(DIRECTIONS);
    }

    /** 已排除的类型集合。 */
    public static Set<String> getExcluded() {
        ensureLoaded();
        return new LinkedHashSet<>(EXCLUDED);
    }

    // ---------------- 优先级 ----------------
    /** 该类型当前的显示优先级：用户指定覆盖 > 标准配置 > 兜底（未列出的 mod 类型）。 */
    public static int getPriority(String regId) {
        ensureLoaded();
        Integer v = PRIORITIES.get(regId);
        if (v != null) return v;
        Integer d = DEFAULT_PRIORITIES.get(regId);
        return d != null ? d : DEFAULT_PRIORITY_FALLBACK;
    }

    /** 是否为用户手动设置的优先级（区别于沿用标准/默认值）。 */
    public static boolean isPriorityCustom(String regId) {
        ensureLoaded();
        return PRIORITIES.containsKey(regId);
    }

    /** 设置/覆盖某类型的显示优先级（数字越小越靠前，最小 0）。 */
    public static void setPriority(String regId, int priority) {
        ensureLoaded();
        if (regId == null || regId.isEmpty()) return;
        if (priority < 0) priority = 0;
        PRIORITIES.put(regId, priority);
        EXCLUDED.remove(regId);
        persist();
    }

    /** 标记本次修改需要落盘：会话中只置脏标记，非会话（其它调用方）仍即时保存。 */
    private static void persist() {
        if (sessionActive) {
            sessionDirty = true;
        } else {
            save();
        }
    }

    // ---------------- 会话机制 ----------------
    /** 进入编辑会话：保存当前配置快照，此后修改仅存内存。 */
    public static void beginSession() {
        ensureLoaded();
        sessionActive = true;
        sessionDirty = false;
        snapDirections = new LinkedHashMap<>(DIRECTIONS);
        snapExcluded = new LinkedHashSet<>(EXCLUDED);
        snapPriorities = new LinkedHashMap<>(PRIORITIES);
    }

    /** 是否有未保存的修改（供界面显示提示等）。 */
    public static boolean isSessionDirty() {
        return sessionDirty;
    }

    /** 保存并退出会话：若有修改则写盘，释放快照。 */
    public static void commitSession() {
        if (!sessionActive) return;
        if (sessionDirty) save();
        endSession();
    }

    /** 不保存并退出会话：回滚本次会话全部修改，恢复进入界面时的配置。 */
    public static void discardSession() {
        if (!sessionActive) return;
        if (snapDirections != null) {
            DIRECTIONS.clear();
            DIRECTIONS.putAll(snapDirections);
            EXCLUDED.clear();
            EXCLUDED.addAll(snapExcluded);
            PRIORITIES.clear();
            PRIORITIES.putAll(snapPriorities);
        }
        endSession();
    }

    /** 会话内“全部重置”：清空全部用户覆盖，恢复到标准配置（随保存落盘）。 */
    public static void resetAll() {
        ensureLoaded();
        DIRECTIONS.clear();
        EXCLUDED.clear();
        PRIORITIES.clear();
        sessionDirty = true;
    }

    private static void endSession() {
        sessionActive = false;
        sessionDirty = false;
        snapDirections = null;
        snapExcluded = null;
        snapPriorities = null;
    }

    /** 重置单个类型为默认：取消排除、清除手动方向与手动优先级。 */
    public static void resetType(String regId) {
        ensureLoaded();
        if (regId == null || regId.isEmpty()) return;
        EXCLUDED.remove(regId);
        DIRECTIONS.remove(regId);
        PRIORITIES.remove(regId);
        persist();
    }

    // ---------------- 修改 ----------------
    /** 设置/覆盖某类型的方向；也可用于“+”添加一个新类型（同时取消排除）。 */
    public static void setDirection(String regId, String side) {
        ensureLoaded();
        if (regId == null || regId.isEmpty()) return;
        EXCLUDED.remove(regId);
        if ("top".equals(side) || "bottom".equals(side)) {
            DIRECTIONS.put(regId, side);
        } else {
            DIRECTIONS.remove(regId);
        }
        persist();
    }

    /** X 删除：该类型不再被扫描。 */
    public static void setExcluded(String regId, boolean excluded) {
        ensureLoaded();
        if (regId == null || regId.isEmpty()) return;
        if (excluded) {
            EXCLUDED.add(regId);
            DIRECTIONS.remove(regId);
        } else {
            EXCLUDED.remove(regId);
        }
        persist();
    }

    /** 清除全部配置。 */
    public static void clear() {
        ensureLoaded();
        DIRECTIONS.clear();
        EXCLUDED.clear();
        persist();
    }

    // 供外部测试/调试
    @SuppressWarnings("unused")
    public static ResourceLocation keyOf(String regId) {
        return ResourceLocation.tryParse(regId);
    }
}
