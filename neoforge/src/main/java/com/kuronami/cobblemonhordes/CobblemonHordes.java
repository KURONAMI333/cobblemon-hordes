package com.kuronami.cobblemonhordes;

import com.kuronami.cobblemonhordes.advancement.HordeAdvancements;
import com.kuronami.cobblemonhordes.battle.HordeDebugCommand;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(Constants.MOD_ID)
public class CobblemonHordes {

    public CobblemonHordes(IEventBus eventBus, ModContainer modContainer) {

        // This method is invoked by the NeoForge mod loader when it is ready
        // to load your mod. You can access NeoForge and Common code in this
        // project.

        NeoForgeHordeConfig.register(modContainer);

        // TRIGGER_TYPES is frozen before this constructor runs, so the horde advancement criterion
        // can only go in through the registration window RegisterEvent opens for it - a direct
        // Registry.register call here fails with "Registry is already frozen".
        eventBus.addListener(this::registerCriteria);

        // RegisterCommandsEvent fires on the game bus (NeoForge.EVENT_BUS), not the mod bus the
        // constructor receives.
        NeoForge.EVENT_BUS.addListener(this::registerCommands);

        // Use NeoForge to bootstrap the Common mod.
        CommonClass.init();

    }

    private void registerCriteria(RegisterEvent event) {
        event.register(Registries.TRIGGER_TYPE,
                helper -> helper.register(HordeAdvancements.ID, HordeAdvancements.HORDE_EVENT));
    }

    private void registerCommands(RegisterCommandsEvent event) {
        HordeDebugCommand.register(event.getDispatcher());
    }
}
