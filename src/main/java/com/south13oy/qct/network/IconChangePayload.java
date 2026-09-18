package com.south13oy.qct.network;

import com.south13oy.qct.QCTMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端 -> 服务端：请求修改指定容器标签页图标（全服同步）。
 */
public record IconChangePayload(ResourceLocation dim, BlockPos pos, ItemStack stack) implements CustomPacketPayload {

    public static final Type<IconChangePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(QCTMod.MODID, "icon_change"));

    public static final StreamCodec<RegistryFriendlyByteBuf, IconChangePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, IconChangePayload::dim,
                    BlockPos.STREAM_CODEC, IconChangePayload::pos,
                    ItemStack.OPTIONAL_STREAM_CODEC, IconChangePayload::stack,
                    IconChangePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
