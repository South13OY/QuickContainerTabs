package com.south13oy.qct.network;

import com.south13oy.qct.QCTMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 -> 客户端：单条纯文本通知（客户端以物品栏上方 actionbar 字幕展示，
 * 与“已重置图标”提示同款样式）。
 */
public record NotifyPayload(String message) implements CustomPacketPayload {

    public static final Type<NotifyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(QCTMod.MODID, "notify"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NotifyPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, NotifyPayload::message,
                    NotifyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
