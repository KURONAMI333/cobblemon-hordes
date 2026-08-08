package com.kuronami.cobblemonhordes;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The NeoForge side of {@link HordeConfig}: one whole number, a server-side decision.
 *
 * <p>{@code COMMON} rather than {@code SERVER} so the file sits in {@code config/} where a single
 * player can find it, and so a dedicated server does not have to push it to clients - nothing here
 * affects rendering.</p>
 */
public final class NeoForgeHordeConfig {

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue HORDE_CHANCE_PERCENT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        HORDE_CHANCE_PERCENT = builder
                .comment(
                        "Chance that an eligible wild encounter becomes a horde battle, as a percentage.",
                        "Eligible means the wild side is one Pokemon (not a legendary, mythical or Ultra",
                        "Beast) and your party can field two. Set to 0 to turn hordes off.")
                .translation("cobblemon_hordes.configuration.horde_chance_percent")
                .defineInRange("hordeChancePercent", HordeConfig.DEFAULT_HORDE_CHANCE_PERCENT, 0, 100);
        SPEC = builder.build();
    }

    private NeoForgeHordeConfig() {
    }

    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, SPEC);
        HordeConfig.bind(HORDE_CHANCE_PERCENT::get);
    }
}
