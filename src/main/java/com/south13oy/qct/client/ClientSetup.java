package com.south13oy.qct.client;

import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * 客户端 MOD 总线初始化：注册按键映射（B 打开配置界面）。
 * NeoForge 21.1 起 @EventBusSubscriber 的 MOD 总线已过时待删除，
 * 统一由 QCTMod 主类通过 IEventBus.addListener 注册本方法。
 */
public class ClientSetup {

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(QCTKeys.OPEN_CONFIG);
    }
}
