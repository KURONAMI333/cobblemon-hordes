package com.kuronami.cobblemonhordes.battle;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.kuronami.cobblemonhordes.Constants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /cobblemonhordes forcehorde}: OP-only, starts a horde battle right now around the nearest
 * eligible wild Pokemon.
 *
 * <p>Whether a horde battle happens at all is normally decided by
 * {@link com.kuronami.cobblemonhordes.HordeConfig#hordeChancePercent()}, which is a question about how
 * often hordes occur. This command answers a different one - what the battle itself feels like - by
 * skipping the chance roll. Everything after that is shared: {@link HordeTrigger#assembleHorde} builds
 * the wild side and {@link HordeBattles#startHordeBattle} starts it, exactly as an ordinary encounter
 * does. Nothing about how a horde battle is built or fought is different here.</p>
 *
 * <p>Debug tooling, not a player-facing feature: gated to permission level 2 (OP) via
 * {@code requires}, so it does nothing unless deliberately invoked. Safe to ship - it never fires on
 * its own.</p>
 */
public final class HordeDebugCommand {

    /** How far from the player to look for a Pokemon to build a horde around. Independent of
     * {@link com.kuronami.cobblemonhordes.HordeConfig#searchRadiusBlocks()}, which governs how far a
     * horde's members may be from each other, not from the player. */
    private static final double LEADER_SEARCH_RADIUS_BLOCKS = 24.0;

    private HordeDebugCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cobblemonhordes")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("forcehorde").executes(HordeDebugCommand::run)));
    }

    private static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();

        PokemonEntity leader = nearestEligibleLeader(level, player);
        if (leader == null) {
            source.sendFailure(Component.literal(
                    "No wild Pokemon within " + (int) LEADER_SEARCH_RADIUS_BLOCKS + " blocks to build a horde around."));
            return 0;
        }

        List<PokemonEntity> reinforcements = new ArrayList<>();
        List<PokemonEntity> horde = HordeTrigger.assembleHorde(level, leader, player, reinforcements);
        if (horde == null) {
            discard(reinforcements);
            source.sendFailure(Component.literal("Could not assemble a horde around "
                    + leader.getPokemon().getSpecies().getName() + "."));
            return 0;
        }

        BattleStartResult result = HordeBattles.startHordeBattle(
                player, horde, Cobblemon.INSTANCE.getConfig().getDefaultFleeDistance(), false);
        if (!(result instanceof SuccessfulBattleStart started)) {
            discard(reinforcements);
            source.sendFailure(Component.literal(
                    "Horde battle did not start (" + result.getClass().getSimpleName()
                            + "). Party too small for a horde, or already in a battle?"));
            return 0;
        }

        PokemonBattle battle = started.getBattle();
        int hordeSize = horde.size();
        String speciesName = leader.getPokemon().getSpecies().getName();
        source.sendSuccess(() -> Component.literal(
                "Horde battle " + battle.getBattleId() + " started: " + hordeSize + " " + speciesName + "."), false);
        Constants.LOG.debug("[forcehorde] {} forced horde battle {} with {} {}",
                player.getGameProfile().getName(), battle.getBattleId(), hordeSize, speciesName);
        return 1;
    }

    @Nullable
    private static PokemonEntity nearestEligibleLeader(ServerLevel level, ServerPlayer player) {
        AABB searchBox = player.getBoundingBox().inflate(LEADER_SEARCH_RADIUS_BLOCKS);
        List<PokemonEntity> candidates = level.getEntitiesOfClass(
                PokemonEntity.class, searchBox, HordeDebugCommand::isEligibleLeader);
        candidates.sort(Comparator.comparingDouble(player::distanceToSqr));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean isEligibleLeader(PokemonEntity candidate) {
        if (!candidate.isAlive() || candidate.getBattleId() != null || candidate.isBusy()) {
            return false;
        }
        Pokemon pokemon = candidate.getPokemon();
        return pokemon.isWild() && pokemon.getCurrentHealth() > 0 && !HordeTrigger.isExcluded(pokemon);
    }

    /** Takes back anything that came out to fill a horde that then did not happen. */
    private static void discard(List<PokemonEntity> reinforcements) {
        for (PokemonEntity reinforcement : reinforcements) {
            reinforcement.discard();
        }
    }
}
