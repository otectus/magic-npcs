package com.otectus.magicnpcs.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates the Recruits magic mixin: applied only when BOTH Iron's Spellbooks and
 * Villager Recruits are present. Detection uses {@code getResource} probing (NOT
 * {@code Class.forName}), so it never force-loads a foreign class — mirroring the
 * ars-n-spells {@code ArsNSpellsMixinPlugin} pattern.
 */
public class MagicNpcsMixinPlugin implements IMixinConfigPlugin {

    private boolean ironsPresent;
    private boolean recruitsPresent;

    @Override
    public void onLoad(String mixinPackage) {
        ironsPresent = resourceExists("io/redspace/ironsspellbooks/api/entity/IMagicEntity.class");
        recruitsPresent = resourceExists("com/talhanation/recruits/entities/AbstractRecruitEntity.class");
    }

    private static boolean resourceExists(String path) {
        return MagicNpcsMixinPlugin.class.getClassLoader().getResource(path) != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("MixinAbstractRecruitEntityMagic")) {
            return ironsPresent && recruitsPresent;
        }
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
