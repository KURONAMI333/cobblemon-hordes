package com.kuronami.cobblemonhordes.battle;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.ai.BattleAI;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.PassActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import org.jetbrains.annotations.Nullable;

/**
 * Answers "nothing" for a slot that has nothing to answer with, and defers everything else.
 *
 * <p>A horde has exactly as many Pokemon as it has slots, so the moment one faints the actor is
 * holding a slot with no occupant and no bench to draw from. Showdown still lists that slot's moves
 * in the request, and {@code RandomBattleAI} takes the bait: it sees a slot that is gone, finds
 * nothing that {@code canBeSentOut}, and falls back to {@code DefaultActionResponse}. That reaches
 * Showdown as {@code default}, which resolves to {@code autoChoose} - a move for a fainted
 * Pokemon:</p>
 *
 * <pre>
 * &gt;p2 default, move 1
 * |error|[Invalid choice] Can't move: You sent more choices than unfainted Pok&eacute;mon.
 * </pre>
 *
 * <p>Cobblemon answers an invalid choice with {@code &gt;forcetie}, so the whole battle ends in a tie
 * on the turn after the first horde member goes down. A client in the same position sends
 * {@code pass}, which Showdown accepts for any slot in a move request
 * ({@code Side.choosePass}).</p>
 *
 * <p>Ordinary trainer doubles never hit this: a trainer with four Pokemon has someone on the bench,
 * so the switch branch finds a candidate. It takes a team whose size equals the number of slots,
 * which is exactly what a horde is.</p>
 */
public class HordeBattleAI implements BattleAI {

    private final BattleAI delegate;

    public HordeBattleAI(BattleAI delegate) {
        this.delegate = delegate;
    }

    @Override
    public ShowdownActionResponse choose(
            ActiveBattlePokemon activeBattlePokemon,
            PokemonBattle battle,
            BattleSide aiSide,
            @Nullable ShowdownMoveset moveset,
            boolean forceSwitch
    ) {
        if (!forceSwitch && slotIsEmpty(activeBattlePokemon)) {
            return PassActionResponse.INSTANCE;
        }
        if (forceSwitch && !hasSomeoneToSendOut(activeBattlePokemon)) {
            // Showdown grants forcedPassesLeft when a side owes more switches than it can make.
            return PassActionResponse.INSTANCE;
        }
        return delegate.choose(activeBattlePokemon, battle, aiSide, moveset, forceSwitch);
    }

    private static boolean slotIsEmpty(ActiveBattlePokemon activeBattlePokemon) {
        return activeBattlePokemon.isGone() || !activeBattlePokemon.isAlive();
    }

    private static boolean hasSomeoneToSendOut(ActiveBattlePokemon activeBattlePokemon) {
        for (BattlePokemon battlePokemon : activeBattlePokemon.getActor().getPokemonList()) {
            if (battlePokemon.canBeSentOut()) {
                return true;
            }
        }
        return false;
    }
}
