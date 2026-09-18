package com.south13oy.qct.network;

import com.south13oy.qct.QCTMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 服务端 -> 客户端：全量图标同步（玩家登录时下发）。
 */
public record SyncAllIconsPayload(List<IconSyncEntry> icons) implements CustomPacketPayload {

    public static final Type<SyncAllIconsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(QCTMod.MODID, "icon_sync_all"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncAllIconsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    IconSyncEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncAllIconsPayload::icons,
                    SyncAllIconsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
