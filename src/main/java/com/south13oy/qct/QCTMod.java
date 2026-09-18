package com.south13oy.qct;

import com.mojang.logging.LogUtils;
import com.south13oy.qct.client.ClientSetup;
import com.south13oy.qct.config.ModConfig;
import com.south13oy.qct.network.ModNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(QCTMod.MODID)
public class QCTMod {
    public static final String MODID = "qct";
    private static final Logger LOGGER = LogUtils.getLogger();

    public QCTMod(IEventBus modEventBus, ModContainer modContainer) {
        // 注册共享配置（common.toml）
        modContainer.registerConfig(Type.COMMON, ModConfig.SPEC);
        // 注册网络载荷
        modEventBus.addListener(ModNetworking::register);
        // 注册客户端按键映射（NeoForge 21.1：MOD 总线监听统一走 IEventBus，替代已弃用的 Bus.MOD 注解）
        modEventBus.addListener(ClientSetup::onRegisterKeyMappings);
        // 注册游戏事件：玩家登录 -> 全量下发图标
        NeoForge.EVENT_BUS.addListener(ModNetworking::onPlayerLoggedIn);
        LOGGER.info("Quick Container Tabs loaded.");
    }
}
