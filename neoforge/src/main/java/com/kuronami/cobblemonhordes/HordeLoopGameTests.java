package com.kuronami.cobblemonhordes;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.CobblemonNetwork;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.battles.model.ai.BattleAI;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokeball.PokeBallCaptureCalculatedEvent;
import com.cobblemon.mod.common.api.pokeball.PokeBalls;
import com.cobblemon.mod.common.api.pokeball.catching.CaptureContext;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.reactive.ObservableSubscription;
import com.cobblemon.mod.common.api.storage.party.PartyStore;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.ForcePassActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionRequest;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor;
import com.cobblemon.mod.common.battles.ai.RandomBattleAI;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.net.PacketRegisterInfo;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.cobblemon.mod.common.battles.BattleSide;
import com.kuronami.cobblemonhordes.battle.HordeBattleAI;
import com.kuronami.cobblemonhordes.battle.HordeBattleActor;
import com.kuronami.cobblemonhordes.battle.HordeBattles;
import com.kuronami.cobblemonhordes.battle.HordeTrigger;
import kotlin.Unit;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Drives the whole horde loop headlessly: a player-vs-horde battle starts, resolves turns, survives
 * one wild Pokemon fainting, gives up its last member to a Poke Ball, and flees when the player
 * walks away.
 *
 * <p>The player is {@link GameTestHelper#makeMockServerPlayerInLevel()}, which registers the mock in
 * the real player list, so Cobblemon's {@code uuid.getPlayer()} resolves it and the ordinary
 * {@link PlayerBattleActor} can be used unchanged. That matters: {@code checkFlee} reaches for
 * {@code actors.filterIsInstance<PlayerBattleActor>().first()}, so a stand-in actor would turn the
 * flee path into a {@code NoSuchElementException} rather than a flee.</p>
 *
 * <p>Choices are submitted from {@link Rig#drive()} on a later tick rather than from inside
 * {@code sendUpdate}. This is the one thing the AI-vs-AI spike could not do, and the reason it
 * deadlocked: {@code BattleActor.setActionResponses} ends with {@code checkForInputDispatch()},
 * which clears every actor's request as soon as no actor is left waiting - so an actor that answers
 * synchronously inside its own {@code turn()} destroys the request of every actor after it in the
 * list. A real client answers a tick or more later, and so does this rig.</p>
 */
@GameTestHolder(Constants.MOD_ID)
public class HordeLoopGameTests {

    private static final String TAG = "[HORDELOOP]";

    /**
     * Ceiling for the runs that have to see a battle all the way out.
     *
     * <p>A tick budget is the wrong unit for those, and generously raising it does almost nothing.
     * {@code WaitDispatch.canProceed} compares {@code System.currentTimeMillis()} against a deadline,
     * so Cobblemon paces its battle messages in wall-clock seconds while the game test server ticks
     * flat out. Every faint, damage number and chat line is one of those waits, and nothing is
     * written to Showdown until the queue drains, which puts a turn at a second or two of real time
     * no matter how fast the ticks come. Those runs wait for the rig to finish rather than counting
     * ticks; this is only the point at which the wait is declared hung.</p>
     */
    private static final int LONG_TIMEOUT_TICKS = 120000;

    private static final int PLAYER_LEVEL = 60;
    private static final int WILD_LEVEL = 5;

    /**
     * One heavy hitter and one that cannot knock anything out.
     *
     * <p>Two level 60 Pokemon against two level 5 ones end the battle on turn one - both wild
     * members faint at once and there is nothing left to catch. Pairing the Charizard with a
     * Caterpie of the horde's own level keeps the horde down to one casualty per turn, which is the
     * state stages 3 and 4 are about.</p>
     */
    private static final String[] CAPTURE_PARTY_SPECIES = { "charizard", "caterpie" };
    private static final int[] CAPTURE_PARTY_LEVELS = { PLAYER_LEVEL, WILD_LEVEL };

    /** Evenly matched, so the fight lasts long enough to watch turns resolve repeatedly. */
    private static final String[] EVEN_PARTY_SPECIES = { "caterpie", "weedle" };
    private static final int[] EVEN_PARTY_LEVELS = { WILD_LEVEL, WILD_LEVEL };

    /**
     * Two Pokemon that finish the horde off and are never in danger themselves.
     *
     * <p>Both have to survive: {@code awardExperienceToFaintedPokemon} adds a faint-ordering rule
     * that would make the expected figure a different sum for a party member that went down.</p>
     */
    private static final String[] WIPE_PARTY_SPECIES = { "charizard", "blastoise" };
    private static final int[] WIPE_PARTY_LEVELS = { PLAYER_LEVEL, PLAYER_LEVEL };

    /**
     * Both runs use a real flee distance, including the capture one.
     *
     * <p>Disabling the flee check for the capture run would hide the window that matters: once the
     * captured Pokemon's entity leaves the world {@code HordeBattleActor.getWorldAndPosition()}
     * returns null, the actor is filtered out of {@code checkFlee} <em>before</em> the
     * {@code fleeDistance == -1F} short-circuit, and {@code none {}} over an empty list is true.
     * {@code -1F} is no protection there.</p>
     */
    private static final float FLEE_DISTANCE = 8F;

    /** Turns the evenly matched run resolves before the player walks off. */
    private static final int TURNS_BEFORE_LEAVING = 4;

    /** Throws allowed per run. A ball that clips the ground is dropped silently, so retries are needed. */
    private static final int MAX_THROWS = 8;

    private static final String[] PLAYER_SPECIES = { "charizard", "blastoise", "venusaur" };

    /** Wild side for the runs that build a horde by hand rather than through the trigger. */
    private static final String[] WILD_SPECIES = { "caterpie", "weedle", "rattata" };

    /**
     * One wild species per run, shared with nothing else in the suite.
     *
     * <p>Every horde run builds its wild side through {@link HordeTrigger#assembleHorde}, which uses a
     * wild Pokemon of the leader's species standing within {@link HordeConfig#searchRadiusBlocks} if
     * there is one and spawns the missing member if there is not. Game tests in a batch run side by
     * side a few blocks apart in one level, well inside that radius, so two runs sharing a species
     * would recruit each other's Pokemon: the run under test would quietly skip the spawn path it
     * exists to measure, and the other run would lose an entity out of the middle of its battle.
     * {@link Rig#setUp} fails the run if nothing was spawned, which is what keeps this comment
     * honest.</p>
     *
     * <p>All weak, all level {@link #WILD_LEVEL}: {@code FLEE} needs a wild side its evenly matched
     * party can trade turns with rather than be swept by.</p>
     */
    private static final String CAPTURE_WILD_SPECIES = "sentret";
    private static final String FLEE_WILD_SPECIES = "zigzagoon";
    private static final String BREAK_FREE_WILD_SPECIES = "bidoof";
    private static final String BREAK_FREE_LATE_WILD_SPECIES = "patrat";
    private static final String WIPE_WILD_SPECIES = "lillipup";
    private static final String WIPE_SINGLES_WILD_SPECIES = "hoothoot";

    // ---------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------

    /**
     * Stages 1 through 4: start, turns resolve, survive a faint, catch the last one.
     *
     * <p>Paced by the battle rather than by a tick count, for the reason {@link #LONG_TIMEOUT_TICKS}
     * gives. A fixed budget measures the wrong thing here: the run has to sit silent through the
     * faint and wait for Showdown's next turn, and that wait is a wall-clock one while the budget is
     * counted in ticks. How many ticks fit inside it is a property of the machine and of how many
     * messages the turn queued, not of the mod, so the same battle passes or fails depending on how
     * fast the server happens to be ticking. Waiting for the rig to finish removes that coupling.</p>
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty3x3x3", timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void hordeLoopToCapture(GameTestHelper helper) {
        Rig rig = new Rig(helper, "CAPTURE", Mode.CAPTURE, FLEE_DISTANCE, CAPTURE_WILD_SPECIES,
                CAPTURE_PARTY_SPECIES, CAPTURE_PARTY_LEVELS);
        helper.startSequence()
                .thenExecute(rig::setUp)
                .thenWaitUntil(rig::driveUntilFinished)
                .thenExecute(rig::report)
                .thenExecute(() -> {
                    rig.requireStage(rig.battleStarted, "1 battle started");
                    rig.requireStage(rig.maxTurn >= 2, "2 turns resolved (maxTurn=" + rig.maxTurn + ")");
                    rig.requireStage(rig.survivedFaint, "3 battle continued after a wild Pokemon fainted");
                    rig.requireStage(rig.captured, "4 last wild Pokemon captured");
                })
                .thenExecute(rig::tearDown)
                .thenSucceed();
    }

    /**
     * Stages 1, 2 and 5: the horde flees once the player is out of range.
     *
     * <p>Paced by the battle rather than by a tick budget, for the reason
     * {@link #LONG_TIMEOUT_TICKS} gives: this run has to see four turns out before the player walks
     * off, and how many ticks four turns costs is a function of how many messages Cobblemon queues -
     * which the wild species changes. A fixed budget makes the run's verdict depend on that.</p>
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty3x3x3", timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void hordeLoopToFlee(GameTestHelper helper) {
        Rig rig = new Rig(helper, "FLEE", Mode.FLEE, FLEE_DISTANCE, FLEE_WILD_SPECIES,
                EVEN_PARTY_SPECIES, EVEN_PARTY_LEVELS);
        helper.startSequence()
                .thenExecute(rig::setUp)
                .thenWaitUntil(rig::driveUntilFinished)
                .thenExecute(rig::report)
                .thenExecute(() -> {
                    rig.requireStage(rig.battleStarted, "1 battle started");
                    rig.requireStage(rig.maxTurn >= TURNS_BEFORE_LEAVING,
                            "2 turns resolved repeatedly (maxTurn=" + rig.maxTurn + ", choices=" + rig.choicesMade + ")");
                    rig.requireStage(rig.fled, "5 horde fled once the player left");
                })
                .thenExecute(rig::tearDown)
                .thenSucceed();
    }

    /**
     * The ball that fails. Nothing before this point was ever measured against one.
     *
     * <p>Every earlier capture run used a Master Ball, which cannot break free, so the battle always
     * ended on the throw. In play the throw usually fails, and a failed throw in doubles leaves the
     * player holding an answered slot and an unanswered one. The first ball here is forced to fail
     * and the run then has to reach the same capture the Master Ball run does.</p>
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty3x3x3", timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void hordeSurvivesABallBreakingFree(GameTestHelper helper) {
        Rig rig = new Rig(helper, "BREAK", Mode.BREAK_FREE, FLEE_DISTANCE, BREAK_FREE_WILD_SPECIES,
                CAPTURE_PARTY_SPECIES, CAPTURE_PARTY_LEVELS);
        helper.startSequence()
                .thenExecute(rig::setUp)
                .thenWaitUntil(rig::driveUntilFinished)
                .thenExecute(rig::report)
                .thenExecute(() -> {
                    rig.requireStage(rig.battleStarted, "1 battle started");
                    rig.requireStage(rig.survivedFaint, "3 battle continued after a wild Pokemon fainted");
                    rig.requireStage(rig.resolvedAfterBreakFree,
                            "6 the turn resolved after the ball broke free (maxTurn=" + rig.maxTurn
                                    + ", choices=" + rig.choicesMade + ")");
                    rig.requireStage(rig.captured, "7 the next ball still caught the last wild Pokemon");
                })
                .thenExecute(rig::tearDown)
                .thenSucceed();
    }

    /**
     * The same failed ball, but with the second slot still unanswered when it fails.
     *
     * <p>{@link #hordeSurvivesABallBreakingFree} answers as soon as the throw hands it a pass, so the
     * turn is already sitting complete in {@code BattleActor.responses} and the break free only has
     * to unblock {@code checkForInputDispatch}. A player is not that quick. Taking seconds to pick a
     * move for the other slot means {@code setActionResponses} runs <em>after</em> the ball has
     * failed, against a request that outlived a capture - and that is the call that throws
     * {@code "a capture was expected"} if {@code expectingPassActions} is left in an odd state
     * ({@code BattleActor.kt:119}).</p>
     *
     * <p>The run asserts the debt was genuinely outstanding at the moment of failure, because a rig
     * that answered early would satisfy every other check here while measuring the easy path. That
     * assertion is load-bearing rather than decorative: the hold-back only applies to the first
     * throw, so a ball that clipped the ground would carry the run into the second throw with the
     * deferral already switched off. The check turns that into a failure instead of a quiet pass.</p>
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty3x3x3", timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void hordeSurvivesABallBreakingFreeWithTheSecondSlotUnanswered(GameTestHelper helper) {
        Rig rig = new Rig(helper, "BREAK_LATE", Mode.BREAK_FREE, FLEE_DISTANCE, BREAK_FREE_LATE_WILD_SPECIES,
                CAPTURE_PARTY_SPECIES, CAPTURE_PARTY_LEVELS, true);
        helper.startSequence()
                .thenExecute(rig::setUp)
                .thenWaitUntil(rig::driveUntilFinished)
                .thenExecute(rig::report)
                .thenExecute(() -> {
                    rig.requireStage(rig.battleStarted, "1 battle started");
                    rig.requireStage(rig.survivedFaint, "3 battle continued after a wild Pokemon fainted");
                    rig.requireStage(rig.passesOwedAtBreakFree > 0,
                            "6a the second slot was still unanswered when the ball failed"
                                    + " (passesOwed=" + rig.passesOwedAtBreakFree
                                    + ", mustChoose=" + rig.mustChooseAtBreakFree + ")");
                    rig.requireStage(rig.resolvedAfterBreakFree,
                            "6b the turn resolved once the outstanding choice was submitted (maxTurn="
                                    + rig.maxTurn + ", choices=" + rig.choicesMade + ")");
                    rig.requireStage(rig.captured, "7 the next ball still caught the last wild Pokemon");
                })
                .thenExecute(rig::tearDown)
                .thenSucceed();
    }

    /**
     * Control for the experience run: the ordinary single wild battle, driven the same way.
     *
     * <p>Tells "the horde broke this" apart from "the harness cannot see a battle through to its
     * end". If this one does not reach {@code end()} either, nothing about the horde is implicated.</p>
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty3x3x3", timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void singleBattleAwardsExperienceForItsOneDefeat(GameTestHelper helper) {
        Rig rig = new Rig(helper, "WIPE_SINGLES", Mode.WIPE_SINGLES, -1F, WIPE_SINGLES_WILD_SPECIES,
                WIPE_PARTY_SPECIES, WIPE_PARTY_LEVELS);
        helper.startSequence()
                .thenExecute(rig::setUp)
                .thenWaitUntil(rig::driveUntilFinished)
                .thenExecute(rig::report)
                .thenExecute(rig::requireExperienceForEveryDefeat)
                .thenExecute(rig::tearDown)
                .thenSucceed();
    }

    /**
     * Experience for every horde member knocked out, not just the first.
     *
     * <p>{@code PokemonBattle.end} pays out over every pairing of fainted Pokemon and opponent, so
     * two defeats should be worth two payouts. The expected figure is taken from Cobblemon's own
     * calculator before the battle starts, summed over the whole horde, and compared against what
     * actually lands - a check for "some experience arrived" would pass on one defeat too.</p>
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty3x3x3", timeoutTicks = LONG_TIMEOUT_TICKS)
    public static void hordeAwardsExperienceForEveryMemberDefeated(GameTestHelper helper) {
        Rig rig = new Rig(helper, "WIPE", Mode.WIPE, -1F, WIPE_WILD_SPECIES,
                WIPE_PARTY_SPECIES, WIPE_PARTY_LEVELS);
        helper.startSequence()
                .thenExecute(rig::setUp)
                .thenWaitUntil(rig::driveUntilFinished)
                .thenExecute(rig::report)
                .thenExecute(rig::requireExperienceForEveryDefeat)
                .thenExecute(rig::tearDown)
                .thenSucceed();
    }

    /**
     * The gate. A party that cannot fill the field must leave the encounter alone, and a party that
     * can must not be refused.
     *
     * <p>The one-living-of-two case is the reason the gate counts health rather than party slots.</p>
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty3x3x3", timeoutTicks = 400)
    public static void hordeGateNeedsTwoLivingPokemon(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        try {
            PartyStore party = PlayerExtensionsKt.party(player);

            party.clearParty();
            party.add(species(PLAYER_SPECIES[0]).create(PLAYER_LEVEL));
            boolean oneHealthy = HordeBattles.partyCanFaceHorde(player);

            party.add(species(PLAYER_SPECIES[1]).create(PLAYER_LEVEL));
            boolean twoHealthy = HordeBattles.partyCanFaceHorde(player);

            party.get(1).setCurrentHealth(0);
            boolean twoOneFainted = HordeBattles.partyCanFaceHorde(player);

            Constants.LOG.info("{} GATE oneHealthy={} twoHealthy={} twoWithOneFainted={}",
                    TAG, oneHealthy, twoHealthy, twoOneFainted);

            if (oneHealthy) {
                helper.fail("gate let a single-Pokemon party into a horde battle");
            }
            if (!twoHealthy) {
                helper.fail("gate refused a party of two healthy Pokemon");
            }
            if (twoOneFainted) {
                helper.fail("gate counted a fainted party member as able to take the field");
            }

            // Same predicate, reached through the real entry point.
            party.clearParty();
            party.add(species(PLAYER_SPECIES[0]).create(PLAYER_LEVEL));
            List<PokemonEntity> horde = spawnHorde(helper, 2);
            BattleStartResult refused = HordeBattles.startHordeBattle(player, horde, -1F, false);
            if (refused != null) {
                helper.fail("startHordeBattle built a horde for a party that cannot field two Pokemon");
            }
            for (PokemonEntity entity : horde) {
                entity.discard();
            }
            helper.succeed();
        } finally {
            removePlayer(helper, player);
        }
    }


    /**
     * The trigger. An ordinary wild encounter has to come out the other side as a horde battle, and
     * everything that is not eligible has to come out untouched.
     *
     * <p>Entry point is {@code PokemonEntity.forceBattle}, the same call the game makes, so
     * {@code BattleBuilder.pve} builds a singles battle and {@code BATTLE_STARTED_PRE} is what turns
     * it into a horde. {@code forceBattle} reporting false is the cancellation, not the result -
     * what matters is the battle the player is actually left in, which is why every case reads it
     * back out of {@link BattleRegistry}.</p>
     *
     * <p>Two of the cases are about a Pokemon that has no company: one wild Pokemon standing on its
     * own still becomes a horde, because a second one comes out to join it. The three that are left
     * alone are left alone for reasons that have nothing to do with how many Pokemon are nearby.</p>
     *
     * <p>All six cases share one test rather than one each because they take turns writing
     * {@link HordeConfig}, and game tests in a batch run side by side.</p>
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty3x3x3", timeoutTicks = 1600)
    public static void hordeTriggerSwapsEligibleWildEncounters(GameTestHelper helper) {
        TriggerRig rig = new TriggerRig(helper);
        helper.startSequence()
                .thenExecute(rig::openEligibleEncounter)
                .thenExecute(rig::requireSwappedToHorde)
                .thenIdle(200)
                .thenExecute(rig::requireHordeIsRunning)
                .thenExecute(rig::endCase)
                .thenExecute(rig::openLoneWildEncounter)
                .thenExecute(rig::requireLoneWildWasReinforced)
                .thenIdle(200)
                .thenExecute(rig::requireHordeIsRunning)
                .thenExecute(rig::endCase)
                .thenExecute(rig::requireShinyLeaderBringsOutAShiny)
                .thenExecute(rig::requireRegionalFormBringsOutTheSameForm)
                .thenExecute(rig::requireSmallPartyIsLeftAlone)
                .thenExecute(rig::requireZeroChanceIsLeftAlone)
                .thenExecute(rig::requireLegendariesAreLeftAlone)
                .thenExecute(rig::restoreDefaults)
                .thenSucceed();
    }

    /**
     * Diagnostic only: the same doubles battle, but with an AI actor where the player would be.
     * Splits "Showdown will not take this battle" from "Showdown will not take a PlayerBattleActor".
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty3x3x3", timeoutTicks = 1200)
    public static void diagHordeAgainstAiSide(GameTestHelper helper) {
        List<PokemonEntity> ourSide = spawnHorde(helper, HordeBattles.slotsPerActor());
        List<PokemonEntity> horde = spawnHorde(helper, HordeBattles.slotsPerActor());
        List<BattlePokemon> ourTeam = new ArrayList<>();
        for (PokemonEntity entity : ourSide) {
            ourTeam.add(new BattlePokemon(entity.getPokemon(), entity.getPokemon(), new ArrayList<>(), new ArrayList<>()));
        }
        List<BattlePokemon> wildTeam = new ArrayList<>();
        for (PokemonEntity entity : horde) {
            wildTeam.add(new BattlePokemon(entity.getPokemon(), entity.getPokemon(), new ArrayList<>(), new ArrayList<>()));
        }
        BattleStartResult result = BattleRegistry.startBattle(
                HordeBattles.FORMAT,
                new BattleSide(new HordeBattleActor(ourTeam, -1F)),
                new BattleSide(new HordeBattleActor(wildTeam, -1F)),
                false);
        PokemonBattle battle = result instanceof SuccessfulBattleStart success ? success.getBattle() : null;
        helper.runAfterDelay(600, () -> {
            Constants.LOG.info("{} DIAG_AI_DOUBLES started={} turn={} showdownMessages={}",
                    TAG, battle != null && battle.getStarted(), battle == null ? -1 : battle.getTurn(),
                    battle == null ? -1 : battle.getShowdownMessages().size());
            if (battle != null) {
                battle.stop();
            }
            for (PokemonEntity entity : ourSide) {
                entity.discard();
            }
            for (PokemonEntity entity : horde) {
                entity.discard();
            }
            helper.succeed();
        });
    }


    /**
     * Probe, not a test: what Showdown actually does with a party of two whose second member has
     * fainted.
     *
     * <p>The gate refuses this party, and the spike measured the neighbouring case - a team of
     * <em>one</em> against two active slots, where {@code side.active[1]} stays null and
     * {@code Battle.runAction} walks into {@code forceSwitchFlag of null}. A fainted second member
     * is a different shape: two entries get packed, so the slot is filled by a fainted Pokemon
     * rather than left null. This asks Showdown directly, bypassing the gate. It never fails - the
     * answer is the point, and it is only readable because {@code prepareShowdown} makes async
     * Showdown errors audible.</p>
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty3x3x3", timeoutTicks = 1200)
    public static void probeFaintedSecondPartyMember(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        PartyStore party = PlayerExtensionsKt.party(player);
        party.clearParty();
        party.add(species(CAPTURE_PARTY_SPECIES[0]).create(CAPTURE_PARTY_LEVELS[0]));
        party.add(species(CAPTURE_PARTY_SPECIES[1]).create(CAPTURE_PARTY_LEVELS[1]));
        party.get(1).setCurrentHealth(0);

        List<BattlePokemon> playerTeam = HordeBattles.battleTeamOf(player);
        List<PokemonEntity> horde = spawnHorde(helper, HordeBattles.slotsPerActor());
        List<BattlePokemon> wildTeam = new ArrayList<>();
        for (PokemonEntity entity : horde) {
            wildTeam.add(new BattlePokemon(entity.getPokemon(), entity.getPokemon(), new ArrayList<>(), new ArrayList<>()));
        }
        Constants.LOG.info("{} PROBE_FAINTED gateAllows={} teamSize={} living={} healthBySlot={}",
                TAG, HordeBattles.teamCanFaceHorde(playerTeam), playerTeam.size(),
                HordeBattles.countLiving(playerTeam),
                playerTeam.stream().map(BattlePokemon::getHealth).toList());

        BattleStartResult result = BattleRegistry.startBattle(
                HordeBattles.FORMAT,
                new BattleSide(new PlayerBattleActor(player.getUUID(), playerTeam)),
                new BattleSide(new HordeBattleActor(wildTeam, -1F)),
                false);
        PokemonBattle battle = result instanceof SuccessfulBattleStart success ? success.getBattle() : null;
        helper.runAfterDelay(600, () -> {
            Constants.LOG.info("{} PROBE_FAINTED_RESULT started={} turn={} showdownMessages={}",
                    TAG, battle != null && battle.getStarted(), battle == null ? -1 : battle.getTurn(),
                    battle == null ? -1 : battle.getShowdownMessages().size());
            if (battle != null && !battle.getEnded()) {
                battle.stop();
            }
            for (PokemonEntity entity : horde) {
                if (!entity.isRemoved()) {
                    entity.discard();
                }
            }
            removePlayer(helper, player);
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------------------------------
    // Rig
    // ---------------------------------------------------------------------------------------

    private enum Phase { WAITING, FIGHT, AFTER_FAINT, THROWN, DONE }

    /** What the rig is trying to reach. Everything up to the first faint is the same in all four. */
    private enum Mode {
        /** Catch the last horde member with a ball that cannot fail. */
        CAPTURE,
        /** Walk away mid-fight and let the flee check end the battle. */
        FLEE,
        /** Have the first ball break free, then carry on and catch on the next one. */
        BREAK_FREE,
        /** Knock the whole horde out and read the experience that lands. */
        WIPE,
        /** Control for {@link #WIPE}: the same run against one wild Pokemon in an ordinary singles battle. */
        WIPE_SINGLES
    }

    private static final class Rig {
        private final GameTestHelper helper;
        private final String label;
        private final Mode mode;
        private final float fleeDistance;
        private final String wildSpecies;
        private final String[] partySpecies;
        private final int[] partyLevels;
        /**
         * Withhold the answer to the remaining slot until the ball has failed, the way a player who
         * is still reading their moves would.
         */
        private final boolean deferChoiceUntilBreakFree;
        // A client passes for a slot whose Pokemon has fainted; RandomBattleAI alone sends a move.
        private final BattleAI ai = new HordeBattleAI(new RandomBattleAI());

        private ServerPlayer player;
        private List<PokemonEntity> horde;
        private PokemonBattle battle;
        private PlayerBattleActor playerActor;

        private Phase phase = Phase.WAITING;
        private int tick;
        int choicesMade;
        private int turnAtFaint = -1;
        private EmptyPokeBallEntity thrownBall;
        private String lastBallState = "";
        private int throwsMade;

        boolean battleStarted;
        int maxTurn;
        boolean survivedFaint;
        boolean captured;
        boolean fled;
        private String failure;

        // BREAK_FREE only.
        @Nullable
        private ObservableSubscription<PokeBallCaptureCalculatedEvent> captureSubscription;
        private int breakFreesForced;
        boolean brokeFree;
        boolean resolvedAfterBreakFree;
        private int turnAtBreakFree = -1;
        /** What the player still owed Showdown at the instant the ball failed. */
        int passesOwedAtBreakFree = -1;
        boolean mustChooseAtBreakFree;

        // WIPE only. Indexed alongside playerActor.getPokemonList().
        private int[] experienceBefore = new int[0];
        private int[] experienceExpected = new int[0];
        int[] experienceGained = new int[0];

        Rig(GameTestHelper helper, String label, Mode mode, float fleeDistance, String wildSpecies,
            String[] partySpecies, int[] partyLevels) {
            this(helper, label, mode, fleeDistance, wildSpecies, partySpecies, partyLevels, false);
        }

        Rig(GameTestHelper helper, String label, Mode mode, float fleeDistance, String wildSpecies,
            String[] partySpecies, int[] partyLevels, boolean deferChoiceUntilBreakFree) {
            this.helper = helper;
            this.label = label;
            this.mode = mode;
            this.fleeDistance = fleeDistance;
            this.wildSpecies = wildSpecies;
            this.partySpecies = partySpecies;
            this.partyLevels = partyLevels;
            this.deferChoiceUntilBreakFree = deferChoiceUntilBreakFree;
        }

        void setUp() {
            player = mockPlayer(helper);
            PartyStore party = PlayerExtensionsKt.party(player);
            party.clearParty();
            for (int i = 0; i < partySpecies.length; i++) {
                party.add(species(partySpecies[i]).create(partyLevels[i]));
            }

            horde = mode == Mode.WIPE_SINGLES
                    ? spawnWild(helper, wildSpecies, 1)
                    : reinforcedHorde();
            if (horde == null) {
                return;
            }

            BattleStartResult result = mode == Mode.WIPE_SINGLES
                    ? startOrdinarySingleBattle()
                    : HordeBattles.startHordeBattle(player, horde, fleeDistance, false);
            if (result == null) {
                helper.fail(label + ": gate refused a party that should have been able to face a horde");
                return;
            }
            if (!(result instanceof SuccessfulBattleStart success)) {
                Constants.LOG.info("{} {} START_REFUSED {}", TAG, label, result.getClass().getSimpleName());
                helper.fail(label + ": BattleRegistry refused the horde battle");
                return;
            }
            battle = success.getBattle();
            battle.setMute(false);
            playerActor = null;
            for (BattleActor actor : battle.getActors()) {
                if (actor instanceof PlayerBattleActor found) {
                    playerActor = found;
                }
            }
            if (playerActor == null) {
                helper.fail(label + ": the horde battle has no player actor");
                return;
            }
            phase = Phase.FIGHT;
            if (mode == Mode.BREAK_FREE) {
                armFirstBallToFail();
            }
            if (mode == Mode.WIPE || mode == Mode.WIPE_SINGLES) {
                recordExpectedExperience();
            }
            Constants.LOG.info("{} {} SUBMITTED battleId={} format={} playerTeam={} horde={}",
                    TAG, label, battle.getBattleId(), battle.getFormat().getBattleType().getName(),
                    playerActor.getPokemonList().size(), horde.size());
        }

        /**
         * The wild side, built the way an encounter in a running world builds it.
         *
         * <p>One wild Pokemon is spawned and {@link HordeTrigger#assembleHorde} fills the rest of the
         * side, which in an empty test level means it spawns them. Every stage this rig goes on to
         * measure - turns resolving, a member fainting, the last one going into a ball, the flee, the
         * experience payout - therefore runs against a horde member that was brought into the world
         * to be one, not against a pair placed there in advance.</p>
         *
         * <p>Returns null after failing the run, so the caller stops rather than building a battle out
         * of a wild side it does not trust.</p>
         */
        @Nullable
        private List<PokemonEntity> reinforcedHorde() {
            PokemonEntity leader = spawnWild(helper, wildSpecies, 1).get(0);
            List<PokemonEntity> reinforcements = new ArrayList<>();
            List<PokemonEntity> assembled =
                    HordeTrigger.assembleHorde(helper.getLevel(), leader, player, reinforcements);
            if (assembled == null) {
                helper.fail(label + ": assembleHorde could not fill the wild side around a lone " + wildSpecies);
                return null;
            }
            if (reinforcements.isEmpty()) {
                // A neighbouring test's Pokemon of the same species was recruited instead. The run
                // would still pass while measuring the wrong thing, so it is stopped here instead.
                helper.fail(label + ": the wild side was filled from " + wildSpecies
                        + " already standing in the level, so nothing was spawned - that species is"
                        + " not exclusive to this run");
                return null;
            }
            Constants.LOG.info("{} {} HORDE_ASSEMBLED species={} spawned={} total={}",
                    TAG, label, wildSpecies, reinforcements.size(), assembled.size());
            return assembled;
        }

        /**
         * The battle Cobblemon would have built: one wild Pokemon, singles, no horde anywhere.
         *
         * <p>{@code canPreempt} false for the same reason every other rig call uses it - so the
         * horde trigger stays out of a run that is meant to measure the plain case.</p>
         */
        private BattleStartResult startOrdinarySingleBattle() {
            PokemonEntity wild = horde.get(0);
            BattlePokemon wildPokemon = new BattlePokemon(
                    wild.getPokemon(), wild.getPokemon(), new ArrayList<>(), new ArrayList<>());
            return BattleRegistry.startBattle(
                    BattleFormat.Companion.getGEN_9_SINGLES(),
                    new BattleSide(new PlayerBattleActor(player.getUUID(), HordeBattles.battleTeamOf(player))),
                    new BattleSide(new PokemonBattleActor(
                            wild.getPokemon().getUuid(), wildPokemon, fleeDistance, new RandomBattleAI())),
                    false);
        }

        /**
         * Makes the first capture calculation come back as a failure.
         *
         * <p>Everything downstream is Cobblemon's own: the ball still shakes, {@code shakeBall} still
         * calls {@code breakFree()}, and {@code captureFuture} still completes late and off the back
         * of a scheduled task. That timing is the thing under test, so the shortcut of completing
         * {@code captureFuture} by hand would test nothing.</p>
         */
        private void armFirstBallToFail() {
            captureSubscription = CobblemonEvents.POKE_BALL_CAPTURE_CALCULATED.subscribe(
                    Priority.HIGHEST,
                    event -> {
                        if (event.getPokeBallEntity() == thrownBall && breakFreesForced < 1) {
                            breakFreesForced++;
                            event.setCaptureResult(new CaptureContext(1, false, false));
                            Constants.LOG.info("{} {} FORCED_BREAK_FREE tick={} turn={}",
                                    TAG, label, tick, battle.getTurn());
                        }
                        return Unit.INSTANCE;
                    });
        }

        /**
         * What Cobblemon's own calculator says each party member is owed for the whole horde.
         *
         * <p>Taken before a single point is awarded, and summed over every horde member, so the
         * assertion is "one payout per Pokemon knocked out" rather than "some experience arrived".</p>
         */
        private void recordExpectedExperience() {
            List<BattlePokemon> playerTeam = playerActor.getPokemonList();
            List<BattlePokemon> wildTeam = hordeActorTeam();
            experienceBefore = new int[playerTeam.size()];
            experienceExpected = new int[playerTeam.size()];
            experienceGained = new int[playerTeam.size()];
            int slots = battle.getFormat().getBattleType().getSlotsPerActor();
            for (int i = 0; i < playerTeam.size(); i++) {
                BattlePokemon mine = playerTeam.get(i);
                experienceBefore[i] = mine.getEffectedPokemon().getExperience();
                if (i >= slots) {
                    // Never took the field, so it never enters anyone's facedOpponents and is owed
                    // nothing. Leaving it at zero is an assertion in its own right.
                    continue;
                }
                for (BattlePokemon wild : wildTeam) {
                    experienceExpected[i] += Cobblemon.INSTANCE.getExperienceCalculator()
                            .calculate(mine, wild, 1.0);
                }
            }
            Constants.LOG.info("{} {} XP_BASELINE before={} expectedForWholeHorde={}",
                    TAG, label, java.util.Arrays.toString(experienceBefore),
                    java.util.Arrays.toString(experienceExpected));
        }

        /**
         * {@link #drive()} for runs that are paced by Cobblemon rather than by a tick count.
         *
         * <p>{@code thenWaitUntil} re-runs this every tick and moves on the first time it does not
         * throw, so the run costs exactly as long as the battle does and no longer. The message on
         * the way out is what a hung run reports.</p>
         */
        void driveUntilFinished() {
            drive();
            if (phase != Phase.DONE) {
                throw new GameTestAssertException(label + " is still running: turn=" + battle.getTurn()
                        + " choices=" + choicesMade + " phase=" + phase
                        + " dispatches=" + battle.getDispatches().size()
                        + " brokeFree=" + brokeFree + " ended=" + battle.getEnded());
            }
        }

        /** Runs every tick. Must never throw: a thrown exception here is reported as a rig fault. */
        void drive() {
            tick++;
            if (battle == null || phase == Phase.DONE || failure != null) {
                return;
            }
            try {
                driveInner();
            } catch (Exception exception) {
                failure = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                Constants.LOG.error("{} {} RIG_FAULT tick={} {}", TAG, label, tick, failure, exception);
                phase = Phase.DONE;
            }
        }

        private void driveInner() {
            if (battle.getStarted()) {
                battleStarted = true;
            }
            maxTurn = Math.max(maxTurn, battle.getTurn());

            if (mode == Mode.WIPE || mode == Mode.WIPE_SINGLES) {
                // No ball and nowhere to walk to: just keep answering until one side is out.
                if (battle.getEnded()) {
                    readExperienceGained();
                    finish("battle ended");
                } else {
                    submitChoice();
                }
                return;
            }

            if (mode == Mode.FLEE) {
                // Flee needs neither a faint nor a capture: fight a turn so the battle is properly
                // under way, then walk off and let PokemonBattle.checkFlee do its work. It only runs
                // while the dispatch queue is empty, which is exactly the state a battle waiting on
                // the player's choice is in, so the rig stops answering once the player has left.
                if (maxTurn >= TURNS_BEFORE_LEAVING || battle.getEnded()) {
                    leaveTheArea();
                } else {
                    submitChoice();
                }
                return;
            }

            if (battle.getEnded()) {
                finish("battle ended");
                return;
            }

            int wildLiving = HordeBattles.countLiving(hordeActorTeam());

            switch (phase) {
                case FIGHT -> {
                    if (wildLiving <= HordeBattles.slotsPerActor() - 1 && battleStarted) {
                        turnAtFaint = battle.getTurn();
                        Constants.LOG.info("{} {} FAINT_OBSERVED tick={} turn={} wildLiving={}",
                                TAG, label, tick, turnAtFaint, wildLiving);
                        if (wildLiving == 0) {
                            failure = "the whole horde fainted on the same turn, so there was nothing left to catch";
                            phase = Phase.DONE;
                            return;
                        }
                        phase = Phase.AFTER_FAINT;
                        return;
                    }
                    submitChoice();
                }
                case AFTER_FAINT -> {
                    // Deliberately silent: the throw needs the player still owing Showdown a choice
                    // (EmptyPokeBallEntity requires canFitForcedAction()). Showdown advancing the
                    // turn counter while we say nothing is the proof that losing a horde member did
                    // not kill the battle.
                    if (battle.getTurn() > turnAtFaint || playerActor.getMustChoose()) {
                        survivedFaint = true;
                    }
                    if (survivedFaint && playerActor.canFitForcedAction()) {
                        throwBall();
                    }
                }
                case THROWN -> {
                    if (thrownBall != null) {
                        String state = thrownBall.getCaptureState() + "/removed=" + thrownBall.isRemoved()
                                + "/captures=" + battle.getCaptureActions().size();
                        if (!state.equals(lastBallState)) {
                            lastBallState = state;
                            Constants.LOG.info("{} {} BALL tick={} {} pos={}", TAG, label, tick, state,
                                    thrownBall.position());
                        }
                    }
                    if (lastWild() != null && !lastWild().getPokemon().isWild()) {
                        captured = true;
                        finish("captured");
                    }
                    if (lastWild() == null || lastWild().isRemoved()) {
                        // The capture removes the entity from the world; confirm through the party.
                        captured = PlayerExtensionsKt.party(player).size() > HordeBattles.slotsPerActor();
                        finish(captured ? "captured (entity gone, party grew)" : "wild entity vanished without a capture");
                        return;
                    }
                    if (mode == Mode.BREAK_FREE) {
                        driveAfterThrow();
                        return;
                    }
                    if (thrownBall != null && thrownBall.isRemoved() && battle.getCaptureActions().isEmpty()) {
                        // The ball left the world without starting a capture, which is what a ball
                        // that hit terrain looks like. Refusals announce themselves as a system
                        // message, so retrying cannot paper one over.
                        if (throwsMade >= 3) {
                            finish("ball dropped " + throwsMade + " times without starting a capture");
                        } else if (playerActor.canFitForcedAction()) {
                            throwBall();
                        } else {
                            finish("cannot throw again: the player no longer owes Showdown a choice");
                        }
                    }
                }
                default -> { }
            }
        }

        /**
         * Mirrors what a client does: answer the outstanding request, one tick after being asked.
         *
         * <p>Throwing a ball does not answer the request, it only claims one slot of it. Cobblemon
         * records that as a {@code ForcePassActionResponse} parked in
         * {@code BattleActor.expectingPassActions}, and the client hands it back in the first slot it
         * has not already answered ({@code BattleApplyPassResponseHandler}). In singles that is the
         * whole answer; in doubles the player still owes a real choice for the other slot, and
         * {@code setActionResponses} throws {@code "a capture was expected"} if the pass is left
         * out. Placing it in the first slot that has a moveset and is not switching is what makes a
         * doubles throw answerable at all.</p>
         */
        private void submitChoice() {
            if (!playerActor.getMustChoose()) {
                return;
            }
            ShowdownActionRequest request = playerActor.getRequest();
            if (request == null) {
                return;
            }
            // Read now: a ball landing between ticks adds to this list.
            int[] passesOwed = { playerActor.getExpectingPassActions().size() };
            List<ShowdownActionResponse> responses = request.iterate(
                    playerActor.getActivePokemon(),
                    (ActiveBattlePokemon active, ShowdownMoveset moveset, Boolean forceSwitch) -> {
                        if (passesOwed[0] > 0 && moveset != null && !forceSwitch) {
                            passesOwed[0]--;
                            return (ShowdownActionResponse) new ForcePassActionResponse();
                        }
                        return ai.choose(active, battle, playerActor.getSide(), moveset, forceSwitch);
                    });
            if (passesOwed[0] > 0) {
                // No slot can carry the pass - every one is switching or has no moves. Submitting
                // would throw; wait for the next request instead.
                return;
            }
            playerActor.setActionResponses(responses);
            choicesMade++;
        }

        /**
         * The ball is in the air or shaking. Answers the request it claimed, then watches what the
         * battle does when the ball fails.
         *
         * <p>{@code checkForInputDispatch} refuses to write anything to Showdown while
         * {@code battle.captureActions} is occupied, so the submitted choices sit there until the
         * ball resolves. A break free empties that list through {@code finishCaptureAction}, and the
         * turn should then go through - pass for the slot that threw, a move for the other. If it
         * does not, the battle is stuck and the player owes Showdown a choice forever.</p>
         */
        private void driveAfterThrow() {
            if (!brokeFree && thrownBall != null && thrownBall.isRemoved()
                    && battle.getCaptureActions().isEmpty()
                    && playerActor.getExpectingPassActions().isEmpty()) {
                // The ball left the world without ever claiming a slot, which is what a throw that
                // clipped the ground looks like. Same hazard the capture run retries around.
                if (throwsMade >= MAX_THROWS) {
                    finish("ball dropped " + throwsMade + " times without starting a capture");
                } else if (playerActor.canFitForcedAction()) {
                    throwBall();
                } else {
                    finish("cannot throw again: the player no longer owes Showdown a choice");
                }
                return;
            }
            // Read the break before answering anything, so the debt recorded below is the debt as it
            // stood at the moment of failure rather than one this tick just settled.
            if (!brokeFree && thrownBall != null
                    && thrownBall.getCaptureState() == EmptyPokeBallEntity.CaptureState.BROKEN_FREE) {
                brokeFree = true;
                turnAtBreakFree = battle.getTurn();
                if (passesOwedAtBreakFree < 0) {
                    passesOwedAtBreakFree = playerActor.getExpectingPassActions().size();
                    mustChooseAtBreakFree = playerActor.getMustChoose();
                }
                Constants.LOG.info("{} {} BROKE_FREE tick={} turn={} expectingPasses={} mustChoose={}",
                        TAG, label, tick, turnAtBreakFree,
                        playerActor.getExpectingPassActions().size(), playerActor.getMustChoose());
            }
            // The deferral covers the forced failure only. The ball that follows it is a Master Ball
            // and never breaks free, so holding the choice back for that one would simply hang.
            boolean holdingBack = deferChoiceUntilBreakFree && throwsMade <= 1 && !brokeFree;
            if (!playerActor.getExpectingPassActions().isEmpty() && !holdingBack) {
                submitChoice();
            }
            if (!brokeFree) {
                return;
            }
            if (battle.getTurn() <= turnAtBreakFree) {
                submitChoice();
                return;
            }
            if (!resolvedAfterBreakFree) {
                resolvedAfterBreakFree = true;
                Constants.LOG.info("{} {} RESOLVED_AFTER_BREAK tick={} turn={} choices={}",
                        TAG, label, tick, battle.getTurn(), choicesMade);
            }
            if (throwsMade >= MAX_THROWS) {
                finish("gave up after " + throwsMade + " throws");
                return;
            }
            if (playerActor.canFitForcedAction()) {
                brokeFree = false;
                turnAtBreakFree = -1;
                throwBall();
                return;
            }
            submitChoice();
        }

        private void readExperienceGained() {
            List<BattlePokemon> playerTeam = playerActor.getPokemonList();
            for (int i = 0; i < experienceGained.length && i < playerTeam.size(); i++) {
                experienceGained[i] = playerTeam.get(i).getEffectedPokemon().getExperience()
                        - experienceBefore[i];
            }
            Constants.LOG.info("{} {} XP_RESULT gained={} expected={} wildLiving={}",
                    TAG, label, java.util.Arrays.toString(experienceGained),
                    java.util.Arrays.toString(experienceExpected),
                    HordeBattles.countLiving(hordeActorTeam()));
        }

        void requireExperienceForEveryDefeat() {
            List<BattlePokemon> wildTeam = hordeActorTeam();
            int wildLiving = HordeBattles.countLiving(wildTeam);
            if (wildLiving != 0) {
                helper.fail(label + ": the horde was not wiped out (living=" + wildLiving + ")");
            }
            int owedToSomeone = 0;
            for (int owed : experienceExpected) {
                owedToSomeone += owed;
            }
            if (owedToSomeone <= 0) {
                helper.fail(label + ": nothing was owed to anybody, so the check proves nothing");
            }
            for (int i = 0; i < experienceExpected.length; i++) {
                if (experienceGained[i] != experienceExpected[i]) {
                    helper.fail(label + ": slot " + i + " gained " + experienceGained[i]
                            + " experience but " + wildTeam.size() + " defeated Pokemon are worth "
                            + experienceExpected[i]);
                }
            }
        }

        private void leaveTheArea() {
            Vec3 arena = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5));
            if (player.position().distanceTo(arena) < 12) {
                Vec3 away = arena.add(16, 0, 0);
                player.teleportTo(away.x, away.y, away.z);
                Constants.LOG.info("{} {} PLAYER_LEFT tick={} turn={} to={}", TAG, label, tick, battle.getTurn(), away);
            }
            if (battle.getEnded()) {
                // A flee, not a defeat: both sides still have Pokemon standing.
                fled = HordeBattles.countLiving(hordeActorTeam()) > 0
                        && HordeBattles.countLiving(playerActor.getPokemonList()) > 0;
                finish(fled ? "fled" : "battle ended without a flee");
            }
        }

        /**
         * Throws from point blank, level with the target.
         *
         * <p>A throw from the player's eyes travels a downward slope and buries itself in the ground
         * before it reaches a knee-high Caterpie - and {@code onHitBlock} drops the ball without a
         * word, so the failure looks exactly like a refused capture. Starting a block and a half
         * away at the target's own height keeps the flight horizontal and short.</p>
         */
        private void throwBall() {
            PokemonEntity target = lastWild();
            if (target == null) {
                failure = "no living wild Pokemon left to throw at";
                phase = Phase.DONE;
                return;
            }
            ServerLevel level = helper.getLevel();
            Vec3 centre = target.position().add(0, Math.max(target.getBbHeight(), 0.6) * 0.5, 0);
            Vec3 approach = new Vec3(player.getX() - target.getX(), 0, player.getZ() - target.getZ());
            approach = approach.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : approach.normalize();
            // Launched above the target and aimed down at it, fast. A flat throw from the target's own
            // height starts a hand's width off the floor, and gravity puts the ball into the ground
            // before it has covered the gap - onHitBlock then drops it without a word, which looks
            // exactly like a refused capture.
            Vec3 from = centre.add(approach.scale(1.2)).add(0, 0.6, 0);
            Vec3 aim = centre.subtract(from).normalize();

            EmptyPokeBallEntity ball = new EmptyPokeBallEntity(level);
            ball.setPokeBall(PokeBalls.getMasterBall());
            ball.setOwner(player);
            ball.setPos(from);
            ball.shoot(aim.x, aim.y, aim.z, 1.2F, 0F);
            level.addFreshEntity(ball);
            thrownBall = ball;
            throwsMade++;
            phase = Phase.THROWN;
            Constants.LOG.info("{} {} BALL_THROWN #{} tick={} turn={} at={} from={} target={}",
                    TAG, label, throwsMade, tick, battle.getTurn(),
                    target.getPokemon().getSpecies().getName(), from, centre);
        }

        private List<BattlePokemon> hordeActorTeam() {
            for (BattleActor actor : battle.getActors()) {
                if (!(actor instanceof PlayerBattleActor)) {
                    return actor.getPokemonList();
                }
            }
            return List.of();
        }

        private PokemonEntity lastWild() {
            for (BattlePokemon battlePokemon : hordeActorTeam()) {
                if (battlePokemon.getHealth() > 0) {
                    return battlePokemon.getEntity();
                }
            }
            return null;
        }

        private void finish(String why) {
            if (phase != Phase.DONE) {
                Constants.LOG.info("{} {} FINISH tick={} reason={} ", TAG, label, tick, why);
                phase = Phase.DONE;
            }
        }

        void report() {
            Constants.LOG.info("{} {} REPORT started={} maxTurn={} choices={} turnAtFaint={} survivedFaint={} captured={} fled={} brokeFree={} resolvedAfterBreakFree={} passesOwedAtBreakFree={} mustChooseAtBreakFree={} ended={} phase={} failure={}",
                    TAG, label, battleStarted, maxTurn, choicesMade, turnAtFaint, survivedFaint,
                    captured, fled, breakFreesForced > 0, resolvedAfterBreakFree,
                    passesOwedAtBreakFree, mustChooseAtBreakFree,
                    battle != null && battle.getEnded(), phase, failure);
            if (battle != null) {
                Constants.LOG.info("{} {} DISPATCH dispatches={} dispatchResult={} showdownMessages={} playerMustChoose={} expectingPasses={}",
                        TAG, label, battle.getDispatches().size(), battle.getDispatchResult(),
                        battle.getShowdownMessages().size(),
                        playerActor != null && playerActor.getMustChoose(),
                        playerActor == null ? -1 : playerActor.getExpectingPassActions().size());
                List<String> log = battle.getBattleLog();
                int from = Math.max(0, log.size() - 60);
                for (int i = from; i < log.size(); i++) {
                    Constants.LOG.info("{} {} LOG| {}", TAG, label, log.get(i).replace("\n", " \\n "));
                }
            }
        }

        void requireStage(boolean condition, String description) {
            if (failure != null) {
                helper.fail(label + ": rig fault before stage \"" + description + "\": " + failure);
            }
            if (!condition) {
                helper.fail(label + ": stage failed - " + description);
            }
        }

        void tearDown() {
            if (captureSubscription != null) {
                // Global subscription: leaving it attached would fail the ordinary capture test.
                CobblemonEvents.POKE_BALL_CAPTURE_CALCULATED.unsubscribe(captureSubscription);
                captureSubscription = null;
            }
            if (battle != null && !battle.getEnded()) {
                battle.stop();
            }
            if (horde != null) {
                for (PokemonEntity entity : horde) {
                    if (!entity.isRemoved()) {
                        entity.discard();
                    }
                }
            }
            removePlayer(helper, player);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Trigger rig
    // ---------------------------------------------------------------------------------------

    /**
     * Runs one wild encounter at a time and reports what the player ended up in.
     *
     * <p>Uses a species no other test spawns. Game tests share a level and stand only a few blocks
     * apart, so a neighbouring test's Caterpie is well inside the horde search radius and would be
     * recruited into this one.</p>
     */
    private static final class TriggerRig {
        private static final String TRIGGER_SPECIES = "pidgey";
        private static final String LEGENDARY_SPECIES = "articuno";

        /**
         * A species whose regional form is carried by a species feature rather than by its name.
         *
         * <p>Vulpix's Alolan form is {@code features: ["alolan"]} in its species JSON, and the form is
         * selected from the aspect that feature provides. It is the case that separates a
         * reinforcement that really is the same Pokemon from one that merely shares a species name -
         * anything that rebuilds the companion from species and level alone hands the player a
         * Kantonian Vulpix next to an Alolan one.</p>
         */
        private static final String REGIONAL_SPECIES = "vulpix";
        private static final String REGIONAL_ASPECT = "alolan";

        private final GameTestHelper helper;
        private ServerPlayer player;
        private List<PokemonEntity> wild = List.of();
        private String caseName = "";

        TriggerRig(GameTestHelper helper) {
            this.helper = helper;
        }

        // --- cases ---------------------------------------------------------------------------

        void openEligibleEncounter() {
            beginCase("ELIGIBLE", TRIGGER_SPECIES, 2, 2, 100);
        }

        void requireSwappedToHorde() {
            PokemonBattle battle = requireDoubles();
            // Both members must be claimed, or a second player can start their own battle with the
            // one that was recruited. Nothing should have been spawned here either: the pair was
            // already standing there, so the second slot has to be the second Pokemon this rig put in
            // the level rather than a third one.
            PokemonEntity recruited = otherWildMember(battle, wild.get(0));
            if (recruited.getId() != wild.get(1).getId()) {
                helper.fail(caseName + ": a second " + TRIGGER_SPECIES + " was standing right there,"
                        + " but the horde was filled with a freshly spawned one instead");
            }
            // No parity check here: these two were rolled independently, so they legitimately differ
            // in gender and everything downstream of it. Parity is what a spawned member owes.
            Constants.LOG.info("{} TRIGGER {} swapped battleId={} wildTeam={}",
                    TAG, caseName, battle.getBattleId(), wildTeamOf(battle).size());
        }

        /** A horde that was built but never runs would satisfy every check above. */
        void requireHordeIsRunning() {
            PokemonBattle battle = currentBattle();
            Constants.LOG.info("{} TRIGGER {} running started={} turn={} messages={}",
                    TAG, caseName, battle.getStarted(), battle.getTurn(),
                    battle.getShowdownMessages().size());
            if (!battle.getStarted() || battle.getTurn() < 1) {
                helper.fail(caseName + ": the horde battle was created but Showdown never started it"
                        + " (started=" + battle.getStarted() + ", turn=" + battle.getTurn() + ")");
            }
        }

        /**
         * The case the whole design turns on: one wild Pokemon, nothing else of its kind anywhere
         * near it.
         *
         * <p>Cobblemon's spawner picks each Pokemon independently, and its one herd spawn detail
         * ({@code 0000_pidgey_herd.json}) ships {@code "enabled": false}, so this - not the pair - is
         * what an encounter in a running world nearly always looks like. If it were left alone, hordes
         * would be something a player never met.</p>
         */
        void openLoneWildEncounter() {
            beginCase("REINFORCED", TRIGGER_SPECIES, 1, 2, 100);
        }

        void requireLoneWildWasReinforced() {
            PokemonBattle battle = requireDoubles();
            PokemonEntity leader = wild.get(0);
            PokemonEntity reinforcement = otherWildMember(battle, leader);
            requireSameStock(leader, reinforcement);
            Constants.LOG.info("{} TRIGGER {} reinforced leaderEntity={} spawnedEntity={} species={} level={}",
                    TAG, caseName, leader.getId(), reinforcement.getId(),
                    reinforcement.getPokemon().getSpecies().getName(), reinforcement.getPokemon().getLevel());
        }

        /**
         * Shininess is part of what has to be brought out, not something rolled again.
         *
         * <p>A shiny standing next to an ordinary one is the single most visible way for the pair to
         * read as two unrelated Pokemon that happen to be fighting together.</p>
         */
        void requireShinyLeaderBringsOutAShiny() {
            beginCase("SHINY", () -> parseWild("species=" + TRIGGER_SPECIES + " shiny=yes"), 1, 2, 100);
            PokemonEntity leader = wild.get(0);
            if (!leader.getPokemon().getShiny()) {
                helper.fail(caseName + ": the fixture failed - the leader was not spawned shiny,"
                        + " so this case would pass without testing anything");
            }
            PokemonEntity reinforcement = otherWildMember(requireDoubles(), leader);
            if (!reinforcement.getPokemon().getShiny()) {
                helper.fail(caseName + ": a shiny leader brought out an ordinary " + TRIGGER_SPECIES);
            }
            requireSameStock(leader, reinforcement);
            endCase();
        }

        /** The form has to come out too, and it is carried by a species feature rather than the name. */
        void requireRegionalFormBringsOutTheSameForm() {
            beginCase("REGIONAL", () -> parseWild("species=" + REGIONAL_SPECIES + " " + REGIONAL_ASPECT), 1, 2, 100);
            PokemonEntity leader = wild.get(0);
            if (!leader.getPokemon().getAspects().contains(REGIONAL_ASPECT)) {
                helper.fail(caseName + ": the fixture failed - the leader is not " + REGIONAL_ASPECT
                        + " (aspects=" + leader.getPokemon().getAspects() + ", form="
                        + leader.getPokemon().getForm().getName() + ")");
            }
            PokemonEntity reinforcement = otherWildMember(requireDoubles(), leader);
            if (!reinforcement.getPokemon().getAspects().contains(REGIONAL_ASPECT)) {
                helper.fail(caseName + ": an " + REGIONAL_ASPECT + " leader brought out a "
                        + reinforcement.getPokemon().getForm().getName() + " one (aspects="
                        + reinforcement.getPokemon().getAspects() + ")");
            }
            requireSameStock(leader, reinforcement);
            endCase();
        }

        void requireSmallPartyIsLeftAlone() {
            beginCase("ONE_POKEMON_PARTY", TRIGGER_SPECIES, 2, 1, 100);
            requireOrdinaryEncounter();
            endCase();
        }

        void requireZeroChanceIsLeftAlone() {
            beginCase("CHANCE_ZERO", TRIGGER_SPECIES, 2, 2, 0);
            requireOrdinaryEncounter();
            endCase();
        }

        /**
         * Legendaries are excluded by their own {@code legendary} label rather than by name, which is
         * what lets a species from a data pack add-on be eligible without being listed anywhere here.
         */
        void requireLegendariesAreLeftAlone() {
            beginCase("LEGENDARY", LEGENDARY_SPECIES, 2, 2, 100);
            requireOrdinaryEncounter();
            endCase();
        }

        void restoreDefaults() {
            HordeConfig.bind(() -> HordeConfig.DEFAULT_HORDE_CHANCE_PERCENT);
        }

        // --- plumbing ------------------------------------------------------------------------

        private void beginCase(String name, String wildSpecies, int wildCount, int partySize, int chancePercent) {
            beginCase(name, () -> species(wildSpecies).create(WILD_LEVEL), wildCount, partySize, chancePercent);
        }

        private void beginCase(String name, Supplier<Pokemon> wildFactory, int wildCount, int partySize,
                               int chancePercent) {
            caseName = name;
            HordeConfig.bind(() -> chancePercent);
            player = mockPlayer(helper);
            PartyStore party = PlayerExtensionsKt.party(player);
            party.clearParty();
            for (int i = 0; i < partySize; i++) {
                party.add(species(PLAYER_SPECIES[i % PLAYER_SPECIES.length]).create(PLAYER_LEVEL));
            }
            wild = spawnWild(helper, wildFactory, wildCount);

            boolean singleBattleSurvived = wild.get(0).forceBattle(player);
            Constants.LOG.info("{} TRIGGER {} forceBattle={} chance={} wild={} party={}",
                    TAG, caseName, singleBattleSurvived, chancePercent, wildCount, partySize);
        }

        /** The battle the player is in, failing the case unless it is a horde-shaped one. */
        private PokemonBattle requireDoubles() {
            PokemonBattle battle = currentBattle();
            if (battle.getFormat().getBattleType().getSlotsPerActor() != HordeBattles.slotsPerActor()) {
                helper.fail(caseName + ": the player is in a "
                        + battle.getFormat().getBattleType().getName() + " battle, not a horde");
            }
            List<BattlePokemon> wildTeam = wildTeamOf(battle);
            if (wildTeam.size() != HordeBattles.slotsPerActor()) {
                helper.fail(caseName + ": the wild side holds " + wildTeam.size()
                        + " Pokemon, expected " + HordeBattles.slotsPerActor());
            }
            return battle;
        }

        /**
         * The horde member that is not the Pokemon the player walked into.
         *
         * <p>Every member has to be a distinct entity that is really in the world and really claimed
         * by this battle. A wild side holding the leader twice would satisfy a check on team size
         * alone, and an unclaimed member lets a second player open their own battle with it.</p>
         */
        private PokemonEntity otherWildMember(PokemonBattle battle, PokemonEntity leader) {
            PokemonEntity other = null;
            for (BattlePokemon member : wildTeamOf(battle)) {
                PokemonEntity entity = member.getEntity();
                if (entity == null) {
                    helper.fail(caseName + ": a horde member has no entity in the world");
                    continue;
                }
                if (!battle.getBattleId().equals(entity.getBattleId())) {
                    helper.fail(caseName + ": " + entity.getPokemon().getSpecies().getName()
                            + " is on the wild side but its battleId is " + entity.getBattleId());
                }
                if (entity.getId() == leader.getId()) {
                    continue;
                }
                if (other != null) {
                    helper.fail(caseName + ": more than one horde member is not the leader");
                }
                other = entity;
            }
            if (other == null) {
                helper.fail(caseName + ": the wild side is the same Pokemon in both slots");
                throw new IllegalStateException("unreachable");
            }
            if (other.isRemoved() || !other.isAlive()) {
                helper.fail(caseName + ": the second horde member is not alive in the world");
            }
            return other;
        }

        /**
         * What "the same kind of Pokemon" means for a pair the player is looking at.
         *
         * <p>Species, form, level and shininess are the four the player can see at a glance. A
         * separate Pokemon, though - different UUID, different entity - because two slots backed by
         * one Pokemon is a different bug that this would otherwise hide.</p>
         */
        private void requireSameStock(PokemonEntity leader, PokemonEntity other) {
            Pokemon a = leader.getPokemon();
            Pokemon b = other.getPokemon();
            if (!a.getSpecies().getResourceIdentifier().equals(b.getSpecies().getResourceIdentifier())) {
                helper.fail(caseName + ": species differ - " + a.getSpecies().getResourceIdentifier()
                        + " and " + b.getSpecies().getResourceIdentifier());
            }
            if (!a.getForm().getName().equals(b.getForm().getName())) {
                helper.fail(caseName + ": forms differ - " + a.getForm().getName()
                        + " and " + b.getForm().getName());
            }
            if (a.getLevel() != b.getLevel()) {
                helper.fail(caseName + ": levels differ - " + a.getLevel() + " and " + b.getLevel());
            }
            if (a.getShiny() != b.getShiny()) {
                helper.fail(caseName + ": shininess differs - " + a.getShiny() + " and " + b.getShiny());
            }
            if (!a.getAspects().equals(b.getAspects())) {
                helper.fail(caseName + ": aspects differ - " + a.getAspects() + " and " + b.getAspects());
            }
            if (a.getUuid().equals(b.getUuid())) {
                helper.fail(caseName + ": both horde slots are backed by one Pokemon (" + a.getUuid() + ")");
            }
            if (b.getCurrentHealth() <= 0) {
                helper.fail(caseName + ": the second horde member came out fainted");
            }
        }

        private void requireOrdinaryEncounter() {
            PokemonBattle battle = currentBattle();
            List<BattlePokemon> wildTeam = wildTeamOf(battle);
            if (battle.getFormat().getBattleType().getSlotsPerActor() != 1 || wildTeam.size() != 1) {
                helper.fail(caseName + ": this encounter should have stayed an ordinary single battle,"
                        + " but it is " + battle.getFormat().getBattleType().getName()
                        + " with " + wildTeam.size() + " wild Pokemon");
            }
        }

        private PokemonBattle currentBattle() {
            PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
            if (battle == null) {
                helper.fail(caseName + ": the encounter left the player in no battle at all");
                throw new IllegalStateException("unreachable");
            }
            return battle;
        }

        private static List<BattlePokemon> wildTeamOf(PokemonBattle battle) {
            for (BattleActor actor : battle.getActors()) {
                if (!(actor instanceof PlayerBattleActor)) {
                    return actor.getPokemonList();
                }
            }
            return List.of();
        }

        /**
         * Clears the level for the next case.
         *
         * <p>The wild side of the battle is swept as well as the Pokemon this rig spawned, because a
         * reinforcement the trigger brought out is not in {@link #wild} and would otherwise still be
         * standing there when the next case spawns a leader of the same species - which is exactly the
         * neighbour the next case is asserting does not exist.</p>
         */
        void endCase() {
            Set<PokemonEntity> leaving = new LinkedHashSet<>(wild);
            PokemonBattle battle = player == null ? null : BattleRegistry.getBattleByParticipatingPlayer(player);
            if (battle != null) {
                for (BattlePokemon member : wildTeamOf(battle)) {
                    PokemonEntity entity = member.getEntity();
                    if (entity != null) {
                        leaving.add(entity);
                    }
                }
            }
            removePlayer(helper, player);
            for (PokemonEntity entity : leaving) {
                if (!entity.isRemoved()) {
                    entity.discard();
                }
            }
            wild = List.of();
            player = null;
        }

        /** A wild Pokemon from a property string, e.g. {@code "species=vulpix alolan"}. */
        private static Pokemon parseWild(String properties) {
            return PokemonProperties.Companion
                    .parse(properties + " level=" + WILD_LEVEL, " ", "=")
                    .create();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private static Species species(String name) {
        Species species = PokemonSpecies.getByName(name);
        if (species == null) {
            throw new IllegalStateException("Species registry does not know " + name);
        }
        return species;
    }

    /** Wild Pokemon of one species, spaced two blocks apart, at the wild level. */
    private static List<PokemonEntity> spawnWild(GameTestHelper helper, String speciesName, int count) {
        return spawnWild(helper, () -> species(speciesName).create(WILD_LEVEL), count);
    }

    /** As above, for wild Pokemon that need more saying about them than a species name. */
    private static List<PokemonEntity> spawnWild(GameTestHelper helper, Supplier<Pokemon> factory, int count) {
        ServerLevel level = helper.getLevel();
        List<PokemonEntity> spawned = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Pokemon pokemon = factory.get();
            Vec3 pos = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5)).add(3.0, 0.0, i * 2.0);
            PokemonEntity entity = pokemon.sendOut(level, pos, null, e -> Unit.INSTANCE);
            if (entity == null) {
                throw new IllegalStateException("Could not spawn wild " + pokemon.getSpecies().getName());
            }
            spawned.add(entity);
        }
        return spawned;
    }

    private static List<PokemonEntity> spawnHorde(GameTestHelper helper, int size) {
        ServerLevel level = helper.getLevel();
        List<PokemonEntity> spawned = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Pokemon pokemon = species(WILD_SPECIES[i % WILD_SPECIES.length]).create(WILD_LEVEL);
            Vec3 pos = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5)).add(3.0, 0.0, i * 2.0);
            PokemonEntity entity = pokemon.sendOut(level, pos, null, e -> Unit.INSTANCE);
            if (entity == null) {
                throw new IllegalStateException("Could not spawn wild " + pokemon.getSpecies().getName());
            }
            spawned.add(entity);
        }
        return spawned;
    }

    /**
     * A server player that Cobblemon can find and talk to.
     *
     * <p>This is {@code GameTestHelper.makeMockServerPlayerInLevel} taken apart, because the order
     * matters. That helper hands the player straight to {@code PlayerList.placeNewPlayer}, which
     * fires the join events - and Cobblemon answers those by pushing its data registries at the new
     * player. The mock's connection is an {@link io.netty.channel.embedded.EmbeddedChannel} that
     * never negotiated a modded channel, and NeoForge throws
     * {@code UnsupportedOperationException: Payload ... may not be sent to the client} rather than
     * send over one that did not, which takes the whole server tick loop down. Declaring Cobblemon's
     * payloads as ad-hoc channels on the connection <em>before</em> the player joins is what lets
     * those packets land harmlessly in the embedded channel.</p>
     *
     * <p>The real {@link PlayerBattleActor} is then usable unchanged, which matters for the flee
     * stage: {@code PokemonBattle.checkFlee} reaches for
     * {@code actors.filterIsInstance<PlayerBattleActor>().first()}, so a stand-in actor would turn a
     * flee into a {@code NoSuchElementException}.</p>
     */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "horde-test-player");
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }

            /** Cobblemon reports a refused Poke Ball through a system message and nothing else. */
            @Override
            public void sendSystemMessage(net.minecraft.network.chat.Component message, boolean actionBar) {
                Constants.LOG.info("{} PLAYER_MESSAGE {}", TAG, message.getString());
                super.sendSystemMessage(message, actionBar);
            }
        };

        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        Set<ResourceLocation> adHocChannels = ChannelAttributes.getOrCreateAdHocChannels(connection);
        for (PacketRegisterInfo<?> info : CobblemonNetwork.INSTANCE.getS2cPayloads()) {
            adHocChannels.add(info.getId());
        }

        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);

        Vec3 stand = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5));
        player.teleportTo(stand.x, stand.y, stand.z);
        player.setNoGravity(true);
        return player;
    }

    private static void removePlayer(GameTestHelper helper, ServerPlayer player) {
        if (player == null) {
            return;
        }
        PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
        if (battle != null && !battle.getEnded()) {
            battle.stop();
        }
        helper.getLevel().getServer().getPlayerList().remove(player);
    }
}
