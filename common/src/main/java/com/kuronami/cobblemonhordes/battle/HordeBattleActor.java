package com.kuronami.cobblemonhordes.battle;

import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.FleeableBattleActor;
import com.cobblemon.mod.common.api.net.NetworkPacket;
import com.cobblemon.mod.common.battles.actor.MultiPokemonBattleActor;
import com.cobblemon.mod.common.battles.ai.RandomBattleAI;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * A wild-side {@link com.cobblemon.mod.common.api.battles.model.actor.BattleActor} that holds a whole
 * horde of Pokemon in a single actor.
 *
 * <p>Cobblemon ships {@link MultiPokemonBattleActor}, which already covers the "one AI actor, many
 * Pokemon" case, but it implements neither {@link EntityBackedBattleActor} nor
 * {@link FleeableBattleActor}. Without those,
 * {@code PokemonBattle.checkFlee()} sees no fleeable actor with a position, concludes the wild side is
 * out of range and immediately calls {@code stop()}. This subclass supplies the four members
 * {@code PokemonBattleActor} implements, generalised from one Pokemon to a list.</p>
 */
public class HordeBattleActor extends MultiPokemonBattleActor
        implements EntityBackedBattleActor<PokemonEntity>, FleeableBattleActor {

    private final float fleeDistance;
    @Nullable
    private final Vec3 initialPos;

    public HordeBattleActor(List<BattlePokemon> pokemonList, float fleeDistance) {
        // Kotlin default arguments are not visible from Java, so all three are passed explicitly.
        // RandomBattleAI cannot answer for a horde that has lost a member; see HordeBattleAI.
        super(pokemonList, new HordeBattleAI(new RandomBattleAI()), UUID.randomUUID());
        this.fleeDistance = fleeDistance;
        PokemonEntity representative = firstLivingEntity();
        this.initialPos = representative == null ? null : representative.position();
    }

    /** First horde member that is still alive and still has an entity in the world. */
    @Nullable
    private PokemonEntity firstLivingEntity() {
        for (BattlePokemon battlePokemon : getPokemonList()) {
            PokemonEntity entity = battlePokemon.getEntity();
            if (entity != null && battlePokemon.getHealth() > 0) {
                return entity;
            }
        }
        // Everything alive has already been recalled/captured; fall back to any entity we still have.
        for (BattlePokemon battlePokemon : getPokemonList()) {
            PokemonEntity entity = battlePokemon.getEntity();
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    // --- EntityBackedBattleActor ---------------------------------------------------------------

    @Nullable
    @Override
    public PokemonEntity getEntity() {
        return firstLivingEntity();
    }

    @Nullable
    @Override
    public Vec3 getInitialPos() {
        return initialPos;
    }

    // --- FleeableBattleActor -------------------------------------------------------------------

    @Override
    public float getFleeDistance() {
        return fleeDistance;
    }

    /**
     * Position the flee check measures the player's distance against. Mirrors
     * {@code PokemonBattleActor.getWorldAndPosition()}: an owner player wins over the entity, because
     * capturing a horde member removes its entity from the world and that otherwise looks like the
     * Pokemon perished, which is grounds for a flee.
     */
    @Nullable
    @Override
    public kotlin.Pair<ServerLevel, Vec3> getWorldAndPosition() {
        for (BattlePokemon battlePokemon : getPokemonList()) {
            var owner = battlePokemon.getEffectedPokemon().getOwnerPlayer();
            if (owner != null) {
                return new kotlin.Pair<>(owner.serverLevel(), owner.position());
            }
        }
        PokemonEntity entity = firstLivingEntity();
        if (entity == null) {
            return null;
        }
        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        return new kotlin.Pair<>(serverLevel, entity.position());
    }

    // --- Battle lifecycle ----------------------------------------------------------------------

    @Override
    public void sendUpdate(NetworkPacket<?> packet) {
        super.sendUpdate(packet);
        if (packet instanceof BattleEndPacket) {
            for (BattlePokemon battlePokemon : getPokemonList()) {
                PokemonEntity entity = battlePokemon.getEntity();
                if (entity != null) {
                    entity.setBattleId(null);
                }
            }
        }
    }
}
