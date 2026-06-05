# Changelog

All notable changes to Magic NPCs are documented here. Versions follow
`MAJOR.MINOR.PATCH`; this is a pre-1.0 line.

## [0.1.1] — production-validated

No functional code changes from 0.1.0. Validated the runtime in a real Forge **47.4.16**
dedicated server (production/SRG space) with Iron's `1.20.1-3.15.x`, GeckoLib 4.8.3,
Curios 5.14.1+1.20.1, PlayerAnimator 1.0.2-rc1+1.20, and Recruits 1.15.0 installed:

- **Boots cleanly** — Iron's own mixins apply (the SRG-vs-named failure seen in the
  ForgeGradle *dev* runtime is dev-only), and our `AbstractRecruitEntity` mixin loads
  without error.
- **Casting executes:** a tagged skeleton (universal path) and a Villager Recruit
  (adapter path) both cast Magic Missile via `onCast(CastSource.MOB)` with correct mana
  deduction (`mana 100 → 90`, `80 → 70`).
- **Iron's-AI Mixin path works:** with `recruits.useIronsAI=true`, recruits cast via
  Iron's `WizardAttackGoal` (driven through the mixin's `initiateCastSpell`) — no crash.
- Build wiring: `forge_version=47.4.16`; a `-PdevRuntime` runtime-companion block (off by
  default; ships nothing). Default `./gradlew build` stays green + third-party-free.

## [0.1.0] — initial development build

First feature-complete build (Minecraft 1.20.1, Forge). Compiles and packages
cleanly; the full in-game casting pass is pending a dev runtime with Iron's and its
companions (see `docs/dev-runtime.md`).

### Added
- **Mod-agnostic casting core.** Datapack loadouts
  (`data/<ns>/spellcasters/*.json`) make any entity type a spellcaster; an injected
  AI goal selects spells by role/range/mana/cooldown and casts via Iron's
  `AbstractSpell.onCast(..., CastSource.MOB, ...)`. Mana pool + regen + per-spell
  cooldowns are managed by the mod (Iron's runs its economy only for players).
- **SUPPORT spells** self-cast when the caster drops below a health threshold.
- **Villager Recruits adapter** (soft-dep): rank-scaled mana (`getXpLevel()`),
  diplomacy-aware targeting via Recruits' `shouldAttack()`, and a **line-of-fire
  ally check**. Curated loadouts for `recruit`, `bowman`, `crossbowman`, `captain`.
- **Opt-in Iron's mob AI for recruits** (`recruits.useIronsAI`, default off): a
  duck-interface Mixin makes recruits `IMagicEntity` so Iron's `WizardAttackGoal`
  can drive them; the cast itself still routes through the proven path.
- **Server config** (`magicnpcs-server.toml`): global toggle, mana/cooldown/regen
  multipliers, decision interval, support threshold, friendly-fire toggle, spell
  allow/deny lists, and the Recruits options.
- **GameTest harness** (`runGameTestServer`) with an offline boot-sanity test.
- Docs: `README`, `docs/dev-runtime.md`, and ADR `docs/decisions/0001-irons-mob-casting.md`.

### Notes
- Iron's Spells 'n Spellbooks and Villager Recruits are **compile-only** soft
  dependencies — neither is bundled or redistributed.
- API verified by decompiling the target Iron's (3.15.x) and Recruits (1.15.0) jars.
