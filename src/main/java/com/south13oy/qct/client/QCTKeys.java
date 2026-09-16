package com.south13oy.qct.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * QCT 按键绑定：B 键打开“容器上下分类”配置界面。
 */
public class QCTKeys {
    public static final KeyMapping OPEN_CONFIG = new KeyMapping(
            "key.qct.open_config",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.qct");
}
