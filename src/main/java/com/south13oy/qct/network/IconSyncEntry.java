package com.south13oy.qct.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 单条图标同步数据（维度 + 坐标 + 物品），供全量同步列表使用。
 */
public record IconSyncEntry(ResourceLocation dim, BlockPos pos, ItemStack stack) {

    public static final StreamCodec<RegistryFriendlyByteBuf, IconSyncEntry> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, IconSyncEntry::dim,
                    BlockPos.STREAM_CODEC, IconSyncEntry::pos,
                    ItemStack.OPTIONAL_STREAM_CODEC, IconSyncEntry::stack,
                    IconSyncEntry::new);
}
