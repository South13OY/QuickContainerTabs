package com.south13oy.qct.network;

import com.south13oy.qct.QCTMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 服务端 -> 客户端：单条图标变更广播（全服同步）。
 */
public record IconSyncPayload(ResourceLocation dim, BlockPos pos, ItemStack stack) implements CustomPacketPayload {

    public static final Type<IconSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(QCTMod.MODID, "icon_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, IconSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, IconSyncPayload::dim,
                    BlockPos.STREAM_CODEC, IconSyncPayload::pos,
                    ItemStack.OPTIONAL_STREAM_CODEC, IconSyncPayload::stack,
                    IconSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
