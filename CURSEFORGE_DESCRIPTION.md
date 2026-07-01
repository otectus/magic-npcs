# Magic NPCs

**Give your NPCs real spells.** Magic NPCs makes mobs cast spells from *Iron's
Spells 'n Spellbooks* — driven by datapacks and config, so any mob can become a
spellcaster, with first-class support for **Villager Recruits** and a per-NPC
**magic-school** system for recruits and villagers.

## What it does

- **Datapack-driven.** Drop a small JSON in `data/<pack>/spellcasters/` naming an
  entity type and a list of Iron's spells — that mob now casts them. No tags, no
  add-on mods. Shipped loadouts cover **optional NPC mods** (Recruits, Guard
  Villagers) and stay inert unless that mod is installed — vanilla mobs cast nothing
  until you opt them in (copy the skeleton example below). When two datapacks target
  one entity they **pool** by default; add `"replace": true` to override cleanly.
  `/magicnpcs loadout` and `/magicnpcs validate` show exactly what each mob resolves to.
- **Magic schools (recruits & villagers).** Assign each individual recruit or villager
  one of the nine Iron's schools — fire, ice, lightning, holy, ender, blood, evocation,
  nature, eldritch. Its spell pool is built **dynamically** from that school, so it
  even uses spells added by Iron's add-ons. Schools are assigned automatically on spawn
  (recruits by chance/rank, villagers by profession), and you can set any individual's
  school with the **`/magicnpcs school`** command or the **School Tome** item
  (right-click to cycle, sneak to clear).
- **Real Iron's casting.** Spells fire through Iron's own cast path, so you get the
  genuine projectiles, particles, and sounds. Mobs have a real mana pool that
  regenerates and gates how often they cast.
- **Smart and safe.** Casters check line of sight, range, mana, and cooldowns; they
  won't cast while sleeping, dead, or on Peaceful, and mana scales with difficulty.
  Friendly-fire protection keeps villagers, iron golems, players, pets, owners, and
  teammates out of the blast — and Recruits cast **only at enemies** using their own
  diplomacy, respect their command state, and scale mana with **rank**.
- **Works with your NPC mods.** Beyond Recruits, optional config-gated support covers
  **Guard Villagers, MCA Reborn, MineColonies, Easy NPC, Human Companions, More
  Villagers, and VillagersPlus** — each behind a toggle that defaults **off**, so
  nothing changes until you opt in. Owned/teamed NPCs (e.g. Human Companions) get
  friendly-fire safety automatically.
- **Deeply tunable.** A server config controls global on/off, mana/cooldown/regen
  balance, decision interval, difficulty scaling, line-of-sight and friendly-fire
  rules, a spell allow/deny list, equipment requirements, per-mod compat toggles, and
  the full magic-school system. Optionally, recruits can use Iron's *own* combat AI.

## Make any mob a spellcaster (datapacks)

Magic NPCs is datapack-driven: drop a small JSON naming an entity type and a list of
Iron's spells, and that mob casts them. A complete, minimal pack is two files under
`<world>/datapacks/my_magic/`:

**`pack.mcmeta`** — for 1.20.1, `pack_format` is **15**:

```json
{ "pack": { "pack_format": 15, "description": "My Magic NPCs loadouts" } }
```

**`data/my_magic/spellcasters/skeleton.json`**:

```json
{
  "entity_type": "minecraft:skeleton",
  "max_mana": 100,
  "mana_regen": 10,
  "spells": [
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "weight": 3, "max_range": 16.0, "role": "attack" },
    { "spell": "irons_spellbooks:heal", "level": 1, "role": "support" }
  ]
}
```

Run `/reload` and skeletons now sling Magic Missiles (and heal when hurt). Each spell
also accepts `min_range`/`max_range`, `safety_radius` (friendly-fire clearance — larger for
AoE spells), and `role` (`attack` or `support`). Add `"profession": "minecraft:cleric"` to
scope a villager loadout to a single profession. The full guide — annotated multi-spell
examples, modded mobs, the spell-focus tag, and explicit-loadouts vs. magic-schools — is in
the README and `docs/loadouts/`.

## Requirements

- **Forge 47.4.0+** for Minecraft **1.20.1**
- **Iron's Spells 'n Spellbooks** (`1.20.1-3.15.x`) + its dependencies **GeckoLib**,
  **Curios API**, **PlayerAnimator** — required for any spellcasting
- **Villager Recruits** (1.15.0+) — *optional*, enables the recruit features

Magic NPCs does not include these mods; install them separately. Every NPC-mod
integration is optional and loads safely whether or not that mod is present.

## Notes

- Server-side config; settings sync to clients automatically.
- Villagers given a school only actually cast when they have a target (raids, guard
  mods) — their normal, peaceful behavior is preserved.
- Open source under GPL-3.0. Iron's Spells and Villager Recruits are compile-only
  dependencies — neither is bundled or redistributed.
