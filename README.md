# Magic NPCs

**Minecraft 1.20.1 · Forge · `modid: magicnpcs`**

Magic NPCs lets NPC mobs cast spells from **Iron's Spells 'n Spellbooks**. It is a
mod-agnostic, **datapack-driven** casting framework that works on any mob, with
first-class support for **Villager Recruits**. It soft-depends on both Iron's and
Recruits and loads cleanly if either is absent.

## How it works

- A mob becomes a spellcaster when a **datapack loadout** exists for its entity
  type (`data/<namespace>/spellcasters/<name>.json`). No tags, no code.
- On spawn, the mob gets Iron's mana attributes (`MAX_MANA` / `MANA_REGEN`); a
  lightweight AI goal then selects and casts spells at its target via Iron's real
  `AbstractSpell.onCast(..., CastSource.MOB, ...)`. Iron's spawns the spell's own
  particles and sounds server-side.
- Mana and cooldowns are owned by Magic NPCs (Iron's does not run its player-side
  economy for foreign mobs); mana regenerates each tick from `MANA_REGEN`.

### Villager Recruits (optional)

When Recruits is installed, a thin adapter:
- scales a recruit's mana pool by its **rank** (`getXpLevel()`), updating as it levels;
- routes targeting through Recruits' own diplomacy-aware `shouldAttack()` so recruits
  cast **only at enemies, never at their owner or allies**;
- adds a **line-of-fire check** that skips a cast when an ally is between the recruit
  and its target (or inside an AoE's blast radius).

Ships curated combat loadouts for `recruit`, `bowman`, `crossbowman`, and `captain`.

Optionally (config `recruits.useIronsAI`, default off), recruits use Iron's *own*
combat AI (`WizardAttackGoal`: distance-aware selection, fleeing) via a Mixin that
makes them `IMagicEntity`; the actual cast still routes through the proven path above.

## Dependencies

| Mod | Required? | Version (1.20.1) |
|-----|-----------|------------------|
| Forge | yes | **47.4.0+** (required by Iron's 3.15.x) |
| Iron's Spells 'n Spellbooks | for any casting | `1.20.1-3.15.x` |
| GeckoLib | (Iron's dep) | `4.8+` |
| Curios API | (Iron's dep) | `5.4.7+` |
| PlayerAnimator | (Iron's dep) | `1.0.2-rc1+` |
| Villager Recruits | optional | `1.15.0+` (enables the recruit adapter) |

Magic NPCs **does not bundle** Iron's or Recruits — install them yourself.

## Datapack loadout schema

`data/<namespace>/spellcasters/<anything>.json`:

```json
{
  "entity_type": "minecraft:skeleton",
  "max_mana": 100,
  "mana_regen": 10,
  "spells": [
    {
      "spell": "irons_spellbooks:magic_missile",
      "level": 1,
      "weight": 3,
      "min_range": 0.0,
      "max_range": 16.0,
      "safety_radius": 1.0,
      "role": "attack"
    },
    { "spell": "irons_spellbooks:heal", "level": 1, "role": "support" }
  ]
}
```

| Field | Default | Meaning |
|-------|---------|---------|
| `entity_type` | — | target entity id (the opt-in; one loadout per type) |
| `max_mana` / `mana_regen` | 100 / 10 | base values for the mob's Iron's mana attributes |
| `spell` | — | an Iron's spell registry id |
| `level` | 1 | spell level to cast at |
| `weight` | 1 | relative pick weight among castable spells |
| `min_range` / `max_range` | 0 / 20 | target distance window (blocks) for `attack` spells |
| `safety_radius` | 1.5 | friendly-fire clearance (blocks); larger for AoE spells |
| `role` | `attack` | `attack` (aim at the hostile target) or `support` (self-cast when hurt) |

Datapacks override the shipped loadouts — change a mob's spells without touching code.

## Configuration

Server config `config/magicnpcs-server.toml` (auto-synced to clients):

- **general** — `enableSpellcasting`, `debugLogging`
- **balance** — `manaMultiplier`, `cooldownMultiplier`, `regenMultiplier`,
  `decisionIntervalTicks`, `supportHealthThreshold`, `friendlyFireCheck`
- **spells** — `spellBlacklist`, `spellWhitelist`
- **recruits** — `enabled`, `manaPerLevel`, `useIronsAI`, `ironsAiSpeed`, `ironsAiIntervalTicks`

## Building

```
./gradlew build
```
Produces `build/libs/magicnpcs-<version>.jar` (reobfuscated; ships no third-party
classes). For an in-dev runtime with Iron's + Recruits, see
[`docs/dev-runtime.md`](docs/dev-runtime.md). The Recruits jar belongs in `libs/`
(see that doc); it is compile-only and never bundled or committed.

## License

GPL-3.0 (see [`LICENSE`](LICENSE)). Do **not** redistribute Iron's Spells 'n
Spellbooks or Villager Recruits jars/assets — both are restrictively licensed and
are only ever compile-time dependencies here.
