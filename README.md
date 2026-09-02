# Magic NPCs

**Minecraft 1.20.1 · Forge · `modid: magicnpcs`**

Magic NPCs lets NPC mobs cast spells from **Iron's Spells 'n Spellbooks**. It is a
mod-agnostic, **datapack-driven** casting framework that works on any mob, with
first-class support for **Villager Recruits**. It soft-depends on both Iron's and
Recruits and loads cleanly if either is absent.

## How it works

- A mob becomes a spellcaster when a **datapack loadout** exists for its entity
  type (`data/<namespace>/spellcasters/<name>.json`). No tags, no code.
- On spawn — and on every reload, config change or manual assignment — the mob is
  **reconciled**: Magic NPCs works out what it should be running, compares that with what
  it is running, and applies only the difference. A mob already running the right loadout
  is left completely alone, which is what lets a `/reload` preserve mana and cooldowns.
- A reconciled caster gets Iron's mana attributes (`MAX_MANA` / `MANA_REGEN`) and an AI
  goal that selects spells. Casting runs Iron's **real cast lifecycle** — `initiateCast`,
  `onServerPreCast`, per-tick `onServerCastTick`, `onCast`, `onServerCastComplete` — in the
  same order Iron's own casting mobs use, so channelled and continuous spells behave as
  designed rather than being reduced to a single call. Iron's spawns the spell's own
  particles and sounds server-side.
- Mana and cooldowns are owned by Magic NPCs (Iron's does not run its player-side
  economy for foreign mobs); mana regenerates each tick from `MANA_REGEN`. Both are charged
  once, at the moment Iron's accepts a cast — a spell that is refused costs nothing.
- Spells are cast only when Magic NPCs has **verified** that a mob gets their designed
  behaviour. The check is a reviewed per-spell manifest derived from the Iron's jar this
  build was tested against; anything outside it is *unverified* and skipped unless you opt
  in with `spells.allowUnverifiedSpells`.

### Villager Recruits (optional)

When Recruits is installed, a thin adapter:
- scales a recruit's mana pool by its **rank** (`getXpLevel()`), updating as it levels;
- routes targeting through Recruits' own diplomacy-aware `shouldAttack()` so recruits
  cast **only at enemies, never at their owner or allies**;
