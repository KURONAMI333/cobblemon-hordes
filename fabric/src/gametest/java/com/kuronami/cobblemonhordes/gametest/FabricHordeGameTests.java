package com.kuronami.cobblemonhordes.gametest;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.battles.model.ai.BattleAI;
import com.cobblemon.mod.common.api.pokeball.PokeBalls;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.party.PartyStore;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.ForcePassActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionRequest;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.ai.RandomBattleAI;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.kuronami.cobblemonhordes.Constants;
import com.kuronami.cobblemonhordes.advancement.HordeAdvancements;
import com.kuronami.cobblemonhordes.battle.HordeBattleAI;
import com.kuronami.cobblemonhordes.battle.HordeBattles;
import com.kuronami.cobblemonhordes.battle.HordeTrigger;
import com.mojang.authlib.GameProfile;
import kotlin.Unit;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Fabric half of the horde verification.
 *
 * <p>Everything the loop needs was proven on NeoForge by {@code HordeLoopGameTests}, and none of
 * that carries over: Fabric registers the advancement trigger from {@code onInitialize} instead of a
 * {@code RegisterEvent}, applies the capture mixin through Loom rather than FML, and had never once
 * been run. These four tests are the smallest set that distinguishes "the Fabric jar builds" from
 * "the mod works on Fabric".</p>
 *
 * <p>Registered through the {@code fabric-gametest} entrypoint of a second, dev-only mod declared in
 * {@code src/gametest/resources/fabric.mod.json}, so neither this class nor the entrypoint is in the
 * published jar.</p>
 */
public class FabricHordeGameTests implements FabricGameTest {

    private static final String TAG = "[FABRICHORDE]";

    private static final int PLAYER_LEVEL = 60;
    private static final int WILD_LEVEL = 5;

    /**
     * One heavy hitter and one that cannot knock anything out - the same pairing the NeoForge
     * capture run uses, and for the same reason: two level 60 Pokemon wipe a level 5 horde out on
     * turn one and leave nothing to catch.
     */
    private static final String[] PARTY_SPECIES = { "charizard", "caterpie" };
    private static final int[] PARTY_LEVELS = { PLAYER_LEVEL, WILD_LEVEL };

    /**
     * One wild species per run, shared with nothing else in this suite.
     *
     * <p>Both horde runs build their wild side through {@link HordeTrigger#assembleHorde}, which uses
     * a wild Pokemon of the leader's species standing within {@code HordeConfig.searchRadiusBlocks}
     * before it spawns one. Game tests in a batch run side by side a few blocks apart in one level,
     * well inside that radius, so sharing a species would have the two runs recruit each other's
     * Pokemon instead of spawning: one would skip the path it exists to measure and the other would
     * lose an entity out of the middle of its battle.</p>
     */
    private static final String TURNS_WILD_SPECIES = "sentret";
    private static final String CAPTURE_WILD_SPECIES = "bidoof";

    private static final float FLEE_DISTANCE = 8F;
    private static final int MAX_THROWS = 8;

    private static final int SHORT_TIMEOUT_TICKS = 400;
    private static final int TURN_TIMEOUT_TICKS = 4800;
    /** Battles are paced by Cobblemon's wall-clock message queue, not by ticks (see NeoForge notes). */
    private static final int LONG_TIMEOUT_TICKS = 120000;

    private static final int TURNS_TO_SEE = 2;

    // ---------------------------------------------------------------------------------------
    // 1 + 2: the mod is loaded and its advancement trigger reached the registry
    // ---------------------------------------------------------------------------------------

