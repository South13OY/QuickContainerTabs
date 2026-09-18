package com.south13oy.qct.network;

import com.south13oy.qct.QCTMod;
import com.south13oy.qct.config.ModConfig;
import com.south13oy.qct.icon.IconStore;
import com.south13oy.qct.server.ServerIconStore;
import com.south13oy.qct.util.MenuBridge;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.util.List;

/**
 * 网络注册与处理。
 * <ul>
 *   <li>open_menu（C2S）：请求切换打开指定坐标容器菜单；</li>
 *   <li>icon_change（C2S）：请求修改容器标签页图标（全服同步）；</li>
 *   <li>icon_sync（S2C）：单条图标变更广播；</li>
 *   <li>icon_sync_all（S2C）：玩家登录时全量图标下发。</li>
 * </ul>
 */
public class ModNetworking {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(QCTMod.MODID).versioned("2");
        registrar.playToServer(RequestOpenMenuPayload.TYPE,
                RequestOpenMenuPayload.STREAM_CODEC,
                handler());
        registrar.playToServer(IconChangePayload.TYPE,
                IconChangePayload.STREAM_CODEC,
                iconChangeHandler());
        registrar.playToClient(IconSyncPayload.TYPE,
                IconSyncPayload.STREAM_CODEC,
                iconSyncHandler());
        registrar.playToClient(SyncAllIconsPayload.TYPE,
                SyncAllIconsPayload.STREAM_CODEC,
                syncAllHandler());
        registrar.playToClient(NotifyPayload.TYPE,
                NotifyPayload.STREAM_CODEC,
                notifyHandler());
    }

    // ---------------- open_menu ----------------

    private static IPayloadHandler<RequestOpenMenuPayload> handler() {
        return (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                openNearbyMenu(serverPlayer, payload.pos());
            }
        });
    }

    /** 服务端安全校验：坐标已加载、距离合法、确为可打开方块。 */
    private static void openNearbyMenu(ServerPlayer player, BlockPos pos) {
        if (player == null) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        int radius = ModConfig.VALUES.scanRadius.get();
        double maxDist = radius + 2;
        double distSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq > maxDist * maxDist) return;
        if (!level.isLoaded(pos)) return;

        BlockState state = level.getBlockState(pos);
        // MenuBridge 统一解析（含末影箱特判）：与服务端切换打开行为保持一致——
        // 末影箱本身无 getMenuProvider，不走特判就无法通过标签切换到末影箱界面。
        MenuProvider provider = MenuBridge.providerFor(level, pos, state);
        if (provider != null) {
            // 独占逻辑方块（如 workbench 类单用户独占）正被其它玩家使用：
            // 方块自身的 useWithoutItem 会被短路（isOccupied()==true 直接返回 SUCCESS），
            // 此时继续走原流程只会“关掉当前背包却开不出新菜单”。提前识别并向玩家
            // 给出与“已重置图标”同款的物品栏上方字幕提醒（NotifyPayload S2C）。
            if (MenuBridge.isOccupiedByOther(level, pos, state, player)) {
                player.closeContainer();
                PacketDistributor.sendToPlayer(player, new NotifyPayload("目标方块正在被他人使用"));
                return;
            }
            try {
                player.closeContainer();
                // 贴近原版右键路径：调用方块的 useWithoutItem，让方块自身完成菜单数据同步。
                // 不能直接 player.openMenu(provider)：NeoForge 的 ServerPlayer.openMenu 仅当
                // provider 实现了 IMenuProviderExtension.getMenuProvider() 时才会带数据发送
                // ClientboundOpenScreenPacket；framework 系（如 refurbished_furniture 的
                // workbench）的 MenuType 用 StreamCodec 解码 extraData，直连 openMenu 会发出
                // null 数据包，客户端在 MenuType.create -> IContainerFactory.create 解码 NPE
                // 被踢线。
                // 点击面取方块水平朝向的反面（正面）：MrCrayfish 家具（储物柜/冰箱/冷冻层等）
                // 的 useWithoutItem 要求 hit 面 == DIRECTION.getOpposite() 才打开菜单，
                // 固定 Direction.UP 会导致这些方块直接 PASS 而打不开。
                BlockHitResult hit = new BlockHitResult(
                        new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                        hitDirectionFor(state), pos, false);
                InteractionResult result = state.useWithoutItem(level, player, hit);
                // useWithoutItem 可能因方向或其它条件不满足而返回 PASS（方块未开菜单）。
                // 回退为直接打开 provider（普通容器安全）；framework 数据菜单除外——
                // 直连 openMenu 会发 null extraData 导致客户端解码 NPE 被踢线，
                // 宁可保持打不开也不冒踢线风险。
                if (result == InteractionResult.PASS && !MenuBridge.isFrameworkDataMenu(provider)) {
                    player.openMenu(provider);
                }
            } catch (Exception e) {
                // 打开第三方自定义菜单可能因数据未同步/解码失败抛异常，
                // 若直接让异常上抛会导致玩家被踢出存档（网络协议错误）。
                // 这里隔离异常并记录，保证标签切换失败只影响本次打开。
                LOGGER.warn("[QCT] 打开菜单失败已隔离，pos={}，block={}", pos, state.getBlock(), e);
            }
        }
    }

    // ---------------- icon 全服同步 ----------------

    /**
     * 计算点击方块时应使用的“击中面”方向。
     * <p>部分第三方方块（如 MrCrayfish 家具重制的储物柜/冰箱/冷冻层）的
     * useWithoutItem 要求点击面等于方块水平朝向的反面（正面）才打开菜单，
     * 否则直接 PASS。这里从方块状态中解析水平 facing 属性并取反向；
     * 无水平 facing 属性的方块退回 Direction.UP（原逻辑）。
     */
    private static Direction hitDirectionFor(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (!(property instanceof DirectionProperty dirProperty)) continue;
            java.util.Collection<Direction> values = dirProperty.getPossibleValues();
            if (values.isEmpty()) continue;
            boolean allHorizontal = true;
            for (Direction d : values) {
                if (!d.getAxis().isHorizontal()) {
                    allHorizontal = false;
                    break;
                }
            }
            if (!allHorizontal) continue;
            Direction facing = state.getValue(dirProperty);
            return facing.getOpposite();
        }
        return Direction.UP;
    }

    /** 客户端发送图标修改请求（副本数固定 1）。 */
    public static void sendIconChange(ResourceKey<Level> dim, BlockPos pos, ItemStack stack) {
        PacketDistributor.sendToServer(new IconChangePayload(
                dim.location(), pos, stack.copyWithCount(1)));
    }

    /** 服务端：玩家修改图标 -> 校验 -> 权威存储 -> 全服广播。 */
    private static IPayloadHandler<IconChangePayload> iconChangeHandler() {
        return (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                if (!isValidIconTarget(serverPlayer, payload.pos())) return;
                ServerIconStore.setIcon(serverPlayer.level().registryAccess(),
                        payload.dim(), payload.pos(), payload.stack());
                PacketDistributor.sendToAllPlayers(
                        new IconSyncPayload(payload.dim(), payload.pos(), payload.stack()));
            }
        });
    }

    /** 服务端校验：距离合法、区块已加载、确为可打开容器方块（防止恶意刷任意坐标图标）。 */
    private static boolean isValidIconTarget(ServerPlayer player, BlockPos pos) {
        if (player == null) return false;
        if (!(player.level() instanceof ServerLevel level)) return false;
        int radius = ModConfig.VALUES.scanRadius.get();
        double maxDist = radius + 2;
        double distSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq > maxDist * maxDist) return false;
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return MenuBridge.providerFor(level, pos, state) != null;
    }

    /** 客户端：收到单条图标同步 -> 合并进本地缓存。 */
    private static IPayloadHandler<IconSyncPayload> iconSyncHandler() {
        return (payload, context) -> context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            IconStore.applyServerSync(mc, List.of(new IconStore.ServerEntry(
                    ResourceKey.create(Registries.DIMENSION, payload.dim()),
                    payload.pos(), payload.stack())));
        });
    }

    /** 客户端：收到全量图标同步（登录时）-> 合并进本地缓存。 */
    private static IPayloadHandler<SyncAllIconsPayload> syncAllHandler() {
        return (payload, context) -> context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            List<IconStore.ServerEntry> entries = payload.icons().stream()
                    .map(e -> new IconStore.ServerEntry(
                            ResourceKey.create(Registries.DIMENSION, e.dim()),
                            e.pos(), e.stack()))
                    .toList();
            IconStore.applyServerSync(mc, entries);
        });
    }

    /** 客户端：收到纯文本通知 -> 物品栏上方 actionbar 字幕（与“已重置图标”提示同款样式）。 */
    private static IPayloadHandler<NotifyPayload> notifyHandler() {
        return (payload, context) -> context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || payload.message() == null || payload.message().isEmpty()) return;
            mc.player.displayClientMessage(Component.literal(payload.message()), true);
        });
    }

    /** 玩家登录 -> 服务端全量下发当前所有图标。 */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            List<IconSyncEntry> all = ServerIconStore.getAll(sp.level().registryAccess()).entrySet().stream()
                    .map(e -> {
                        int idx = e.getKey().lastIndexOf(':');
                        ResourceLocation dim = ResourceLocation.tryParse(e.getKey().substring(0, idx));
                        String[] xyz = e.getKey().substring(idx + 1).split(",");
                        BlockPos pos = new BlockPos(
                                Integer.parseInt(xyz[0]),
                                Integer.parseInt(xyz[1]),
                                Integer.parseInt(xyz[2]));
                        return new IconSyncEntry(dim, pos, e.getValue());
                    })
                    .toList();
            PacketDistributor.sendToPlayer(sp, new SyncAllIconsPayload(all));
        }
    }
}
