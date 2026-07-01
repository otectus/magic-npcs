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
In this mode selection/fleeing are Iron's, so the built-in **line-of-sight, friendly-fire,
Peaceful, and spell-focus gates do not apply** (the cast still goes through Magic NPCs'
mana economy). Leave it off if you rely on those safety gates.

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

## Datapacks: make any mob a spellcaster

A mob becomes a caster when a **loadout** JSON exists for its entity type. No code, no
tags — just a small data file you can drop into any datapack.

### 1. Where the files go

A datapack lives in `<world>/datapacks/<your_pack>/`. The minimum is two files:

```
<world>/datapacks/my_magic/
├── pack.mcmeta
└── data/
    └── my_magic/                 ← any namespace you like
        └── spellcasters/
            └── elite_wizard.json ← any file name you like
```

`pack.mcmeta` (Minecraft **1.20.1 → `pack_format` 15**):

```json
{ "pack": { "pack_format": 15, "description": "My Magic NPCs loadouts" } }
```

Then run `/reload` (or rejoin the world); `/datapack list` confirms it loaded. The
loadout's **`entity_type`** is what matters — not the file name or namespace.

### 2. The loadout, field by field

```json
{
  "entity_type": "minecraft:skeleton",
  "max_mana": 100,
  "mana_regen": 10,
  "spells": [
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "weight": 3,
      "min_range": 0.0, "max_range": 16.0, "safety_radius": 1.0, "role": "attack" },
    { "spell": "irons_spellbooks:heal", "level": 1, "role": "support" }
  ]
}
```

