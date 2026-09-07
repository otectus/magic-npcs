# 09 — Arcane Progression

A small progression questline built entirely from vanilla/Forge datapack surfaces, wired to Magic
NPCs' school system rather than its loadout schema. This is the only sample using functions,
advancements, a loot table, and recipe overrides.

## Files

| File | Type | Purpose |
|---|---|---|
| `data/minecraft/tags/functions/load.json` | Function tag | Adds `arcane_progression:load` to the vanilla `#minecraft:load` tag |
| `data/arcane_progression/functions/load.mcfunction` | Function | Runs on world load and every `/reload`: adds a scoreboard objective and a team |
| `data/arcane_progression/functions/give_starter_tome.mcfunction` | Function | Gives the caller a `magicnpcs:school_tome` |
| `data/arcane_progression/functions/anoint_holy.mcfunction` | Function | For every nearby vanilla villager, calls `anoint_one_holy` |
| `data/arcane_progression/functions/anoint_one_holy.mcfunction` | Function | Runs `/magicnpcs school set @s irons_spellbooks:holy` on one villager, then joins a team and sets a scoreboard value |
| `data/arcane_progression/functions/release_from_order.mcfunction` | Function | Undoes the assignment with `/magicnpcs school auto` and removes the team/scoreboard state |
| `data/arcane_progression/advancements/root.json` | Advancement | Root advancement, granted automatically via the `minecraft:tick` trigger |
| `data/arcane_progression/advancements/carry_the_tome.json` | Advancement | Grants on picking up a School Tome (`minecraft:inventory_changed`); its reward runs `anoint_holy` |
| `data/arcane_progression/loot_tables/chests/mage_cache.json` | Loot table | A chest table that can drop a School Tome |
| `data/arcane_progression/spellcasters/tutorial_skeleton.json` | Loadout | A skeleton to test the above against, `caster_chance: 0.5` |
| `data/magicnpcs/recipes/school_tome.json` | Recipe | Overrides the mod's shipped `magicnpcs:school_tome` recipe with an unconditional shaped recipe |
| `data/magicnpcs/recipes/school_tome_irons.json` | Recipe | Overrides the mod's shipped `magicnpcs:school_tome_irons` recipe; still `forge:conditional` on Iron's being installed |

## What this teaches

- **A function tag driving load-time setup.** `data/minecraft/tags/functions/load.json` adds
  `arcane_progression:load` to the vanilla `#minecraft:load` tag, so it runs automatically on
  world load and on every `/reload` — used here to (re-)create a scoreboard objective and a team,
  both idempotent no-ops on repeat.
- **mcfunctions driving `/magicnpcs school`.** `anoint_one_holy.mcfunction` shows
  `/magicnpcs school set @s <school>` as a manual override that outranks any datapack loadout and
  survives chunk reloads; `release_from_order.mcfunction` shows the difference between
  `/magicnpcs school auto` (hand the NPC back to automatic assignment) and
  `/magicnpcs school clear` for going fully silent.
- **Advancements with function rewards.** `root.json` is granted for everyone automatically via
  the vanilla `minecraft:tick` trigger (a common no-op-criteria pattern for a root advancement, not
  something Magic NPCs–specific); `carry_the_tome.json` is a child that grants on picking up
  `magicnpcs:school_tome` and runs `anoint_holy` as its reward.
- **A custom loot table.** `mage_cache.json` weights `magicnpcs:school_tome` against
  `minecraft:book` in one pool, with lapis/amethyst in a second — an ordinary loot table that just
  happens to reference the mod's one item.
- **Overriding both shipped School Tome recipes.** This pack ships files at the exact resource
  locations the mod uses for its two default recipes (`magicnpcs:school_tome`,
  `magicnpcs:school_tome_irons`), so a datapack replaces them outright. `school_tome_irons.json`
  keeps the `forge:conditional` wrapper (gated on `irons_spellbooks` being loaded and its items
  existing) since that recipe only makes sense with Iron's installed; `school_tome.json` here is
  an unconditional shaped recipe.
- **Why the functions avoid modded entity selectors.** `anoint_holy.mcfunction` only ever selects
  `type=minecraft:villager`. A selector naming a modded entity type (e.g.
  `type=recruits:recruit`) is a parse error when that mod isn't installed, and one bad selector
  stops the *entire function* from loading — so a function mixing vanilla and modded targets has
  to split the modded part into its own function and call it conditionally, rather than put both
  in one file.

## Prerequisites

Iron's Spells 'n Spellbooks for the `irons_spellbooks:holy` school and the recipe override; no
other mod is required. No `[compat]` toggle needed.

## Try it

```
/reload
/function arcane_progression:give_starter_tome
/function arcane_progression:anoint_holy
/magicnpcs school info @e[type=minecraft:villager,distance=..16]
/function arcane_progression:release_from_order
```