    /**
     * Fabric registers {@code horde_event} straight from {@code onInitialize}, where NeoForge needs a
     * {@code RegisterEvent} to avoid {@code Registry is already frozen}. That line had never been
     * executed.
     *
     * <p>The advancement half is the load-bearing part. All four advancement JSONs name
     * {@code cobblemon_hordes:horde_event} as their criterion trigger, so if the registration had not
     * happened the datapack codec would drop them and {@code ServerAdvancementManager.get} would come
     * back null - a check the registry lookup alone would not catch if the JSONs were malformed.</p>
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = SHORT_TIMEOUT_TICKS)
    public void advancementTriggerIsRegisteredAndAdvancementsLoad(GameTestHelper helper) {
        CriterionTrigger<?> trigger = BuiltInRegistries.TRIGGER_TYPES.get(HordeAdvancements.ID);
        if (trigger == null) {
            helper.fail("TRIGGER_TYPES has no " + HordeAdvancements.ID
                    + ": Fabric's onInitialize registration did not take");
        }
        if (trigger != HordeAdvancements.HORDE_EVENT) {
            helper.fail("TRIGGER_TYPES holds a different instance under " + HordeAdvancements.ID);
        }

        String[] advancements = { "root", "encounter_horde", "defeat_horde", "capture_from_horde" };
        for (String path : advancements) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path);
            AdvancementHolder holder = helper.getLevel().getServer().getAdvancements().get(id);
            if (holder == null) {
                helper.fail("advancement " + id + " did not load - its criterion trigger is unknown"
                        + " to the datapack loader");
            }
        }
        Constants.LOG.info("{} TRIGGER ok id={} advancements={}", TAG, HordeAdvancements.ID, advancements.length);
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------
    // 3: the capture mixin is on EmptyPokeBallEntity
    // ---------------------------------------------------------------------------------------

    /**
     * Structural check that Loom applied {@code EmptyPokeBallEntityMixin}.
     *
     * <p>Mixin merges the handler into the target class under its own prefixed name, so the method
     * is visible on {@link EmptyPokeBallEntity} itself once the mixin has been applied and absent
     * otherwise. {@link #hordeLastMemberCanBeCaught} proves the same thing behaviourally; this one
     * says which of the two is broken when they disagree, and it still reports when the battle test
     * cannot be reached at all.</p>
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = SHORT_TIMEOUT_TICKS)
    public void captureMixinIsApplied(GameTestHelper helper) {
        List<String> merged = new ArrayList<>();
        for (Method method : EmptyPokeBallEntity.class.getDeclaredMethods()) {
            if (method.getName().contains(Constants.MOD_ID)) {
                merged.add(method.getName());
            }
        }
        Constants.LOG.info("{} MIXIN merged methods on EmptyPokeBallEntity: {}", TAG, merged);
        if (merged.isEmpty()) {
            helper.fail("EmptyPokeBallEntityMixin was not applied: no " + Constants.MOD_ID
                    + " method merged into EmptyPokeBallEntity");
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------
    // 4: a horde battle actually runs
    // ---------------------------------------------------------------------------------------

    /** The battle starts as a 2v2 with two wild Pokemon on the far side, and turns keep resolving. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = TURN_TIMEOUT_TICKS)
    public void hordeBattleStartsAsDoublesAndTurnsResolve(GameTestHelper helper) {
        Rig rig = new Rig(helper, "TURNS", TURNS_WILD_SPECIES, false);
        helper.startSequence()
                .thenExecute(rig::setUp)
                .thenExecute(rig::requireDoublesShape)
                .thenWaitUntil(rig::driveUntilFinished)
                .thenExecute(rig::report)
                .thenExecute(() -> {
                    rig.require(rig.battleStarted, "the battle started");
                    rig.require(rig.maxTurn >= TURNS_TO_SEE,
                            "turns resolved (maxTurn=" + rig.maxTurn + ", choices=" + rig.choicesMade + ")");
                })
                .thenExecute(rig::tearDown)
                .thenSucceed();
    }

    /**
     * The whole loop through to a capture: turns resolve, a horde member faints, the battle carries
     * on, and the last one goes into a ball.
     *
     * <p>That last step is what the mixin exists for - an unpatched Cobblemon refuses every throw in
     * a doubles battle - so reaching it is the behavioural proof that the mixin is live on Fabric.</p>
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = LONG_TIMEOUT_TICKS)
    public void hordeLastMemberCanBeCaught(GameTestHelper helper) {
        Rig rig = new Rig(helper, "CAPTURE", CAPTURE_WILD_SPECIES, true);
        helper.startSequence()
                .thenExecute(rig::setUp)
                .thenWaitUntil(rig::driveUntilFinished)
                .thenExecute(rig::report)
                .thenExecute(() -> {
                    rig.require(rig.battleStarted, "the battle started");
                    rig.require(rig.survivedFaint, "the battle continued after a wild Pokemon fainted");
                    rig.require(rig.captured, "the last wild Pokemon was caught");
                })
                .thenExecute(rig::tearDown)
                .thenSucceed();
    }

    // ---------------------------------------------------------------------------------------
    // Rig
    // ---------------------------------------------------------------------------------------

    private enum Phase { FIGHT, AFTER_FAINT, THROWN, DONE }

    /**
     * Drives a horde battle the way a client does: answers the outstanding request a tick or more
     * after being asked, never from inside {@code sendUpdate}.
     *
     * <p>Trimmed from the NeoForge {@code HordeLoopGameTests.Rig}. The deferral is not a
     * convenience - {@code BattleActor.setActionResponses} ends with {@code checkForInputDispatch()},
     * which clears every actor's request once nobody is left waiting, so an actor answering
     * synchronously destroys the request of every actor after it.</p>
     */
    private static final class Rig {
        private final GameTestHelper helper;
        private final String label;
        private final String wildSpecies;
        private final boolean catchTheLastOne;
        private final BattleAI ai = new HordeBattleAI(new RandomBattleAI());

