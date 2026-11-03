package dev.axiom;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class AxiomClient implements ClientModInitializer {
    public static final String MOD_ID = "axiom";
    public static KeyMapping OPEN_KEY;

    @Override
    public void onInitializeClient() {
        AxiomConfig.load();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        OPEN_KEY = new KeyMapping("key.axiom.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category);
        KeyMappingHelper.registerKeyMapping(OPEN_KEY);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (OPEN_KEY.consumeClick()) {
                if (mc.screen == null) mc.setScreenAndShow(new AxiomScreen());
            }
            BlockScanner.tick(mc);
        });

        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(EspRenderer::render);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BlockScanner.clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            AxiomConfig.save();
            EspRenderer.close();
        });
    }
}
