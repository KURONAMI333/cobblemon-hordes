package com.kuronami.cobblemonhordes.battle;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleStartedEvent;
import com.cobblemon.mod.common.api.reactive.ObservableSubscription;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.kuronami.cobblemonhordes.Constants;
import com.kuronami.cobblemonhordes.HordeConfig;
import kotlin.Unit;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns an ordinary wild encounter into a horde by swapping the battle out as it starts.
 *
 * <p>{@code BattleRegistry.startBattle} runs every battle through
 * {@link CobblemonEvents#BATTLE_STARTED_PRE} before it creates anything, and that event is
 * cancelable, so no mixin is needed here: build the horde battle, and cancel the single one only
 * once the horde is actually running.</p>
 *
 * <h2>Why this cannot loop</h2>
 *
 * <p>{@code startBattle} skips the event outright when {@code canPreempt} is false - it calls
 * {@code start()} and returns before {@code BattleStartedEvent.Pre} is ever constructed.
 * {@link HordeBattles#startHordeBattle} passes false, so the battle this handler creates cannot come
 * back through this handler. That is structural rather than a flag that has to be cleared correctly
 * on every path.</p>
 *
 * <p>The eligibility check below is a second, independent stop: it only accepts a wild side made of
 * one actor holding exactly one Pokemon, and a horde battle's wild side is one actor holding two.
 * Even a third-party mod re-posting the event with a horde battle would be turned away.</p>
 */
public final class HordeTrigger {

    /** Second horde member; the wild Pokemon that was already being fought is the first. */
    private static final int RECRUITS_NEEDED = HordeBattles.slotsPerActor() - 1;

    /**
     * How far from the leader a reinforcement comes out, in blocks, on top of the leader's own width.
     *
     * <p>Far enough not to be inside the leader, close enough to read as the same patch of grass.</p>
     */
    private static final double EMERGE_GAP_BLOCKS = 1.0;

    /** Two horde members closer together than this are standing in each other. */
    private static final double MIN_SEPARATION_BLOCKS = 1.0;

    @Nullable
    private static ObservableSubscription<BattleStartedEvent.Pre> subscription;

    private HordeTrigger() {
    }

    /** Idempotent: both loaders route through one common entry point, and tests re-enter it. */
    public static synchronized void register() {
        if (subscription != null) {
            return;
        }
        subscription = CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.NORMAL, event -> {
            try {
                tryUpgradeToHorde(event);
            } catch (Exception exception) {
                // A throw here would propagate into BattleRegistry.startBattle and take the
                // encounter down with it. Losing the horde is the acceptable failure.
                Constants.LOG.error("Failed to consider a horde battle; leaving the encounter alone", exception);
            }
            return Unit.INSTANCE;
        });
    }

    private static void tryUpgradeToHorde(BattleStartedEvent.Pre event) {
        PokemonBattle battle = event.getBattle();
        if (!battle.isPvW()) {
            return;
        }
        if (battle.getFormat().getBattleType().getSlotsPerActor() != 1) {
            return;
        }

        BattleActor wildActor = soleWildActor(battle);
        if (wildActor == null || wildActor.getPokemonList().size() != 1) {
            return;
        }
        PokemonEntity leader = wildActor.getPokemonList().get(0).getEntity();
        if (leader == null || !(leader.level() instanceof ServerLevel level)) {
            return;
        }
        if (isExcluded(leader.getPokemon())) {
            return;
        }

        ServerPlayer player = solePlayer(battle);
        if (player == null || !HordeBattles.partyCanFaceHorde(player)) {
            return;
        }

        int chancePercent = HordeConfig.hordeChancePercent();
        if (chancePercent <= 0 || level.getRandom().nextInt(100) >= chancePercent) {
            return;
        }

        // Anything spawned to fill the wild side has to go again unless the battle actually starts,
        // or the player is left standing next to a Pokemon that came out of nowhere for nothing.
        List<PokemonEntity> reinforcements = new ArrayList<>();
        boolean hordeStarted = false;
        try {
            List<PokemonEntity> horde = assembleHorde(level, leader, player, reinforcements);
            if (horde == null) {
                // Every check above said this encounter should have been a horde, so the player gets
                // a plain single battle where the mod meant to give them a group. Silence here makes
                // that indistinguishable from the trigger never having fired at all.
                Constants.LOG.warn("Horde battle abandoned at {}: the wild side could not be filled for {}"
                                + " (nothing of its kind within {} blocks, and no clear ground to bring one out on)",
                        leader.blockPosition(), leader.getPokemon().getSpecies().getName(),
                        HordeConfig.searchRadiusBlocks());
                return;
            }

            BattleStartResult result = HordeBattles.startHordeBattle(
                    player,
                    horde,
                    Cobblemon.INSTANCE.getConfig().getDefaultFleeDistance(),
                    false);
            if (!(result instanceof SuccessfulBattleStart started)) {
                // Nothing was created, so the ordinary encounter is left to run. Cancelling here would
                // mean the player pokes a Pokemon and nothing happens at all.
                Constants.LOG.warn("Horde battle abandoned at {}: Cobblemon refused to start it for {} ({})",
                        leader.blockPosition(), leader.getPokemon().getSpecies().getName(),
                        result.getClass().getSimpleName());
                return;
            }

            // Cobblemon's own wild-battle entry point reports a cancelled start to the player as red
            // text. There is nothing to report - the player is in a battle - so the message is emptied.
            event.setReason(Component.empty());
            event.cancel();
            hordeStarted = true;
            Constants.LOG.debug("Horde battle {} replaced a wild encounter with {} {} ({} of them came out to join it)",
                    started.getBattle().getBattleId(), horde.size(),
                    leader.getPokemon().getSpecies().getName(), reinforcements.size());
        } finally {
            if (!hordeStarted) {
                for (PokemonEntity reinforcement : reinforcements) {
                    reinforcement.discard();
                }
            }
        }
    }

    /**
     * The horde that will face the player: the Pokemon already being fought, plus enough others to
     * fill the wild side.
     *
     * <p>Wild Pokemon of the same species standing nearby are used first, so a group that is really
     * there is really what is fought. When there are not enough - which is the ordinary case, because
     * Cobblemon's spawner picks each Pokemon independently and its one herd spawn detail
     * ({@code 0000_pidgey_herd.json}) ships disabled - the rest come out to join it, one at a time.
     * From the player's side that is what a wild double battle has always looked like: you walk into
     * the grass and a second one is there too. Whether it was standing there a moment ago is not
     * something the player can see.</p>
     *
     * <p>Each one that comes out is a clone of the leader, so an add-on's species needs no entry
     * anywhere for this to work on it.</p>
     *
     * <p>Returns null if the side could not be filled, in which case the encounter has to stay an
     * ordinary single battle. Every Pokemon this spawns is added to {@code reinforcements}, and the
     * caller has to discard them unless the battle starts - including on the null return.</p>
     *
     * <p>The leader is taken as given: whether its species belongs in a horde at all is
     * {@link #isExcluded}, which both callers ask before they get here.</p>
     */
    @Nullable
    public static List<PokemonEntity> assembleHorde(
            ServerLevel level,
            PokemonEntity leader,
            @Nullable ServerPlayer player,
            List<PokemonEntity> reinforcements) {
        List<PokemonEntity> horde = new ArrayList<>();
        horde.add(leader);
        horde.addAll(nearbyRecruits(level, leader));

        while (horde.size() < HordeBattles.slotsPerActor()) {
            PokemonEntity reinforcement = spawnReinforcement(level, leader, player, horde);
            if (reinforcement == null) {
                return null;
            }
            reinforcements.add(reinforcement);
            horde.add(reinforcement);
        }
        return horde;
    }

    /**
     * Wild Pokemon of the leader's species already standing within
     * {@link HordeConfig#searchRadiusBlocks()}, nearest first, at most as many as the horde needs.
     */
    private static List<PokemonEntity> nearbyRecruits(ServerLevel level, PokemonEntity leader) {
        double radius = HordeConfig.searchRadiusBlocks();
        AABB searchBox = leader.getBoundingBox().inflate(radius);
        List<PokemonEntity> recruits = level.getEntitiesOfClass(
                PokemonEntity.class,
                searchBox,
                candidate -> candidate != leader && canJoinHorde(candidate, leader));
        recruits.sort(Comparator.comparingDouble(leader::distanceToSqr));
        return recruits.subList(0, Math.min(RECRUITS_NEEDED, recruits.size()));
    }

    /**
     * One more of the leader, standing next to it.
     *
     * <p>{@link Pokemon#clone} rather than {@code Species.create}: a regional form is carried by the
     * species features the leader holds ({@code FlagSpeciesFeature}, e.g. {@code alolan}), and
     * {@code PokemonProperties.create} runs {@code initialize()} last, which re-derives the form from
     * whatever features the fresh Pokemon happens to have. Setting {@code form} on the properties
     * therefore does not survive, and {@code PokemonProperties.aspects} is never applied to a Pokemon
     * at all. Cloning carries species, form, features, forced aspects, shininess, level and scale in
     * one step, and is the only route that does so for a species this mod has never heard of.</p>
     *
     * <p>Healed first: the clone would otherwise inherit the leader's damage and status, and a
     * Pokemon that has only just appeared has not been in a fight.</p>
     *
     * <p>The result is checked against {@link #canJoinHorde} rather than assumed - the same rule the
     * recruits pass - so a spawn that came out unusable is discarded instead of joining.</p>
     */
    @Nullable
    private static PokemonEntity spawnReinforcement(
            ServerLevel level,
            PokemonEntity leader,
            @Nullable ServerPlayer player,
            List<PokemonEntity> alreadyStanding) {
        Pokemon companion = leader.getPokemon().clone(true, level.registryAccess());
        companion.heal();

        Vec3 position = emergePosition(leader, player, alreadyStanding);
        PokemonEntity entity = companion.sendOut(level, position, null, spawned -> Unit.INSTANCE);
        if (entity == null) {
            // POKEMON_SENT_PRE was cancelled by something else. Nothing was added to the world.
            return null;
        }
        if (!canJoinHorde(entity, leader)) {
            entity.discard();
            return null;
        }
        return entity;
    }

    /**
     * Where a reinforcement comes out.
     *
     * <p>Beside the leader on the far side from the player, so it reads as something that was in the
     * grass behind it rather than something dropped on the player's head. Blocked spots are not
     * handled here: {@code Pokemon.sendOut} runs the position through
     * {@code getAdjustedSendoutPosition} (water surfaces) and {@code setPositionSafely} (a BFS out of
     * solid blocks), which is the same treatment every other Cobblemon spawn gets. All that is left
     * is not to stand inside a horde member, which the quarter turns cover.</p>
     */
    private static Vec3 emergePosition(
            PokemonEntity leader, @Nullable ServerPlayer player, List<PokemonEntity> alreadyStanding) {
        Vec3 origin = leader.position();
        Vec3 awayFromPlayer = player == null ? Vec3.ZERO : origin.subtract(player.position());
        Vec3 flat = new Vec3(awayFromPlayer.x, 0.0, awayFromPlayer.z);
        if (flat.lengthSqr() < 1.0E-4) {
            // Player is standing on top of the leader, or there is no player to face away from.
            flat = new Vec3(1.0, 0.0, 0.0);
        }
        Vec3 step = flat.normalize().scale(leader.getBbWidth() + EMERGE_GAP_BLOCKS);

        for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
            Vec3 candidate = origin.add(step);
            if (isClearOf(candidate, alreadyStanding)) {
                return candidate;
            }
            step = new Vec3(-step.z, 0.0, step.x);
        }
        return origin.add(step);
    }

    private static boolean isClearOf(Vec3 position, List<PokemonEntity> alreadyStanding) {
        for (PokemonEntity standing : alreadyStanding) {
            if (standing.position().distanceToSqr(position) < MIN_SEPARATION_BLOCKS * MIN_SEPARATION_BLOCKS) {
                return false;
            }
        }
        return true;
    }

    private static boolean canJoinHorde(PokemonEntity candidate, PokemonEntity leader) {
        if (!candidate.isAlive() || candidate.getBattleId() != null || candidate.isBusy()) {
            return false;
        }
        Pokemon pokemon = candidate.getPokemon();
        if (!pokemon.isWild() || pokemon.getCurrentHealth() <= 0) {
            return false;
        }
        return pokemon.getSpecies().getResourceIdentifier()
                .equals(leader.getPokemon().getSpecies().getResourceIdentifier());
    }

    /**
     * Species that never appear in a horde.
     *
     * <p>Stated as an exclusion, and read off the species' own labels rather than a list of names.
     * The label lives in the species JSON ({@code FormData.labels} falling back to
     * {@code Species.labels}), which is the same schema a data pack add-on writes, so a species some
     * add-on introduces is eligible for hordes the day it is installed and no entry has to be made
     * for it here. That is the whole reason this is not an inclusion list.</p>
     *
     * <p>Legendaries and mythicals do not appear in groups in the mainline games either. Ultra
     * beasts belong to the same category.</p>
     *
     * <p>Package-private (not private) so {@link HordeDebugCommand} can apply the same rule when
     * picking a leader for a forced horde battle, instead of restating it.</p>
     */
    static boolean isExcluded(Pokemon pokemon) {
        return pokemon.isLegendary() || pokemon.isMythical() || pokemon.isUltraBeast();
    }

    @Nullable
    private static BattleActor soleWildActor(PokemonBattle battle) {
        BattleActor wildActor = null;
        for (BattleSide side : battle.getSides()) {
            for (BattleActor actor : side.getActors()) {
                if (actor.getType() != ActorType.WILD) {
                    continue;
                }
                if (wildActor != null) {
                    return null;
                }
                wildActor = actor;
            }
        }
        return wildActor;
    }

    @Nullable
    private static ServerPlayer solePlayer(PokemonBattle battle) {
        ServerPlayer found = null;
        for (ServerPlayer player : battle.getPlayers()) {
            if (found != null) {
                return null;
            }
            found = player;
        }
        return found;
    }
}