        private ServerPlayer player;
        private List<PokemonEntity> horde;
        private PokemonBattle battle;
        private PlayerBattleActor playerActor;

        private Phase phase = Phase.FIGHT;
        private int tick;
        private int turnAtFaint = -1;
        private EmptyPokeBallEntity thrownBall;
        private String lastBallState = "";
        private int throwsMade;
        private String failure;

        boolean battleStarted;
        int maxTurn;
        int choicesMade;
        boolean survivedFaint;
        boolean captured;

        Rig(GameTestHelper helper, String label, String wildSpecies, boolean catchTheLastOne) {
            this.helper = helper;
            this.label = label;
            this.wildSpecies = wildSpecies;
            this.catchTheLastOne = catchTheLastOne;
        }

        void setUp() {
            player = mockPlayer(helper);
            PartyStore party = PlayerExtensionsKt.party(player);
            party.clearParty();
            for (int i = 0; i < PARTY_SPECIES.length; i++) {
                party.add(species(PARTY_SPECIES[i]).create(PARTY_LEVELS[i]));
            }

            horde = reinforcedHorde();
            if (horde == null) {
                return;
            }

            BattleStartResult result = HordeBattles.startHordeBattle(player, horde, FLEE_DISTANCE, false);
            if (result == null) {
                helper.fail(label + ": the gate refused a party that should be able to face a horde");
                return;
            }
            if (!(result instanceof SuccessfulBattleStart success)) {
                Constants.LOG.info("{} {} START_REFUSED {}", TAG, label, result.getClass().getSimpleName());
                helper.fail(label + ": BattleRegistry refused the horde battle");
                return;
            }
            battle = success.getBattle();
            battle.setMute(false);
            for (BattleActor actor : battle.getActors()) {
                if (actor instanceof PlayerBattleActor found) {
                    playerActor = found;
                }
            }
            if (playerActor == null) {
                helper.fail(label + ": the horde battle has no player actor");
                return;
            }
            Constants.LOG.info("{} {} SUBMITTED battleId={} format={} playerTeam={} horde={}",
                    TAG, label, battle.getBattleId(), battle.getFormat().getBattleType().getName(),
                    playerActor.getPokemonList().size(), horde.size());
        }

