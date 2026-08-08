package com.kuronami.cobblemonhordes;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.kuronami.cobblemonhordes.battle.HordeBattleActor;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import kotlin.Unit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Spike harness for "does Showdown accept a battle where the wild side holds more than one Pokemon".
 *
 * <p>Deliberately AI-vs-AI with no player actor. {@code PokemonBattle.tick()} only runs
 * {@code checkFlee()} when {@code isPvW} is true, and {@code isPvW} requires a side whose actors are
 * all {@code ActorType.PLAYER}. With no player anywhere, the flee path never executes, so a battle
 * that fails to progress can only have failed inside Showdown. {@code BattleRegistry.startShowdown()}
 * never looks at {@code ActorType} - it only assigns {@code p1,p3,...} / {@code p2,p4,...} by side and
 * packs teams - so the answer transfers to the real player-vs-horde case.</p>
 *
 * <p>Only {@link #spikeControlSingles} asserts. The format probes always succeed and report through
 * {@code [HORDESPIKE]} log lines, because "Showdown refused this format" is a legitimate spike result
 * and must not be confused with a broken harness. A non-zero exit code therefore means the harness
 * itself is broken, and the log lines say which formats were accepted.</p>
 */
@GameTestHolder(Constants.MOD_ID)
public class HordeSpikeGameTests {

    private static final String TAG = "[HORDESPIKE]";
    /** Ticks to let the battle run before reading the result. the game test server ticks flat out, so this is seconds of wall time. */
    private static final int OBSERVE_TICKS = 1200;
    private static final int TIMEOUT_TICKS = 2400;

    /** Species picked by name rather than randomly so a failure is reproducible. */
    private static final String[] SPECIES_POOL = {
            "bulbasaur", "charmander", "squirtle", "pidgey", "rattata", "caterpie"
    };

    /**
     * Builds a team and puts every member on the field as a real {@link PokemonEntity}.
     *
     * <p>Entities are not optional. {@code SwitchInstruction} only assigns
     * {@code activePokemon.battlePokemon} when either the Pokemon already has an entity or the actor
     * does; with neither, the active slots stay empty, the AI has nothing to choose for and the battle
     * parks on turn 1 forever. That stall is a property of the harness, not of the format.</p>
     */
    private static List<BattlePokemon> team(GameTestHelper helper, int size, int offset, int lane) {
        List<BattlePokemon> list = new ArrayList<>();
        ServerLevel level = helper.getLevel();
        for (int i = 0; i < size; i++) {
            Species species = PokemonSpecies.getByName(SPECIES_POOL[(offset + i) % SPECIES_POOL.length]);
            if (species == null) {
                throw new IllegalStateException("Species registry not loaded");
            }
            Pokemon pokemon = species.create(20);
            Vec3 origin = helper.absoluteVec(new Vec3(1.5, 1.0, 1.5));
            Vec3 pos = origin.add(lane * 4.0, 0.0, i * 2.0);
            pokemon.sendOut(level, pos, null, entity -> Unit.INSTANCE);
            list.add(BattlePokemon.Companion.playerOwned(pokemon));
        }
        return list;
    }

    /** fleeDistance -1 disables the flee check outright; belt and braces on top of isPvW being false. */
    private static HordeBattleActor actor(GameTestHelper helper, int size, int offset, int lane) {
        return new HordeBattleActor(team(helper, size, offset, lane), -1F);
    }

    private static PokemonBattle start(String label, BattleFormat format, BattleSide side1, BattleSide side2) {
        BattleStartResult result = BattleRegistry.startBattle(format, side1, side2, false);
        if (!(result instanceof SuccessfulBattleStart success)) {
            Constants.LOG.info("{} {} REJECTED_BY_COBBLEMON result={}", TAG, label, result.getClass().getSimpleName());
            return null;
        }
        PokemonBattle battle = success.getBattle();
        // Default is !SNAPSHOT, i.e. muted in a release jar; without this there is no battleLog to read.
        battle.setMute(false);
        Constants.LOG.info("{} {} SUBMITTED gameType={} actors={}/{} battleId={}",
                TAG, label, format.getBattleType().getName(),
                count(side1), count(side2), battle.getBattleId());
        return battle;
    }

    private static int count(BattleSide side) {
        return side.getActors().length;
    }

    private static void report(String label, PokemonBattle battle) {
        if (battle == null) {
            Constants.LOG.info("{} {} RESULT=NOT_SUBMITTED", TAG, label);
            return;
        }
        Constants.LOG.info("{} {} RESULT started={} turn={} ended={} showdownMessages={} battleLogLines={}",
                TAG, label, battle.getStarted(), battle.getTurn(), battle.getEnded(),
                battle.getShowdownMessages().size(), battle.getBattleLog().size());
        int shown = 0;
        for (String message : battle.getShowdownMessages()) {
            if (shown++ >= 40) {
                break;
            }
            Constants.LOG.info("{} {} SD| {}", TAG, label, message.replace("\n", " \\n "));
        }
        battle.stop();
    }

    /**
     * Control. If this does not reach turn >= 1, nothing else in this class is readable: showdown may
     * not have booted, the species registry may be empty, or {@code BattleRegistry.tick()} may not be
     * wired to the game test server's tick loop.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty3x3x3", timeoutTicks = TIMEOUT_TICKS)
    public static void spikeControlSingles(GameTestHelper helper) {
        String label = "CONTROL_SINGLES";
        PokemonBattle battle = start(label, BattleFormat.Companion.getGEN_9_SINGLES(),
                new BattleSide(actor(helper, 1, 0, 0)), new BattleSide(actor(helper, 1, 1, 1)));
        helper.runAfterDelay(OBSERVE_TICKS, () -> {
            report(label, battle);
            if (battle == null) {
                helper.fail("control battle was never submitted");
            } else if (!battle.getStarted()) {
                helper.fail("control battle never started - showdown harness is broken");
            } else if (battle.getTurn() < 1) {
                helper.fail("control battle started but never reached turn 1 - tick wiring is broken");
            } else {
                helper.succeed();
            }
        });
    }

}
