package com.flowingsun.vppoints.client;

import com.flowingsun.vppoints.vp.VictoryPointRuntime;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client bootstrap hooks: renderer registration, keybinds, and HUD event routing.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class SquadClient {
    public static final KeyMapping OPEN_HUD_CONFIG = new KeyMapping("key.vppoints.hud_config", InputConstants.KEY_O, "key.categories.vppoints");

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(VictoryPointRuntime.BE_TYPE.get(), VictoryPointBER::new);
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_HUD_CONFIG);
    }

    @Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ClientForgeEvents {
        private static final SquadHudOverlay HUD = new SquadHudOverlay();

        @SubscribeEvent
        public static void onKey(InputEvent.Key event) {
            if (OPEN_HUD_CONFIG.consumeClick() && net.minecraft.client.Minecraft.getInstance().screen == null) {
                net.minecraft.client.Minecraft.getInstance().setScreen(new HudConfigScreen(null));
            }
        }

        @SubscribeEvent
        public static void onHud(net.minecraftforge.client.event.RenderGuiOverlayEvent.Post event) {
            // Keep HUD render path centralized in one overlay implementation.
            HUD.onHud(event);
        }
    }
}

