# Example spellcaster loadouts for optional NPC mods

These are **copy-paste examples**, not shipped/active data. Magic NPCs cannot
compile against these mods in its dev environment, so their entity-type ids below
are documented from public sources and **must be verified for your exact version**
(`/summon <id>` in-game, or check the mod's entity registry).

## How to use one

1. Confirm the entity id (e.g. with `/data get entity @e[limit=1]` after spawning one,
   or the mod's docs).
2. Drop the JSON into a datapack at `data/<your_pack>/spellcasters/<name>.json`
   (any namespace works — `entity_type` is what matters).
3. Enable the matching compat toggle in `config/magicnpcs-server.toml` → `[compat]`
   (e.g. `guardvillagers = true`). Loadouts for these namespaces are **inert until
   their toggle is enabled** — a deliberate, modpack-safe default.
4. `/reload`.

## Vanilla mobs (e.g. skeletons) and OpenLoader

Magic NPCs ships **no** active loadout for vanilla mobs, so vanilla skeletons cast nothing until a
datapack opts them in. Copy [`examples/skeleton.json`](examples/skeleton.json) into a datapack at
`data/<your_pack>/spellcasters/skeleton.json`, or for an **OpenLoader** pack at
`config/openloader/data/<pack>/data/<pack>/spellcasters/skeleton.json`. Vanilla mobs need no compat
toggle.

If another datapack (or an older Magic NPCs build) also defines that entity type, the loadouts
**pool** by default (each mob picks one). Add a root-level `"replace": true` to make yours override
the others for that `entity_type` (+ optional `profession`) instead of stacking. Use
`/magicnpcs validate` to spot pooled/duplicate keys and `/magicnpcs loadout entity <target>` to see
what a given mob actually resolves to.

## Known limitations

- **Profession-scoped casting (vanilla, More Villagers / VillagersPlus):** loadouts key on
  *entity type*, and these mods add professions to the vanilla `minecraft:villager` type.
  Use the optional **`profession`** field (see the example at the bottom) to target a single
  profession instead of *every* villager — which is why no blanket villager loadout ships.
  (Magical trade/loot injection remains future work.)
- **MCA Reborn:** MCA villagers share entity types (`mca:male` / `mca:female`) across
  all roles, so a loadout makes *all* of them cast. Only enable `compat.mca` if you
  truly want that, and prefer pairing it with a tight `spellWhitelist`.
- **MineColonies:** citizens are driven by colony AI; a generic casting goal layered
  on top is conservative and unaware of work orders. Enable only for raiders or with
  care.

## Examples

### Guard Villagers — `guardvillagers:guard`
(Already shipped as an active, toggle-gated default in
`data/magicnpcs/spellcasters/guard.json`.)

### Human Companions — `humancompanions:human_companion`
```json
{
  "entity_type": "humancompanions:human_companion",
  "max_mana": 90,
  "mana_regen": 8,
  "spells": [
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "weight": 3, "max_range": 16.0, "role": "attack" },
    { "spell": "irons_spellbooks:heal", "level": 1, "weight": 1, "role": "support" }
  ]
}
```
Friendly-fire toward the owner/allies is handled automatically by the generic
owner/team adapter (`targeting.protectOwners`).

### MineColonies barbarian raider — `minecolonies:barbarian`
```json
{
  "entity_type": "minecolonies:barbarian",
  "max_mana": 80,
  "mana_regen": 7,
  "spells": [
    { "spell": "irons_spellbooks:firebolt", "level": 1, "weight": 2, "max_range": 18.0, "role": "attack" }
  ]
}
```

### Easy NPC — `easy_npc:humanoid` (verify; Easy NPC ids vary by variant)
```json
{
  "entity_type": "easy_npc:humanoid",
  "max_mana": 100,
  "mana_regen": 9,
  "spells": [
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "weight": 2, "max_range": 18.0, "role": "attack" },
    { "spell": "irons_spellbooks:heal", "level": 1, "weight": 1, "role": "support" }
  ]
}
```

### MCA Reborn — `mca:male` / `mca:female` (applies to ALL — see limitations)
```json
{
  "entity_type": "mca:male",
  "max_mana": 100,
  "mana_regen": 8,
  "spells": [
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "weight": 2, "max_range": 16.0, "role": "attack" },
    { "spell": "irons_spellbooks:heal", "level": 1, "weight": 1, "role": "support" }
  ]
}
```

### Profession-scoped villager — only clerics cast
Add the optional **`profession`** field (a villager-profession id) so the loadout applies to
just that profession; villagers of other professions are untouched. A profession-less
`minecraft:villager` loadout, if present, is the fallback for everyone else. No `[compat]`
toggle is needed — this is vanilla.
```json
{
  "entity_type": "minecraft:villager",
  "profession": "minecraft:cleric",
  "max_mana": 90,
  "mana_regen": 9,
  "spells": [
    { "spell": "irons_spellbooks:guiding_bolt", "level": 1, "weight": 2, "max_range": 20.0, "role": "attack" },
    { "spell": "irons_spellbooks:heal", "level": 1, "weight": 1, "role": "support" }
  ]
}
```
Villagers only cast when they have a target (raids, or a guard mod grants combat AI), so a
cleric battlemage stays peaceful until a raid hits. For modded professions, use that mod's
profession id (e.g. `morevillagers:fisherman`).

### Per-spell pacing & aim (optional)

Any spell entry accepts optional tuning fields; omit them to inherit the global config
defaults under `[balance]` / `[targeting]`:

- **`cast_chance`** `[0..1]` — chance to actually cast on each decision (a "hesitation").
- **`cooldown`** — explicit cooldown in **ticks** (20 = 1 s). Overrides `cooldown_multiplier`;
  floored by `minCooldownTicks`. e.g. `"cooldown": 100` = 5 s.
- **`cooldown_multiplier`** — scales the spell's Iron's default cooldown. **Above 1.0 = slower
  (longer), below 1.0 = faster (shorter)** — so a *bigger* multiplier means a *longer* cooldown.
- **`windup`** — ticks the caster spends facing/tracking the target before an attack spell
  fires (re-checking line of sight/range; it only fires if the target is still valid). The caster's
  facing is snapped onto the target right before release, so even `0` (instant) fires on-aim.

Spell ids: see [`../irons_spell_ids.md`](../irons_spell_ids.md) or run `/magicnpcs spells`.

```json
{
  "entity_type": "minecraft:wither_skeleton",
  "max_mana": 160,
  "mana_regen": 10,
  "spells": [
    { "spell": "irons_spellbooks:fireball", "level": 2, "weight": 2,
      "min_range": 6.0, "max_range": 24.0, "safety_radius": 4.0, "role": "attack",
      "cast_chance": 0.6, "cooldown": 120, "windup": 20 },
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "weight": 3,
      "min_range": 0.0, "max_range": 20.0, "role": "attack" }
  ]
}
```
Here the fireball is a telegraphed, occasional heavy hit (1 s wind-up, 6 s cooldown, 60%
chance); the magic missile omits the fields and inherits the global defaults.

### Weighted starting equipment (optional)

A loadout may also carry an `equipment` block to arm casters on spawn (handy with
`equipment.requireSpellFocus`). Each hand takes a weighted item list — a bare `"id"` (weight 1) or
`{ "item", "weight" }` — plus `chance` (default 1.0) and `only_if_empty` (default true). Omit the
block entirely to keep the global `spawnWithGearChance` behaviour.

```json
{
  "entity_type": "minecraft:skeleton",
  "max_mana": 100, "mana_regen": 10,
  "equipment": {
    "mainhand": [
      { "item": "irons_spellbooks:pyrium_staff",    "weight": 5 },
      { "item": "irons_spellbooks:graybeard_staff", "weight": 1 }
    ],
    "chance": 0.35,
    "only_if_empty": true
  },
  "spells": [
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "role": "attack", "cooldown": 100 }
  ]
}
```
