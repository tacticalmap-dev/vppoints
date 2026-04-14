package com.flowingsun.vppoints;

import com.flowingsun.vppoints.config.SquadConfig;
import com.flowingsun.vppoints.match.SquadMatchService;
import com.flowingsun.vppoints.net.SquadNetwork;
import com.flowingsun.vppoints.stats.PlayerCombatStatsService;
import com.flowingsun.vppoints.vp.VictoryMatchManager;
import com.flowingsun.vppoints.vp.VictoryPointProtection;
import com.flowingsun.vppoints.vp.VictoryPointRuntime;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Main Forge mod entry point.
 * Wires registries, config specs, network channel, and runtime services.
 */
@Mod(VpPointsMod.MOD_ID)
public class VpPointsMod {
    public static final String MOD_ID = "vppoints";

    public VpPointsMod() {
        // Register game content first so objects are available before gameplay systems boot.
        VictoryPointRuntime.register(FMLJavaModLoadingContext.get().getModEventBus());
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCreativeTab);

        // Expose both server/common and client-side config trees.
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, SquadConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT, SquadConfig.CLIENT_SPEC);

        SquadNetwork.init();

        // Runtime services are Forge-event driven.
        MinecraftForge.EVENT_BUS.register(SquadMatchService.INSTANCE);
        MinecraftForge.EVENT_BUS.register(VictoryMatchManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(PlayerCombatStatsService.INSTANCE);
        MinecraftForge.EVENT_BUS.register(new VictoryPointProtection());
    }

    private void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(VictoryPointRuntime.VICTORY_POINT_ITEM.get());
            event.accept(VictoryPointRuntime.NORMAL_POINT_ITEM.get());
            event.accept(VictoryPointRuntime.AMMO_POINT_ITEM.get());
            event.accept(VictoryPointRuntime.OIL_POINT_ITEM.get());
        }
    }
}


