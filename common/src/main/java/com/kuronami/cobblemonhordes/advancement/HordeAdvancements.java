package com.kuronami.cobblemonhordes.advancement;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleStartedEvent;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.kuronami.cobblemonhordes.Constants;
import com.kuronami.cobblemonhordes.battle.HordeBattleActor;
import kotlin.Unit;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wires the horde-specific advancement moments to the Cobblemon events that carry them.
 *
 * <p>Purely additive: nothing here changes when or how a horde starts, ends, or gets caught from -
 * it only listens after the fact, the same way {@code HordeTrigger} only listens for the moment to
 * swap a battle out. {@link #register()} is idempotent the same way {@code HordeTrigger.register()}
 * is, for the same reason (both loaders route through the common init, and game tests re-enter
 * it).</p>
 *
 * <p>{@link #HORDE_EVENT} itself is only instantiated here, not registered: {@code TRIGGER_TYPES}
 * freezes before either loader's mod entry point runs, so common code is structurally too late to
 * add to it. Registering it is each loader's job - NeoForge only accepts registrations that arrive
 * through a {@code RegisterEvent}, and Fabric's {@code onInitialize} still runs early enough for a
 * direct {@code Registry.register} call to work, the same split Cobblemon's own
 * {@code registerCriteria()} makes between its two loader modules.</p>
 */
public final class HordeAdvancements {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "horde_event");
    public static final HordeEventTrigger HORDE_EVENT = new HordeEventTrigger();

    /**
     * Battle IDs that were a horde at some point.
     *
     * <p>Deliberately never pruned. A UUID is 16 bytes, and nothing about a session's worth of
     * horde battles comes close to making that bookkeeping worthwhile. It also has to outlive the
     * battle itself: {@code EmptyPokeBallEntity} completes a capture on a real-time delayed callback
     * (roughly one to two seconds after the ball stops shaking), and by the time
     * {@code PokemonCapturedEvent} fires for the horde's last member, {@code BattleVictoryEvent} has
     * already ended the battle and it is gone from {@code BattleRegistry}. Only this set still knows
     * it was ever a horde.</p>
     */
    private static final Set<UUID> HORDE_BATTLE_IDS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static volatile boolean registered;

    private HordeAdvancements() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        CobblemonEvents.BATTLE_STARTED_POST.subscribe(Priority.NORMAL, event -> {
            try {
                onBattleStarted(event);
            } catch (Exception exception) {
                Constants.LOG.error("Failed to consider the horde-encountered advancement", exception);
            }
            return Unit.INSTANCE;
        });
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, event -> {
            try {
                onBattleVictory(event);
            } catch (Exception exception) {
                Constants.LOG.error("Failed to consider the horde-defeated advancement", exception);
            }
            return Unit.INSTANCE;
        });
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL, event -> {
            try {
                onPokemonCaptured(event);
            } catch (Exception exception) {
                Constants.LOG.error("Failed to consider the horde-captured advancement", exception);
            }
            return Unit.INSTANCE;
        });
    }

    private static void onBattleStarted(BattleStartedEvent.Post event) {
        PokemonBattle battle = event.getBattle();
        if (!isHordeBattle(battle)) {
            return;
        }
        HORDE_BATTLE_IDS.add(battle.getBattleId());
        for (ServerPlayer player : battle.getPlayers()) {
            HORDE_EVENT.trigger(player, HordeEventTrigger.Moment.ENCOUNTERED);
        }
    }

    private static void onBattleVictory(BattleVictoryEvent event) {
        if (!HORDE_BATTLE_IDS.contains(event.getBattle().getBattleId())) {
            return;
        }
        // A capture ends the battle too, and that moment belongs to CAPTURED. A battle where
        // anything was caught did not wipe the horde out, so it does not also count as a defeat.
        if (event.getWasWildCapture()) {
            return;
        }
        for (BattleActor winner : event.getWinners()) {
            if (winner instanceof PlayerBattleActor playerActor) {
                ServerPlayer player = playerActor.getEntity();
                if (player != null) {
                    HORDE_EVENT.trigger(player, HordeEventTrigger.Moment.DEFEATED);
                }
            }
        }
    }

    private static void onPokemonCaptured(PokemonCapturedEvent event) {
        PokemonEntity captured = event.getPokeBallEntity().getCapturingPokemon();
        UUID battleId = captured == null ? null : captured.getBattleId();
        if (battleId == null || !HORDE_BATTLE_IDS.contains(battleId)) {
            return;
        }
        HORDE_EVENT.trigger(event.getPlayer(), HordeEventTrigger.Moment.CAPTURED);
    }

    private static boolean isHordeBattle(PokemonBattle battle) {
        for (BattleActor actor : battle.getActors()) {
            if (actor instanceof HordeBattleActor) {
                return true;
            }
        }
        return false;
    }
}
