package com.kuronami.cobblemonhordes.client;

import com.kuronami.cobblemonhordes.Constants;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Puts the config on the mod list screen. NeoForge draws it from the spec; nothing else is needed. */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class CobblemonHordesClient {

    public CobblemonHordesClient(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
