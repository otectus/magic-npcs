package com.otectus.magicnpcs.core.loadout;

import net.minecraft.resources.ResourceLocation;

import java.util.function.Predicate;

/**
 * The two questions the parser must ask the running game about an id: is it registered, and is the
 * mod that owns its namespace even installed?
 *
 * <p>This replaces the {@code boolean checkRegistries} knob 0.6.2–0.8.0 carried. That knob could only
 * say "check everything" or "check nothing", so "no entity type {@code recruits:recruit} is
 * registered" was indistinguishable from a typo — every shipped optional-mod loadout was REJECTED on
 * an instance without that mod (I1). Splitting the question in two lets {@code LoadoutParser} answer
 * "not installed" with an INFO and a {@link LoadoutRecord.Status#INAPPLICABLE} record, and keep the
 * ERROR for an id whose mod <em>is</em> here.
 *
 * <p>Deliberately free of {@code BuiltInRegistries} and {@code ModList}: the schema unit tests
 * classload this interface without a bootstrapped Minecraft. The live implementation lives inside
 * {@link LoadoutManager}, which only runs on a server.
 */
public interface RegistryChecks {

    boolean entityTypeExists(ResourceLocation id);

    boolean professionExists(ResourceLocation id);

    boolean itemExists(ResourceLocation id);

    /** @return true when the mod owning {@code namespace} is installed (always true for vanilla). */
    boolean modLoaded(String namespace);

    /**
     * Everything exists and every mod is loaded — the unit-test stance, and exactly what
     * {@code checkRegistries = false} used to mean. Never used in production.
     */
    RegistryChecks OFFLINE = of(id -> true, id -> true, id -> true, ns -> true);

    /** Build checks from four lambdas, for tests that want one specific answer to be "no". */
    static RegistryChecks of(Predicate<ResourceLocation> entityType, Predicate<ResourceLocation> profession,
                             Predicate<ResourceLocation> item, Predicate<String> modLoaded) {
        return new RegistryChecks() {
            @Override
            public boolean entityTypeExists(ResourceLocation id) {
                return entityType.test(id);
            }

            @Override
            public boolean professionExists(ResourceLocation id) {
                return profession.test(id);
            }

            @Override
            public boolean itemExists(ResourceLocation id) {
                return item.test(id);
            }

            @Override
            public boolean modLoaded(String namespace) {
                return modLoaded.test(namespace);
            }
        };
    }
}