| Field | Default | Meaning |
|-------|---------|---------|
| `entity_type` | — | target entity id (the opt-in) |
| `profession` | *(none)* | optional villager profession id — scopes the loadout to one profession (see below) |
| `max_mana` / `mana_regen` | 100 / 10 | base values for the mob's Iron's mana attributes |
| `spell` | — | an Iron's spell registry id |
| `level` | 1 | spell level to cast at |
| `weight` | 1 | relative pick weight among castable spells |
| `min_range` / `max_range` | 0 / 20 | target distance window (blocks) for `attack` spells |
| `safety_radius` | 1.5 | friendly-fire clearance (blocks); larger for AoE spells |
| `role` | `attack` | `attack` (aim at the hostile target) or `support` (self-cast when hurt) |
| `cast_chance` | *(global `castChance`)* | optional [0..1] chance to actually cast on each decision (a "hesitation") |
| `cooldown` | *(none)* | optional explicit cooldown in ticks; overrides the multiplier path (floored by `minCooldownTicks`) |
| `cooldown_multiplier` | *(global `cooldownMultiplier`)* | optional per-spell cooldown multiplier (ignored if `cooldown` is set) |
| `windup` | *(global `castWindupTicks`)* | optional aim wind-up in ticks before this attack spell fires |
| `condition` | *(none)* | optional reactive trigger object — see [Reactive conditions](#9-reactive-conditions) |

The four tuning fields (`cast_chance`/`cooldown`/`cooldown_multiplier`/`windup`) are optional;
omit them to inherit the matching global config default. `condition` is also optional.

#### Cooldowns, precisely (how to make a spell fire more/less often)

There are two ways to set a spell's cooldown. **20 ticks = 1 second.**

- **`cooldown`** — an **exact** cooldown in **ticks**. This is the clearest option and what we
  recommend for modpack authors. `"cooldown": 100` = a 5-second cooldown, full stop.
- **`cooldown_multiplier`** — multiplies the spell's **Iron's default** cooldown.
  - A multiplier **above `1.0` makes the spell _slower_** (longer cooldown).
  - A multiplier **below `1.0` makes the spell _faster_** (shorter cooldown).
  - So raising `cooldown_multiplier` does **not** reduce the cooldown — it increases it.

`cooldown` always wins over `cooldown_multiplier`. Every result is floored by the global
`balance.minCooldownTicks` (default 20), so that setting can stop a cooldown going below a floor.

**Example — a Phantom that casts `echoing_strikes` every 5 seconds** (`echoing_strikes`'s Iron's
default is ~60 s, but the explicit `cooldown` overrides it entirely):

```json
{
  "entity_type": "minecraft:phantom",
  "max_mana": 100,
  "mana_regen": 10,
  "spells": [
    { "spell": "irons_spellbooks:echoing_strikes", "level": 1, "role": "attack", "cooldown": 100 }
  ]
}
```

The multiplier form `"cooldown_multiplier": 0.0833` (≈ 5⁄60) reaches roughly the same 5 seconds, but
explicit `"cooldown": 100` is clearer — prefer it.

#### Finding valid spell IDs

`spell` is an Iron's registry id, always namespaced as `irons_spellbooks:<id>`. To discover them:

- Run **`/magicnpcs spells`** in-game (or `/magicnpcs spells fire` to filter) for the live list with
  each spell's school, rarity, default cooldown, and a mob-friendly hint.
- See [`docs/irons_spell_ids.md`](docs/irons_spell_ids.md) for a generated reference, and
  [`docs/mob-friendly-spells.md`](docs/mob-friendly-spells.md) for which spells suit mobs.

**Watch the namespace.** A bare id like `devour` is read as `minecraft:devour` (not a spell). Magic
NPCs auto-retries bare ids under `irons_spellbooks:`, and logs a clear warning for ids it still can't
resolve — but write the full id. Frequently-confused examples: `irons_spellbooks:blight` is correct
as-is; `devour` → `irons_spellbooks:devour`; `fangward` → `irons_spellbooks:fang_ward`.

#### Four different "chance" knobs (don't mix them up)

| Knob | Controls |
|------|----------|
| `weight` (per spell) | **which** spell is picked among the currently-eligible ones |
| `cast_chance` (per spell) | **whether** the NPC actually casts after that spell is picked (a hesitation) |
| `equipment.chance` / `equipment.spawnWithGearChance` | **whether a mob gets casting gear** on spawn |
| `schools.*.casterChance` | **whether a generated school-caster exists** at all |

### 3. A worked example (annotated)

A multi-spell "battlemage" mixing a ranged nuke, a spammable bolt, and self-healing:

```json
{
  "entity_type": "minecraft:vindicator",
  "max_mana": 140,
  "mana_regen": 10,
  "spells": [
    { "spell": "irons_spellbooks:fireball",      "level": 2, "weight": 1,
      "min_range": 6.0, "max_range": 24.0, "safety_radius": 4.0, "role": "attack" },
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "weight": 3,
      "min_range": 0.0, "max_range": 16.0, "safety_radius": 1.0, "role": "attack" },
    { "spell": "irons_spellbooks:heal",          "level": 1, "weight": 1, "role": "support" }
  ]
}
```

- **`weight`** sets relative pick odds: `magic_missile` (3) is chosen ~3× as often as
  `fireball` (1) when both are eligible.
- **`min_range` / `max_range`** is the engagement window: `fireball` holds until the target
  is ≥6 blocks away (don't nuke point-blank); `magic_missile` fires from 0–16.
- **`safety_radius`** is friendly-fire clearance — large (4) for the AoE `fireball`, small (1)
  for the single-target missile. A cast is **skipped** if an ally/bystander sits inside it.
- **`role: support`** spells self-cast only when the caster drops below
  `balance.supportHealthThreshold` (default 50% HP), and ignore range.

### 4. Targeting a modded mob

`entity_type` accepts any registered entity id. JSON has no comments, but Magic NPCs
ignores unknown keys, so a `__comment` is a handy in-file note:

```json
{
  "__comment": "Verify the id with /summon or the mod's registry.",
  "entity_type": "somemod:dark_knight",
  "max_mana": 120, "mana_regen": 9,
  "spells": [ { "spell": "irons_spellbooks:fireball", "level": 2, "role": "attack" } ]
}
```

For NPC mods gated behind a `[compat]` toggle (Guard Villagers, MCA, …), also enable that
toggle — see [Supported NPC mods](#supported-npc-mods). Ready examples live in
[`docs/loadouts/`](docs/loadouts/README.md).

### 5. Only certain villagers: profession scoping

Add `"profession"` to apply a loadout to **only** villagers of that profession; other
villagers of the same type are untouched. A profession-less loadout for the same type acts
as the fallback.

```json
{
  "entity_type": "minecraft:villager",
  "profession": "minecraft:cleric",
  "max_mana": 90, "mana_regen": 9,
  "spells": [
    { "spell": "irons_spellbooks:guiding_bolt", "level": 1, "role": "attack" },
    { "spell": "irons_spellbooks:heal",         "level": 1, "role": "support" }
  ]
}
```

> Vanilla villagers only actually cast when they have a target (raids, or a guard/NPC mod
> grants combat AI) — their peaceful behaviour is preserved.

### 6. Explicit loadouts vs. magic schools

Two ways a mob becomes a caster, resolved in this order:

1. **Explicit loadout** (this section) — a hand-tuned, per-type (optionally per-profession)
   spell list. Always wins when one matches.
2. **Magic school** — if no loadout matches, recruits/villagers may be auto-assigned an
   Iron's *school* and have a spell pool built dynamically. See [`docs/schools.md`](docs/schools.md).

Use explicit loadouts for designed encounters; lean on schools for automatic variety.

### 7. Spell focuses (optional)

If you set `equipment.requireSpellFocus = true`, casters must hold an item in the
`magicnpcs:spell_focuses` item tag. That tag ships pre-filled with Iron's focuses
(`#irons_spellbooks:school_focus`). Add your own staves/spellbooks from a datapack — tag
entries merge across packs:

```json
{
  "replace": false,
  "values": [
    "#irons_spellbooks:school_focus",
    "yourmod:fancy_staff"
  ]
}
```

Place that at `data/magicnpcs/tags/items/spell_focuses.json` in your pack. With
`schools.schoolAwareFocus = true`, a school caster may instead hold a focus for **its own**
school (e.g. an Iron's *fire focus* for a fire NPC).

**Concrete example — only cast while holding a Pyrium Staff.** Restrict casting to a specific
weapon by putting just that item in the tag and enabling `equipment.requireSpellFocus`:

```json
{
  "replace": false,
  "values": [
    "irons_spellbooks:pyrium_staff"
  ]
}
```

> **Verify the item id.** `irons_spellbooks:pyrium_staff` is correct in current Iron's, but item ids
> vary by version. Confirm yours with `/give @s irons_spellbooks:pyrium_staff`, by hovering the item
> with advanced tooltips (F3+H), or in JEI/EMI. The tag entry is silently ignored if the id doesn't
> resolve.

### 7b. Weighted starting equipment (optional)

A loadout may grant gear on spawn with a per-loadout `equipment` block — useful with
`requireSpellFocus`, or just to arm casters with staves. It's fully optional and backward-compatible:
omit it and the global `equipment.spawnWithGearChance` behaviour is unchanged.

```json
{
  "entity_type": "minecraft:skeleton",
  "max_mana": 100,
  "mana_regen": 10,
  "equipment": {
    "mainhand": [
      { "item": "irons_spellbooks:pyrium_staff",    "weight": 5 },
      { "item": "irons_spellbooks:graybeard_staff", "weight": 1 }
    ],
    "offhand": [ "minecraft:shield" ],
    "chance": 0.35,
    "only_if_empty": true
  },
  "spells": [
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "role": "attack",
      "cooldown": 100, "windup": 10 }
  ]
}
```

| Field | Default | Meaning |
|-------|---------|---------|
| `mainhand` / `offhand` | *(none)* | weighted item list; each entry is a bare `"id"` (weight 1) or `{ "item", "weight" }` |
| `chance` | `1.0` | probability [0..1] the gear is granted at all |
| `only_if_empty` | `true` | only fill a hand that's currently empty (won't overwrite existing gear) |

A higher `weight` is picked proportionally more often (here the Pyrium Staff appears ~5× as often as
the Graybeard Staff). Verify item ids the same way as focus items above.

### 8. Contextual loadouts (`conditions`)

Add a loadout-level `"conditions"` object to apply a loadout **only** in certain world
contexts; it is checked when the mob spawns or loads. Every field is optional.

```json
{
  "entity_type": "minecraft:skeleton",
  "max_mana": 100, "mana_regen": 10,
  "conditions": {
    "time": "night",
    "dimensions": ["minecraft:overworld"],
    "biomes": ["#minecraft:is_forest", "minecraft:plains"],
    "difficulties": ["normal", "hard"],
    "min_y": 0, "max_y": 128,
    "require_storm": false,
    "require_raid": false,
    "moon_phases": [0]
  },
  "spells": [ { "spell": "irons_spellbooks:magic_missile", "level": 1, "role": "attack" } ]
}
```

| Field | Meaning |
|-------|---------|
| `dimensions` | allowed dimension ids; any if omitted |
| `biomes` | allowed biome ids or `#biome-tags`; any if omitted |
| `difficulties` | allowed `peaceful`/`easy`/`normal`/`hard`; any if omitted |
| `time` | `day`, `night`, or `any` |
| `min_y` / `max_y` | inclusive block-Y band |
| `require_raid` | require an active raid at the mob's position |
| `require_storm` | require thundering weather |
| `moon_phases` | allowed moon phases `0`–`7` |

### 9. Reactive conditions

Give a single spell a `"condition"` so it only fires in the right moment. For a SUPPORT
spell the condition **replaces** the default "cast when below `supportHealthThreshold`"
gate; for an ATTACK spell it is an extra gate on top of range/line-of-sight.

```json
{
  "entity_type": "minecraft:vindicator",
  "max_mana": 140, "mana_regen": 10,
  "spells": [
    { "spell": "irons_spellbooks:fireball", "level": 2, "role": "attack",
      "min_range": 6, "max_range": 24, "safety_radius": 4,
      "condition": { "enemies_within": 3, "enemies_radius": 6 } },
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "weight": 3, "role": "attack",
      "condition": { "target_hp_below": 0.35 } },
    { "spell": "irons_spellbooks:oakskin", "level": 1, "role": "support",
      "condition": { "self_hp_below": 0.4, "when_recently_hurt": true } }
  ]
}
```

| Field | Meaning |
|-------|---------|
| `self_hp_below` | eligible when the caster's HP fraction is below this (0–1) |
| `target_hp_below` | eligible when the target's HP fraction is below this (an "execute"; ATTACK only) |
| `enemies_within` + `enemies_radius` | eligible when ≥ N hostiles are within the radius (blocks; default 8) — favours AoE when swarmed |
| `when_recently_hurt` + `recent_damage_window` | eligible only if the caster took mob damage within the window (ticks; default 60) — e.g. a blink/retaliation |

A satisfied condition can also raise a spell's pick weight via
`balance`/`reactive.matchedConditionWeightBonus` (default 1.0 = no bias). Set
`reactive.enabled = false` to ignore all conditions (spells fall back to role/range only).

### 10. Loadout pools vs. override (`replace`)

Several loadouts may target the **same** effective key (`entity_type` + optional `profession`).
By default they **pool**: each NPC sticky-picks one variant by `pool_weight` (persisted, so it does
not change on reload), giving natural variety — e.g. some skeletons are fire-mages, others
ice-mages. Combine with `conditions` for "this variant only in the nether", etc.

```json
{ "entity_type": "minecraft:skeleton", "pool_weight": 3, "max_mana": 100, "mana_regen": 10,
  "spells": [ { "spell": "irons_spellbooks:firebolt", "level": 1, "role": "attack" } ] }
```
```json
{ "entity_type": "minecraft:skeleton", "pool_weight": 1, "max_mana": 100, "mana_regen": 10,
  "spells": [ { "spell": "irons_spellbooks:icicle", "level": 1, "role": "attack" } ] }
```

> Two files for the same type that used to be a "last one wins" override are now **pooled**.

**To override instead of pool** — e.g. to replace a loadout shipped by another datapack — add a
root-level `"replace": true`. At load time it clears *all* non-replace loadouts for the same
effective key, and only the replace-marked loadout(s) remain:

```json
{ "entity_type": "minecraft:skeleton", "replace": true, "max_mana": 100, "mana_regen": 10,
  "spells": [ { "spell": "irons_spellbooks:icicle", "level": 1, "role": "attack" } ] }
```

When two datapacks target one key and none set `replace`, Magic NPCs logs a warning naming the
sources. Run **`/magicnpcs validate`** to see pooled/duplicate keys and bad ids, and
**`/magicnpcs loadout entity <target>`** (or `loadout id <entity_type>`) to see exactly which spells
a mob resolves to and why each is or isn't eligible.

**OpenLoader / modpacks:** drop your loadout at
`config/openloader/data/<pack>/data/<pack>/spellcasters/<name>.json` (the file name is free; the
`entity_type` field is the opt-in). Add `"replace": true` to win over anything else targeting that
entity. Vanilla skeletons cast nothing by default — see the example below.

### 11. See also

- **Shipped loadouts** (great references) — bundled in the jar under
  `data/magicnpcs/spellcasters/`: `recruit`, `bowman`, `crossbowman`, `captain`, `guard`. These all
  target **optional NPC mods** (Recruits / Guard Villagers), so they stay inert unless that mod is
  installed. There is **no** active `minecraft:skeleton` loadout — an example lives at
  [`docs/loadouts/examples/skeleton.json`](docs/loadouts/examples/skeleton.json) to copy into your
  own datapack.
- [`docs/loadouts/README.md`](docs/loadouts/README.md) — copy-paste examples for optional NPC mods.
- [`docs/schools.md`](docs/schools.md) — the per-NPC magic-school system.
- [`docs/irons_spell_ids.md`](docs/irons_spell_ids.md) — valid Iron's spell ids by school.
- [`docs/mob-friendly-spells.md`](docs/mob-friendly-spells.md) — which spells work well on mobs,
  including the target-locked (`root`, `devour`, `wisp`) and forward-AoE (`stomp`) spells.

### Aiming: how casters face their target

`attack` spells aim before they fire. During the `windup` the caster turns toward the target and
re-checks line of sight and range each tick, and **immediately before the spell is released the
caster's facing is snapped onto the target** — so projectile spells launch *at* the target rather
than in a stale direction, even with `windup: 0`. Give attack spells a `windup` of ~10–20 ticks for a
visible, telegraphed aim; `support`/self-cast spells don't aim at enemies.

## Supported NPC mods

Magic NPCs is mod-agnostic: **any** mob with a datapack loadout casts. On top of
that, these layers add mod-aware safety:

- **Villager Recruits** — first-class compiled adapter: rank-scaled mana,
  diplomacy-aware targeting (`shouldAttack`), and respect for the command system (a
  recruit ordered to a passive/flee state won't cast). Ships loadouts for `recruit`,
  `bowman`, `crossbowman`, `captain`.
- **Generic owner/team protection** — vanilla-only adapter (scoreboard teams +
  `OwnableEntity`, which tamable companions/pets implement). Gives **Human Companions**
  and any owned/teamed NPC friendly-fire safety toward its owner and siblings with no
  hard dependency. Toggle `targeting.protectOwners`.
- **Generic bystander protection** — attack spells won't catch villagers (vanilla,
  MCA, More Villagers, VillagersPlus), iron golems, players, or tamed pets in their
  line of fire / blast radius. Toggle `targeting.protectBystanders`.

Other NPC mods (**Guard Villagers, MCA Reborn, MineColonies, Easy NPC, Human
Companions, More/Plus Villagers**) are supported via **config-gated datapack
loadouts** — no hard dependency. Each has a toggle under `[compat]` (default **off**);
enable it, then drop in a loadout for that mod's entity types. A ready Guard Villagers
loadout ships (inert until `compat.guardvillagers = true`), and copy-paste examples
for the rest live in [`docs/loadouts/`](docs/loadouts/README.md), which also covers
the known limitations (profession-scoped casting and trades are future work).

## Magic schools (recruits & villagers)

Each individual recruit/villager can be assigned a specific Iron's **school** (fire,
ice, lightning, holy, ender, blood, evocation, nature, eldritch); its spell pool is
built dynamically from that school. Assignment is automatic on spawn (persisted), and
also adjustable per-NPC via the `/magicnpcs school` command or the **School Tome** item
(right-click to cycle, sneak to clear). Villagers only actually cast when they have a
target (raids / guard mods) — vanilla passivity is preserved. Full details, including
the `[schools]` config block, are in [`docs/schools.md`](docs/schools.md).

## Configuration

Server config `config/magicnpcs-server.toml` (auto-synced to clients):

- **general** — `enableSpellcasting`, `debugLogging`
- **balance** — `manaMultiplier`, `cooldownMultiplier`, `regenMultiplier`,
  `decisionIntervalTicks`, `castChance`, `minCooldownTicks`, `supportHealthThreshold`,
  `friendlyFireCheck`, `peacefulDisablesCasting`, `difficultyScaling`
- **targeting** — `requireLineOfSight`, `castWindupTicks`, `protectBystanders`, `protectOwners`
- **equipment** — `requireSpellFocus`, `spawnWithGearChance` (both use the
  `magicnpcs:spell_focuses` item tag, which ships pre-filled with Iron's focuses
  (`#irons_spellbooks:school_focus`); add your own staves/spellbooks via a datapack)
- **reactive** — `enabled`, `matchedConditionWeightBonus` (per-spell `condition` blocks;
  see [Reactive conditions](#9-reactive-conditions))
- **feedback** — `telegraphs`, `schoolParticles`, `telegraphGlow`, `telegraphVolume`,
  `minDangerTier` (the cast "tell" shown during a caster's wind-up)
- **spells** — `spellBlacklist`, `spellWhitelist`
- **recruits** — `enabled`, `manaPerLevel`, `useIronsAI`, `ironsAiSpeed`, `ironsAiIntervalTicks`
- **compat** — per-mod loadout toggles (`guardvillagers`, `mca`, `minecolonies`,
  `easynpc`, `humancompanions`, `morevillagers`, `villagersplus`), all default **off**
- **schools** — magic-school assignment (`enableSchools`, `allowedSchools`, `maxRarity`,
  `maxSpellLevel`, `spellsPerSchool`, weighting, base mana, `schoolAwareFocus`…) with
  `schools.recruits.*`, `schools.villagers.*`, and command/item toggles — see
  [`docs/schools.md`](docs/schools.md)

### Disabling risky integrations

Every integration is opt-in or independently toggleable. To keep things conservative
in a large pack: leave all `[compat]` toggles off (the default), keep
`protectBystanders`/`protectOwners`/`requireLineOfSight` on, and use `spellBlacklist`
(or a strict `spellWhitelist`) to bar high-collateral spells. Setting
`enableSpellcasting = false` disables the whole system without removing the mod.

## Building

```
./gradlew build
```
Produces `build/libs/magicnpcs-<version>.jar` (reobfuscated; ships no third-party
classes). The shipped loadouts + the `spell_focuses` tag are generated (committed under
`src/generated/resources`); after editing the data providers, regenerate them with
`./gradlew runData`. For an in-dev runtime with Iron's + Recruits, see
[`docs/dev-runtime.md`](docs/dev-runtime.md). The Recruits jar belongs in `libs/`
(see that doc); it is compile-only and never bundled or committed.

## License

GPL-3.0 (see [`LICENSE`](LICENSE)). Do **not** redistribute Iron's Spells 'n
Spellbooks or Villager Recruits jars/assets — both are restrictively licensed and
are only ever compile-time dependencies here.
