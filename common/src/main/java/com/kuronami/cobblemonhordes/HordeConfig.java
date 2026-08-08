package com.kuronami.cobblemonhordes;

import java.util.function.IntSupplier;

/**
 * The one number the horde trigger is tuned by, plus one internal constant it is not.
 *
 * <p>The percent is a whole number on purpose. A float dressed up as a percentage shows up in the
 * config screen as {@code 0.3499999940395355}, and a horde is either common or rare - there is
 * nothing in between worth a decimal place.</p>
 *
 * <p>Common code cannot see either loader's config system, so each loader binds its own value
 * through {@link #bind}. Until it does, the default applies, which is what the game tests run on.</p>
 */
public final class HordeConfig {

    /**
     * Percent of eligible encounters that turn into a horde.
     *
     * <p>This is the only thing standing between an ordinary wild encounter and a horde. Eligible
     * means the wild side is one unexcluded Pokemon and the player can field two - it does not mean a
     * second Pokemon happens to be standing nearby, because
     * {@link com.kuronami.cobblemonhordes.battle.HordeTrigger#assembleHorde} brings one out when none
     * is. So this reads directly as "roughly this share of wild encounters are hordes".</p>
     *
     * <p>Provisional. What this should be is a question about how the game feels, and kura settles
     * that by playing (gate 2c). Fifteen is a starting point, not a conclusion: noticeable across a
     * play session without turning every third encounter into one.</p>
     */
    public static final int DEFAULT_HORDE_CHANCE_PERCENT = 15;

    /**
     * How far to look for a horde member that is already there, in blocks.
     *
     * <p>Only decides whether a real neighbour is used instead of one coming out to join the fight;
     * the horde happens either way, and the player sees the same thing - two Pokemon of the same
     * species - regardless of which path filled the second slot. Nothing to tune, so it is a constant
     * rather than a config entry. Twelve blocks is wide enough to catch a Pokemon of the same species
     * from the same spawning pass and narrow enough that one across the field is not dragged into the
     * fight.</p>
     */
    public static final int DEFAULT_SEARCH_RADIUS_BLOCKS = 12;

    private static volatile IntSupplier hordeChancePercent = () -> DEFAULT_HORDE_CHANCE_PERCENT;

    private HordeConfig() {
    }

    /** Points the chance at a loader's config. A supplier, so config reloads are picked up. */
    public static void bind(IntSupplier chancePercent) {
        hordeChancePercent = chancePercent;
    }

    /** 0 disables hordes entirely; 100 makes every eligible encounter one. */
    public static int hordeChancePercent() {
        return clamp(hordeChancePercent.getAsInt(), 0, 100);
    }

    public static int searchRadiusBlocks() {
        return DEFAULT_SEARCH_RADIUS_BLOCKS;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
