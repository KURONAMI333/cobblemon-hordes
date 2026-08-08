package com.kuronami.cobblemonhordes;

import com.kuronami.cobblemonhordes.advancement.HordeAdvancements;
import com.kuronami.cobblemonhordes.battle.HordeDebugCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public class CobblemonHordes implements ModInitializer {

    @Override
    public void onInitialize() {

        // This method is invoked by the Fabric mod loader when it is ready
        // to load your mod. You can access Fabric and Common code in this
        // project.

        // Fabric's onInitialize runs early enough that TRIGGER_TYPES is not frozen yet, unlike
        // NeoForge, which needs a RegisterEvent for the same registration (see the NeoForge
        // CobblemonHordes for why).
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, HordeAdvancements.ID, HordeAdvancements.HORDE_EVENT);

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> HordeDebugCommand.register(dispatcher));

        // Use Fabric to bootstrap the Common mod.
        Constants.LOG.info("Hello Fabric world!");
        CommonClass.init();
    }
}