- adds a **line-of-fire check** that skips a cast when an ally is between the recruit
  and its target (or inside an AoE's blast radius).

Ships curated combat loadouts for `recruit`, `bowman`, `crossbowman`, and `captain`.

Recruits **rank up into stronger casters**: a recruit's XP level raises its mana pool, and since
0.6.3 also the level its spells are cast at — the loadout's `level` is a floor, capped by
`balance.rankLevelMaxBonus` and the spell's own maximum.

`recruits.useIronsAI` was **removed in 0.6.3** (it had already been inert since 0.6.2), along with
`ironsAiSpeed` and `ironsAiIntervalTicks`, which never had any readers at all. It replaced the
built-in goal with Iron's `WizardAttackGoal` and handed it a bare list of spells, discarding every
per-entry setting in your loadout — level, weight, ranges, safety radius, cast chance, cooldown,
wind-up, reactive conditions — so one config toggle silently changed what a datapack *meant*. See
[ADR 0009](docs/decisions/0009-caster-movement-and-rank-scaling.md) for why it is not coming back,
and what replaced it.

## Dependencies

| Mod | Required? | Version (1.20.1) |
|-----|-----------|------------------|
| Forge | yes | **47.4.0+** (required by Iron's 3.15.x) |
| Iron's Spells 'n Spellbooks | for any casting | `1.20.1-3.15.x` – `1.20.1-3.16.x` |
| GeckoLib | (Iron's dep) | `4.8+` |
| Curios API | (Iron's dep) | `5.4.7+` |
| PlayerAnimator | (Iron's dep) | `1.0.2-rc1+` |
| Villager Recruits | optional | `1.15.0+` (enables the recruit adapter) |

Magic NPCs **does not bundle** Iron's or Recruits — install them yourself.

The Iron's range in `mods.toml` is `[1.20.1-3.15.0, 1.20.1-3.17.0)`: it ends where the
verification ends. The mob-cast manifest was derived from the 1.20.1-3.16.3 bytecode, and a
later Iron's may add spells or change the cast lifecycle — at which point this build would be
claiming verified support for code it has never seen. The startup log names the Iron's version
it found and the range it was checked against.

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
| `enabled` | `true` | `false` makes the loadout inert; with `"replace": true` it switches the whole entity type off (see [Turning casting off](#12-turning-a-mobs-casting-off)). A disabled loadout may omit `spells` |
| `native_attack` | `coexist` | how the casting goal coexists with the mob's own attack AI: `coexist` / `suppress` / `yield` (see [Mobs with native ranged AI](#13-mobs-with-their-own-ranged-attack-ai)) |
| `goal_priority` | *(global `castingGoalPriority`)* | `GoalSelector` priority for this loadout's casting goal |
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

There are two ways to set a spell's cooldown. **20 ticks = 1 second, and since 0.6.0 that is
literally true** — before 0.6.0 the cooldown counter only advanced on alternating game ticks, so every
configured value behaved as roughly double. If you tuned a pack against an older build, set
`balance.cooldownMultiplier = 2.0` to get the old rate back.

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

#### Five different "chance" knobs (don't mix them up)

| Knob | Controls |
|------|----------|
| `weight` (per spell) | **which** spell is picked among the currently-eligible ones |
| `cast_chance` (per spell) | **whether** the NPC actually casts after that spell is picked (a hesitation) |
| `caster_chance` (per loadout) | **whether this individual NPC is a caster at all**, rolled once and remembered |
| `equipment.chance` / `equipment.spawnWithGearChance` | **whether a mob gets casting gear** on spawn |
| `schools.*.casterChance` | **whether a generated school-caster exists** at all |

`caster_chance` is the one to reach for when you want *some* skeletons to be mages rather than all of
them. The roll happens once per NPC and is stored on it, so a `/reload` or a chunk reload can never
flip an individual into or out of being a caster:

```json
{ "entity_type": "minecraft:skeleton", "caster_chance": 0.15, "max_mana": 100, "mana_regen": 10,
  "spells": [ { "spell": "irons_spellbooks:magic_missile", "role": "attack" } ] }
```

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
- **`require_held_item`** (with optional `required_items` and `required_hand`) gates *one* spell on
  what the caster is holding, so a mob can have a staff-only nuke alongside spells it casts
  bare-handed. `required_items` takes item ids and `#namespace:tag` references; omit it to fall back to
  the `#magicnpcs:spell_focuses` tag. `required_hand` is `main`, `off`, or `either` (the default).
  This is separate from `equipment.requireSpellFocus`, which gates *all* casting.
- **`role: support`** spells self-cast when the caster drops below
  `balance.supportHealthThreshold` (default 50% HP), and ignore range. Since 0.6.0 this works **out of
  combat too**: a wounded NPC with no target heals itself on the slower
  `balance.supportOutOfCombatIntervalTicks` cadence (default 100 ticks). It never casts at full health,
  and `attack` spells are never selected without a target. Set `balance.supportOutOfCombat = false`
  for the pre-0.6.0 "only heals once something attacks it" behaviour.

### 4. Targeting a modded mob

`entity_type` accepts any registered entity id, and is checked against the registry — an unregistered
id is reported by name rather than producing a loadout nothing can ever match. JSON has no comments,
but `_comment`, `__comment` and `$comment` are explicitly allowed anywhere as in-file notes:

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

> Vanilla villagers have **no targeting AI at all** — nothing in vanilla, raids included, ever gives
> one a target — so a villager only casts offensively if a guard/NPC mod grants it combat AI, or you
> turn on `schools.villagers.selfDefense`. Without either it can still self-cast support spells when
> wounded. Its peaceful behaviour is otherwise preserved.

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
contexts. It is a **snapshot**, evaluated when the mob is reconciled — on spawn, on chunk load, on
`/reload`, on a config change, or when you run `/magicnpcs reconcile`. It is *not* re-evaluated
continuously: a caster that walks across a biome boundary keeps the loadout it was given until
something reconciles it again. Every field is optional.

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

### 10. Which loadout wins: source tier, `replace`, pools

When several loadouts target the **same** effective key (`entity_type` + optional `profession`), they
are resolved at load time in this order:

1. **Source tier** — a loadout from a **datapack** beats one shipped inside a **mod jar**, outright.
   Your `guardvillagers:guard` JSON replaces the bundled one with no flag to discover. (0.6.0; see
   [ADR 0003](docs/decisions/0003-loadout-source-tiering.md).)
2. **`replace`** — among the survivors, if any sets `"replace": true`, only replace-marked loadouts
   remain. This is the datapack-vs-datapack arbiter.
3. **Pool** — whatever is left pools.

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
`entity_type` field is the opt-in). Add `"replace": true` to win over **another datapack** targeting
that entity — you no longer need it to beat a loadout Magic NPCs itself ships. Vanilla skeletons cast
nothing by default — see the example below.

### 11. Turning a mob's casting off

Three ways, in increasing order of bluntness:

1. **One JSON file** — a disabled loadout with `"replace": true` suppresses every loadout for that
   key, including the mod's own. No jar edits, survives `/reload`, and `spells` may be omitted:
   ```json
   { "entity_type": "minecraft:skeleton", "enabled": false, "replace": true }
   ```
2. **One config line** — add the id to `general.disabledEntityTypes` in `magicnpcs-server.toml`.
3. **A shipped loadout only** — set its switch to `false` under `[builtinLoadouts]` in
   `magicnpcs-server.toml`. Exactly equivalent to option 1, without writing a datapack; it affects only
   the loadouts Magic NPCs itself ships (`recruit`, `bowman`, `crossbowman`, `captain`, `guard`).
4. **The whole feature** — `general.enableSpellcasting = false`. Since 0.6.2 this takes effect
   immediately on already-spawned casters, not only on newly loaded ones.

`/magicnpcs validate` and `/magicnpcs loadout id <entity_type>` both report a type that has been
switched off, so it never looks like a silent failure.

### 12. Mobs with their own ranged attack AI

Since 0.6.0 the casting goal declares **no** `GoalSelector` control flags, so it runs *alongside* a
mob's built-in attack AI instead of fighting it for the LOOK lock. A witch throws potions **and**
casts; a bow skeleton shoots **and** casts. (Before 0.6.0 a witch could never cast at all, and a
skeleton cast *instead of* shooting — one root cause, two opposite symptoms; see
[ADR 0002](docs/decisions/0002-casting-goal-injection.md).)

Set `"native_attack"` on a loadout to change that:

| Value | Effect |
|---|---|
| `"coexist"` *(default)* | Cast alongside the mob's own attack goals. |
| `"suppress"` | Hold the mob's native ranged/melee attack goals inert — a "pure caster" conversion. Reversible since 0.6.2: the original goals are wrapped rather than destroyed, and are handed back intact when the loadout changes, is removed, or the mod is disabled. **Since 0.6.3 this also switches on caster movement** (below). Every goal taken over is logged by class name. Some mobs re-add theirs (a skeleton does on weapon change). |
| `"yield"` | Only cast while none of the mob's own attack goals is running. |

Extend the set of goal classes those two policies recognise with
`general.suppressibleAttackGoals` (simple class names). `"goal_priority"` overrides
`general.castingGoalPriority` for one entity type.

#### Making a mob a real caster

A mob with a casting loadout but no ranged AI of its own has nothing telling it where to stand. A
Villager Recruit is the clearest case: Recruits gives *every* recruit a melee attack goal and only
Bowmen and Crossbowmen a ranged one, so a plain recruit with a spell loadout walks into sword range
and casts on the way in.

One line fixes it:

```json
{ "entity_type": "recruits:recruit", "native_attack": "suppress", "replace": true,
  "max_mana": 120, "mana_regen": 9,
  "spells": [ { "spell": "irons_spellbooks:magic_missile", "role": "attack",
                "min_range": 8.0, "max_range": 20.0 } ] }
```

`"native_attack": "suppress"` holds the mob's own attack goals inert (reversibly), and that in turn
switches on **caster movement**: the mob backs off when a target comes inside `min_range`, closes
when it drifts past `max_range`, and otherwise holds the band where its spells are eligible. The
range comes from the loadout — there is no second "preferred range" setting to keep in step.

It stays out of the way of the mob's own mod. A Recruit told to **hold a position** gets a short
leash around that post; one **following its owner** a longer one; one in a **formation** or marching
to a move-to order is pinned and will cast from where it stands. A caster also holds still while a
channelled spell is in flight, because it re-aims every tick and walking would throw that aim away.

Turn the whole behaviour off with `balance.casterMovement = false`. A loadout left on the default
`"coexist"` is unaffected either way: the mob keeps its own attack AI and does not reposition, which
is exactly how every shipped loadout still behaves.

If a mob still never casts, run **`/magicnpcs why <target>`** — it prints every goal as
`priority | class | flags | running` and names the one blocking the casting goal. A mob that does not
use the vanilla goal system at all (some animation- or Brain-driven modded mobs) cannot be reached by
goal injection; an empty or unrelated goal list in that dump is the tell.

### 13. Diagnosing a mob that isn't casting

Every line below is a **complete command**. `/magicnpcs loadout` and `/magicnpcs school` on their own
are headings — they need a subcommand — and typing one now prints its executable forms rather than a
syntax error. `<angle brackets>` are placeholders; do not type the brackets.

| Command | Answers |
|---|---|
| `/magicnpcs` | the command index, plus what Magic NPCs detected and how many loadouts loaded |
| `/magicnpcs why <targets>` | **"why is this specific mob, right now, not casting?"** — injection, reconciliation state, the goal environment, state gates, target/line of sight, mana, and a per-spell table with the first blocker for each entry (including *which* entity blocks a friendly-fire check) |
| `/magicnpcs loadout entity <targets>` | which loadout a mob **would** resolve to, which one its goal is **actually running**, and `STALE` when those differ |
| `/magicnpcs loadout id <entity_type>` | every loadout declared for a type, plus compat/disable warnings and any file that declares it but never became active |
| `/magicnpcs validate` | every **discovered** loadout file and its status — active, shadowed, suppressed, or rejected — with the JSON pointer of each problem |
| `/magicnpcs validate resource <resource_id>` | one loadout file in full (`my_magic:skeleton` for `data/my_magic/spellcasters/skeleton.json`) |
| `/magicnpcs validate id <entity_type>` | every loadout file targeting one entity type, whatever its status |
| `/magicnpcs config` | effective settings, the real config file paths, dependency versions, and reconciliation state |
| `/magicnpcs reconcile [targets]` | re-evaluate managed casting state against the current data, now |
| `/magicnpcs school pool [school]` | what a magic school's generated pool contains, and the exact filter that dropped each spell |
| `/magicnpcs school info <targets>` | each NPC's assigned school, its mode (`AUTO` / `MANUAL_SCHOOL` / `MANUAL_DISABLED`) **and which source is actually driving it** |
| `/magicnpcs spells [filter]` | the valid Iron's spell ids, and whether each is supported, unsupported, or unverified for mob casting |

Copy-paste examples for the nearest skeleton:

```mcfunction
/magicnpcs loadout id minecraft:skeleton
/magicnpcs loadout entity @e[type=minecraft:skeleton,sort=nearest,limit=1]
/magicnpcs why @e[type=minecraft:skeleton,sort=nearest,limit=1]
/magicnpcs validate
```

All are op-only. Everything except `/magicnpcs reconcile` is read-only — it writes no entity data and
draws nothing from a mob's RNG, so running a diagnostic cannot change the world it is describing. The
server log also prints a per-school pool summary, every rejected loadout file with its errors, and any
"mod installed but its compat toggle is off" warning on every reload.

**What `/magicnpcs validate` does and does not prove.** It checks loadout *files*: that they were
discovered, parsed, resolved, and that their spell ids exist and are castable by a mob. It cannot see a
file placed outside `data/<namespace>/spellcasters/` — such a file is never handed to Magic NPCs at
all — and it says nothing about whether a particular mob is casting. That second question is
`/magicnpcs why`. Through 0.6.1 validation read only the successfully parsed loadouts, so a rejected
file was invisible to it and "no issues found" could coexist with a broken pack; it now reports every
discovered file, including the rejected ones.

### 14. See also

- **Shipped loadouts** (great references) — bundled in the jar under
  `data/magicnpcs/spellcasters/`: `recruit`, `bowman`, `crossbowman`, `captain`, `guard`. These all
  target **optional NPC mods** (Recruits / Guard Villagers), so they stay inert unless that mod is
  installed. There is **no** active `minecraft:skeleton` loadout — an example lives at
  [`docs/loadouts/examples/skeleton.json`](docs/loadouts/examples/skeleton.json) to copy into your
  own datapack.
- [`docs/loadouts/README.md`](docs/loadouts/README.md) — copy-paste examples for optional NPC mods,
  plus a ready [`witch.json`](docs/loadouts/examples/witch.json) and
  [`skeleton.json`](docs/loadouts/examples/skeleton.json).
- [`docs/schools.md`](docs/schools.md) — the per-NPC magic-school system.
- [`docs/irons_spell_ids.md`](docs/irons_spell_ids.md) — valid Iron's spell ids by school.
- [`docs/mob-friendly-spells.md`](docs/mob-friendly-spells.md) — which spells work well on mobs,
  including the target-locked (`root`, `devour`, `wisp`) and forward-AoE (`stomp`) spells.
- [`docs/decisions/`](docs/decisions/) — the architectural decision records, including why the
  casting goal declares no control flags and why a datapack outranks jar data.

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
  MCA, More Villagers, VillagersPlus), iron golems, or tamed pets in their
  line of fire / blast radius. Toggle `targeting.protectBystanders`. Players are **not**
  bystanders by default since 0.6.0 — treating them as such meant a hostile caster fighting one
  player silently refused to fire while any other player stood near the line. Re-enable with
  `targeting.protectBystanderPlayers`; the caster's own target is never protected either way.

**Easy NPC** is a first-class integration since 0.7.0, alongside Villager Recruits. Magic NPCs
compiles against Easy NPC: Core and uses its own data, so an Easy NPC caster respects its **owner**
and its **faction** (it never casts an attack spell at either), scales its mana with its **experience
level**, and stays put when it is marked immovable or has been given a home position. Casting can be
triggered from an Easy NPC **dialog or action** (`magicnpcs:cast <spell> [level] [self|target]`), and
dialog options can be gated on `magicnpcs:has_school`, `magicnpcs:can_cast` and `magicnpcs:has_mana`.
Turn it on with `easynpc.enabled` and `compat.easynpc`, both default **off**; see
[`docs/loadouts/`](docs/loadouts/README.md) for the entity ids and examples. Owner and faction
protection stay on even with the integration disabled — switching it off stops Easy NPCs casting, it
never removes the rules about who they may not cast at.

Other NPC mods (**Guard Villagers, MCA Reborn, MineColonies, Human
Companions, More/Plus Villagers**) are supported via **config-gated datapack
loadouts** — no hard dependency. Each has a toggle under `[compat]` in
`config/magicnpcs-common.toml` (default **off**); enable it, then drop in a loadout for that mod's
entity types. A ready Guard Villagers loadout ships (inert until `compat.guardvillagers = true`), and
copy-paste examples for the rest live in [`docs/loadouts/`](docs/loadouts/README.md), which also
covers the known limitations (profession-scoped casting and trades are future work).

**Your datapack always wins over a shipped one.** If you write your own loadout for, say,
`guardvillagers:guard`, it replaces the bundled one entirely — no `"replace": true` needed. Confirm
with `/magicnpcs loadout entity <target>`.

**Mobs with their own attack AI** (the vanilla witch, and modded ranged mobs) cast alongside that AI
as of 0.6.0. Mobs that don't use the vanilla goal system at all cannot be reached by goal injection;
`/magicnpcs why <target>` makes that visible rather than leaving it a silent failure.

## Magic schools (recruits & villagers)

Each individual recruit/villager can be assigned a specific Iron's **school** (fire,
ice, lightning, holy, ender, blood, evocation, nature, eldritch); its spell pool is
built dynamically from that school. Assignment is automatic on spawn (persisted), and
also adjustable per-NPC via the `/magicnpcs school` command or the **School Tome** item
(right-click to **inspect**, sneak-right-click to **cycle**; cycling past the last usable school clears it). Villagers only cast offensively when something
gives them a target: a guard/NPC mod, or the opt-in `schools.villagers.selfDefense`. Raids do **not**
— vanilla never targets for a villager. Vanilla passivity is otherwise preserved, and a wounded one
will use a support spell out of combat.

### Manual assignment wins

A school set **by hand** — the Tome or `/magicnpcs school set` — is a per-NPC override. It outranks any
explicit loadout, including a datapack one, and it survives chunk reloads; clearing it likewise sticks.
This is what makes the Tome work on Villager Recruits at all: `recruit`, `bowman`, `crossbowman` and
`captain` ship with built-in loadouts, and an *automatically* assigned school never applies to a mob
that has an explicit loadout. Automatic assignment precedence is unchanged — explicit loadout first,
auto school only when none matches.

### Getting the Tome

Craft it: a book ringed by **amethyst shards and lapis**, or — when Iron's Spellbooks is installed —
by **arcane essence and blank runes**. Exactly one of the two recipes loads, so it is obtainable
either way. It is also in the Tools & Utilities creative tab.

Run **`/magicnpcs school pool [school]`** to see what a school's generated pool actually contains and
which filter dropped each spell; the same summary is logged once per reload, so a school that can
never be assigned is visible without a command. Full details, including the `[schools]` config block,
are in [`docs/schools.md`](docs/schools.md).

## Configuration

Magic NPCs has **two** config files (0.6.0; see
[ADR 0004](docs/decisions/0004-config-split.md)):

| File | Scope | Holds |
|---|---|---|
| `saves/<world>/serverconfig/magicnpcs-server.toml` | **per world**, auto-synced to clients | every gameplay tunable |
| `config/magicnpcs-common.toml` | **every world** | `[compat]` namespace toggles and `general.debugLogging` — installation facts, not balance |

Per-world gameplay settings:

- **general** — `enableSpellcasting`, `castingGoalPriority`, `castingGoalUsesLookFlag`,
  `disabledEntityTypes`, `suppressibleAttackGoals`
- **balance** — `manaMultiplier`, `cooldownMultiplier`, `regenMultiplier`,
  `decisionIntervalTicks`, `castChance`, `minCooldownTicks`, `supportHealthThreshold`,
  `supportOutOfCombat`, `supportOutOfCombatIntervalTicks`,
  `friendlyFireCheck`, `peacefulDisablesCasting`, `difficultyScaling`,
  `casterMovement`, `casterMovementSpeed`, `rankLevelPerRank`, `rankLevelMaxBonus`
- **targeting** — `requireLineOfSight`, `castWindupTicks`, `protectBystanders`,
  `protectBystanderPlayers`, `protectOwners`, `protectRaidAllies`, `sittingPetsMayCast`
- **equipment** — `requireSpellFocus`, `spawnWithGearChance` (both use the
  `magicnpcs:spell_focuses` item tag, which ships pre-filled with Iron's focuses
  (`#irons_spellbooks:school_focus`); add your own staves/spellbooks via a datapack)
- **reactive** — `enabled`, `matchedConditionWeightBonus` (per-spell `condition` blocks;
  see [Reactive conditions](#9-reactive-conditions))
- **feedback** — `telegraphs`, `schoolParticles`, `telegraphGlow`, `telegraphVolume`,
  `minDangerTier` (the cast "tell" shown during a caster's wind-up)
- **spells** — `spellBlacklist`, `spellWhitelist`, `allowUnverifiedSpells`
- **recruits** — `enabled`, `manaPerLevel`
- **schools** — magic-school assignment (`enableSchools`, `allowedSchools`, `maxRarity`,
  `maxSpellLevel`, `spellsPerSchool`, `allowedCastTypes`, weighting, base mana,
  `schoolAwareFocus`…) with `schools.recruits.*`, `schools.villagers.*`, and command/item
  toggles — see [`docs/schools.md`](docs/schools.md)

In `config/magicnpcs-common.toml`:

- **general** — `debugLogging`
- **compat** — per-mod loadout toggles (`guardvillagers`, `mca`, `minecolonies`,
  `easynpc`, `humancompanions`, `morevillagers`, `villagersplus`), all default **off**.
  Magic NPCs logs a warning when one of those mods is installed while its toggle is off.

> **Upgrading from 0.5.0 or earlier:** `[compat]` and `general.debugLogging` used to live in the
> per-world server file. Both locations are read for this release (a toggle is on if *either* file
> enables it), so nothing resets — but move them to the common file; the server-side copies are
> removed in 0.8.0. A one-time warning in the log names any key still in the old place.

### Modpack authors: shipping config defaults

- `config/magicnpcs-common.toml` — just ship the file. It is global, so there is nothing else to do.
- `magicnpcs-server.toml` is **per world**, which is why a plain `config/` copy appears to be
  ignored. Two supported paths:
  - **New worlds:** put your file at `defaultconfigs/magicnpcs-server.toml` in the pack root. Forge
    copies it into every newly created world. This is the correct, supported mechanism.
  - **An existing world:** the file has to go at
    `saves/<world>/serverconfig/magicnpcs-server.toml` (single-player) or
    `<server>/world/serverconfig/magicnpcs-server.toml` (dedicated). Editing `defaultconfigs/`
    afterwards does not retroactively change a world that already exists.

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
