package com.kuronami.cobblemonhordes.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;

import java.util.Optional;

/**
 * One custom criterion trigger for every horde-specific advancement moment.
 *
 * <p>Nothing Cobblemon ships can tell "this was a horde" apart from an ordinary wild encounter:
 * {@code cobblemon:battles_won} and {@code cobblemon:pokemon_defeated} only ever see totals across
 * every battle a player has fought. That distinction only exists in this mod, so one trigger
 * parameterised by {@link Moment} stands in for what would otherwise be three near-identical
 * criterion classes - the minimum this feature needs, not a general-purpose event bus.</p>
 */
public final class HordeEventTrigger extends SimpleCriterionTrigger<HordeEventTrigger.TriggerInstance> {

    /** The three points in a horde's life an advancement cares about. */
    public enum Moment implements StringRepresentable {
        ENCOUNTERED("encountered"),
        DEFEATED("defeated"),
        CAPTURED("captured");

        public static final Codec<Moment> CODEC = StringRepresentable.fromEnum(Moment::values);

        private final String id;

        Moment(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, Moment moment)
            implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                Moment.CODEC.fieldOf("moment").forGetter(TriggerInstance::moment)
        ).apply(instance, TriggerInstance::new));

        boolean matches(Moment actual) {
            return moment == actual;
        }
    }

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, Moment moment) {
        this.trigger(player, instance -> instance.matches(moment));
    }
}