        /**
         * The wild side, built the way an encounter in a running world builds it.
         *
         * <p>One wild Pokemon is spawned and {@link HordeTrigger#assembleHorde} fills the rest of the
         * side, which in an empty test level means it spawns them. Everything this rig goes on to
         * measure therefore runs against a horde member that was brought into the world to be one,
         * not against a pair placed there in advance.</p>
         *
         * <p>Returns null after failing the run, so the caller stops rather than building a battle out
         * of a wild side it does not trust.</p>
         */
        private List<PokemonEntity> reinforcedHorde() {
            PokemonEntity leader = spawnWild(helper, wildSpecies);
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

        /** Shape of the battle at the moment it was built, before a single turn has resolved. */
        void requireDoublesShape() {
            if (battle == null) {
                return;
            }
            int slots = battle.getFormat().getBattleType().getSlotsPerActor();
            if (slots != 2) {
                helper.fail(label + ": the battle has " + slots + " slots per actor, expected 2");
            }
            List<BattlePokemon> wildTeam = hordeActorTeam();
            if (wildTeam.size() != 2) {
                helper.fail(label + ": the wild side holds " + wildTeam.size() + " Pokemon, expected 2");
            }
            for (PokemonEntity entity : horde) {
                if (!battle.getBattleId().equals(entity.getBattleId())) {
                    helper.fail(label + ": " + entity.getPokemon().getSpecies().getName()
                            + " was not claimed by the horde battle (battleId=" + entity.getBattleId() + ")");
                }
            }
            Constants.LOG.info("{} {} SHAPE slots={} wildTeam={}", TAG, label, slots, wildTeam.size());
        }

        void driveUntilFinished() {
            drive();
            if (phase != Phase.DONE) {
                throw new GameTestAssertException(label + " is still running: turn=" + battle.getTurn()
                        + " choices=" + choicesMade + " phase=" + phase
                        + " dispatches=" + battle.getDispatches().size()
                        + " ended=" + battle.getEnded());
            }
        }

        private void drive() {
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

            if (battle.getEnded()) {
                finish("battle ended");
                return;
            }

            if (!catchTheLastOne) {
                if (maxTurn >= TURNS_TO_SEE) {
                    finish("saw " + maxTurn + " turns");
                } else {
                    submitChoice();
                }
                return;
            }

            int wildLiving = HordeBattles.countLiving(hordeActorTeam());

            switch (phase) {
                case FIGHT -> {
                    if (battleStarted && wildLiving <= HordeBattles.slotsPerActor() - 1) {
                        turnAtFaint = battle.getTurn();
                        Constants.LOG.info("{} {} FAINT_OBSERVED tick={} turn={} wildLiving={}",
                                TAG, label, tick, turnAtFaint, wildLiving);
                        if (wildLiving == 0) {
                            failure = "the whole horde fainted at once, so there was nothing left to catch";
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
                    // turn while we say nothing is the proof that the faint did not kill the battle.
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
                            Constants.LOG.info("{} {} BALL tick={} {}", TAG, label, tick, state);
                        }
                    }
                    PokemonEntity last = lastWild();
                    if (last != null && !last.getPokemon().isWild()) {
                        captured = true;
                        finish("captured");
                        return;
                    }
                    if (last == null || last.isRemoved()) {
                        // A capture removes the entity from the world; confirm through the party.
                        captured = PlayerExtensionsKt.party(player).size() > PARTY_SPECIES.length;
                        finish(captured ? "captured (entity gone, party grew)"
                                : "wild entity vanished without a capture");
                        return;
                    }
                    if (thrownBall != null && thrownBall.isRemoved() && battle.getCaptureActions().isEmpty()) {
                        // The ball left the world without starting a capture, which is what a throw
                        // that clipped the ground looks like. A refusal announces itself as a system
                        // message, so retrying cannot paper one over.
                        if (throwsMade >= MAX_THROWS) {
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
         * Answers the outstanding request the way a client does.
         *
         * <p>Throwing a ball claims one slot of the request rather than answering it; Cobblemon parks
         * that as a {@code ForcePassActionResponse} in {@code expectingPassActions} and the client
         * hands it back in the first slot it has not already answered. Leaving it out makes
         * {@code setActionResponses} throw {@code "a capture was expected"}.</p>
         */
        private void submitChoice() {
            if (!playerActor.getMustChoose()) {
                return;
            }
            ShowdownActionRequest request = playerActor.getRequest();
            if (request == null) {
                return;
            }
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
                // No slot can carry the pass. Submitting would throw; wait for the next request.
                return;
            }
            playerActor.setActionResponses(responses);
            choicesMade++;
        }

        /**
         * Throws from point blank, aimed down at the target.
         *
         * <p>A flat throw from a knee-high Caterpie's own height buries itself in the ground, and
         * {@code onHitBlock} drops the ball without a word - which looks exactly like a refused
         * capture.</p>
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
            Constants.LOG.info("{} {} BALL_THROWN #{} tick={} turn={} at={}",
                    TAG, label, throwsMade, tick, battle.getTurn(),
                    target.getPokemon().getSpecies().getName());
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
                Constants.LOG.info("{} {} FINISH tick={} reason={}", TAG, label, tick, why);
                phase = Phase.DONE;
            }
        }

        void report() {
            Constants.LOG.info("{} {} REPORT started={} maxTurn={} choices={} survivedFaint={} captured={} ended={} phase={} failure={}",
                    TAG, label, battleStarted, maxTurn, choicesMade, survivedFaint, captured,
                    battle != null && battle.getEnded(), phase, failure);
            if (battle != null) {
                List<String> log = battle.getBattleLog();
                int from = Math.max(0, log.size() - 40);
                for (int i = from; i < log.size(); i++) {
                    Constants.LOG.info("{} {} LOG| {}", TAG, label, log.get(i).replace("\n", " \\n "));
                }
            }
        }

        void require(boolean condition, String description) {
            if (failure != null) {
                helper.fail(label + ": rig fault before \"" + description + "\": " + failure);
            }
            if (!condition) {
                helper.fail(label + ": " + description + " - did not happen");
            }
        }

        void tearDown() {
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
    // Shared helpers
    // ---------------------------------------------------------------------------------------

    private static Species species(String name) {
        Species found = PokemonSpecies.INSTANCE.getByName(name);
        if (found == null) {
            throw new IllegalStateException("Cobblemon has no species called " + name);
        }
        return found;
    }

    /** A wild Pokemon of one species, at the wild level, a few blocks clear of the player. */
    private static PokemonEntity spawnWild(GameTestHelper helper, String speciesName) {
        ServerLevel level = helper.getLevel();
        Pokemon pokemon = species(speciesName).create(WILD_LEVEL);
        Vec3 pos = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5)).add(3.0, 0.0, 0.0);
        PokemonEntity entity = pokemon.sendOut(level, pos, null, e -> Unit.INSTANCE);
        if (entity == null) {
            throw new IllegalStateException("Could not spawn wild " + pokemon.getSpecies().getName());
        }
        return entity;
    }

    /**
     * A server player Cobblemon can find and talk to.
     *
     * <p>Registered in the real player list so {@code uuid.getPlayer()} resolves it and the ordinary
     * {@link PlayerBattleActor} works unchanged - {@code PokemonBattle.checkFlee} reaches for
     * {@code actors.filterIsInstance<PlayerBattleActor>().first()}, so a stand-in actor would turn
     * that path into a {@code NoSuchElementException}.</p>
     *
     * <p>No equivalent of the NeoForge version's ad-hoc channel registration is needed here: that
     * exists because NeoForge refuses to send a payload over a connection that never negotiated the
     * channel, and Fabric has no such gate.</p>
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
        new io.netty.channel.embedded.EmbeddedChannel(connection);
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
