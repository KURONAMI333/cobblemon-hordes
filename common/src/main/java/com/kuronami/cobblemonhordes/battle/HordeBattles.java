package com.kuronami.cobblemonhordes.battle;

import com.cobblemon.mod.common.api.storage.party.PartyStore;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides whether an encounter becomes a horde, and builds the battle when it does.
 *
 * <p>The horde is one wild actor holding several Pokemon, faced by the player's ordinary
 * {@link PlayerBattleActor}. Showdown's game types are symmetric, so the player fields as many
 * Pokemon as the horde does - which is why {@link #partyCanFaceHorde} exists.</p>
 */
public final class HordeBattles {

    /**
     * Two a side, i.e. Showdown's {@code doubles}. Matches the wild double battles of Gen 3 and
     * Gen 5. Larger hordes would demand a correspondingly larger party from the player.
     */
    public static final BattleFormat FORMAT = BattleFormat.Companion.getGEN_9_DOUBLES();

    private HordeBattles() {
    }

    /** Pokemon each side puts on the field at once. */
    public static int slotsPerActor() {
        return FORMAT.getBattleType().getSlotsPerActor();
    }

    public static int countLiving(List<BattlePokemon> team) {
        int living = 0;
        for (BattlePokemon battlePokemon : team) {
            if (battlePokemon.getHealth() > 0) {
                living++;
            }
        }
        return living;
    }

    /**
     * Whether this team can fill the field for a horde battle.
     *
     * <p>Counts living Pokemon, not party slots, and covers two different failures at once.</p>
     *
     * <p>A team with fewer members than there are active slots leaves {@code side.active[1]} null,
     * and {@code Battle.runAction} walks {@code side.active} with no null guard
     * ({@code battle.js:2411}), so the battle dies on a {@code TypeError} that is both asynchronous
     * and silent. A party of two with one fainted member is a different shape - both members get
     * packed, so the slot is filled rather than null, and Showdown does start that battle (measured:
     * it reaches turn 1). What it produces is a player fielding a fainted Pokemon in the second
     * slot, which is not a battle worth handing anyone. Requiring {@code slotsPerActor} living
     * Pokemon rules out both.</p>
     *
     * <p>Cobblemon's own {@code BattleBuilder.pve} splits its checks differently - team size against
     * {@code slotsPerActor}, plus {@code playerTeam[0].health <= 0} - and going straight to
     * {@link BattleRegistry#startBattle} means this one predicate has to stand in for the lot.</p>
     */
    public static boolean teamCanFaceHorde(List<BattlePokemon> team) {
        return countLiving(team) >= slotsPerActor();
    }

    /**
     * The player's party as a battle team, healthy members first.
     *
     * <p>Ordering mirrors {@code BattleBuilder.pve}: Showdown leads with the first entries of the
     * packed team, so fainted members have to sink to the back.</p>
     */
    public static List<BattlePokemon> battleTeamOf(ServerPlayer player) {
        PartyStore party = PlayerExtensionsKt.party(player);
        List<BattlePokemon> team = new ArrayList<>(party.toBattleTeam(false, false, null));
        team.sort(Comparator.comparing(battlePokemon -> battlePokemon.getHealth() <= 0));
        return team;
    }

    /** Gate for the whole feature: below this, the encounter has to stay an ordinary single battle. */
    public static boolean partyCanFaceHorde(ServerPlayer player) {
        return teamCanFaceHorde(battleTeamOf(player));
    }

    /**
     * Starts a horde battle, or returns null if this encounter cannot be one.
     *
     * <p>Null is not a failure - it is the instruction to leave the ordinary encounter alone. A
     * player who is starting out with a single Pokemon must still get a normal battle.</p>
     */
    @Nullable
    public static BattleStartResult startHordeBattle(
            ServerPlayer player,
            List<PokemonEntity> horde,
            float fleeDistance,
            boolean canPreempt
    ) {
        if (horde.size() < slotsPerActor()) {
            return null;
        }
        List<BattlePokemon> playerTeam = battleTeamOf(player);
        if (!teamCanFaceHorde(playerTeam)) {
            return null;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return null;
        }

        List<BattlePokemon> wildTeam = new ArrayList<>();
        for (PokemonEntity entity : horde) {
            if (entity.getBattleId() != null) {
                return null;
            }
            wildTeam.add(new BattlePokemon(entity.getPokemon(), entity.getPokemon(), new ArrayList<>(), new ArrayList<>()));
        }

        PlayerBattleActor playerActor = new PlayerBattleActor(player.getUUID(), playerTeam);
        HordeBattleActor hordeActor = new HordeBattleActor(wildTeam, fleeDistance);
        return BattleRegistry.startBattle(FORMAT, new BattleSide(playerActor), new BattleSide(hordeActor), canPreempt);
    }
}
