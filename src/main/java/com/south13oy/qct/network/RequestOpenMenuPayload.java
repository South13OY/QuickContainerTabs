package com.south13oy.qct.network;

import com.south13oy.qct.QCTMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 打开指定坐标菜单的请求载荷。
 */
public record RequestOpenMenuPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<RequestOpenMenuPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(QCTMod.MODID, "open_menu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestOpenMenuPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RequestOpenMenuPayload::pos,
                    RequestOpenMenuPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
