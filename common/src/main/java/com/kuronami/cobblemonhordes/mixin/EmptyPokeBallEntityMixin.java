package com.kuronami.cobblemonhordes.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleType;
import com.cobblemon.mod.common.battles.BattleTypes;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.UUID;

/**
 * Lets the last member of a wild horde be caught.
 *
 * <p>{@code EmptyPokeBallEntity.onHitEntity} refuses the throw when either
 * {@code battle.format.battleType != SINGLES} or the hit actor still has more than one living
 * Pokemon. The second half is the mainline rule - you cannot throw a ball while two opponents are
 * still standing - and stays. The first half is the host being conservative: mainline does allow a
 * ball once a double battle is down to one wild Pokemon.</p>
 *
 * <p>Reporting {@code SINGLES} from that one expression hands the decision entirely to the second
 * condition, which is exactly the mainline rule. Only wild battles are touched; trainer and PvP
 * doubles keep refusing throws as before.</p>
 */
@Mixin(value = EmptyPokeBallEntity.class, remap = false)
public abstract class EmptyPokeBallEntityMixin {

    @ModifyExpressionValue(
            method = "onHitEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/cobblemon/mod/common/battles/BattleFormat;getBattleType()Lcom/cobblemon/mod/common/battles/BattleType;",
                    remap = false
            )
    )
    private BattleType cobblemon_hordes$allowCatchingTheLastWildPokemon(BattleType original, EntityHitResult hitResult) {
        if (original == BattleTypes.INSTANCE.getSINGLES()) {
            return original;
        }
        if (!(hitResult.getEntity() instanceof PokemonEntity pokemonEntity)) {
            return original;
        }
        UUID battleId = pokemonEntity.getBattleId();
        if (battleId == null) {
            return original;
        }
        PokemonBattle battle = BattleRegistry.getBattle(battleId);
        if (battle == null || !battle.isPvW()) {
            return original;
        }
        return BattleTypes.INSTANCE.getSINGLES();
    }
}
