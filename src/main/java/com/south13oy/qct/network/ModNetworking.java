package com.south13oy.qct.network;

import com.south13oy.qct.QCTMod;
import com.south13oy.qct.config.ModConfig;
import com.south13oy.qct.util.MenuBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 客户端 -> 服务端：请求切换打开指定坐标的容器/工作方块菜单。
 */
public class ModNetworking {

    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(QCTMod.MODID).versioned("1");
        registrar.playToServer(RequestOpenMenuPayload.TYPE,
                RequestOpenMenuPayload.STREAM_CODEC,
                handler());
    }

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
            player.openMenu(provider);
        }
    }
}
