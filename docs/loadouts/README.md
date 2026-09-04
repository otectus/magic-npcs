# Example spellcaster loadouts for optional NPC mods

These are **copy-paste examples**, not shipped/active data. Magic NPCs cannot
compile against these mods in its dev environment, so their entity-type ids below
are documented from public sources and **must be verified for your exact version**
(`/summon <id>` in-game, or check the mod's entity registry).

**On spells:** Spells follow a layered support model — a spell's mob-cast capability is determined by
(1) config overrides, (2) datapack manifests, (3) the built-in Iron's table, (4) namespace trust, or
(5) nothing (unverified, skipped by default). Add-on spells start unverified and can be enabled through
a manifest file (`data/<namespace>/spell_manifests/*.json`), namespace trust config, or the global
opt-in. See [`docs/compat/irons-addons.md`](../compat/irons-addons.md) for the full guide, including
offline auditing and in-game testing tools.

## How to use one

1. Confirm the entity id (e.g. with `/data get entity @e[limit=1]` after spawning one,
   or the mod's docs).
2. Drop the JSON into a datapack at `data/<your_pack>/spellcasters/<name>.json`
   (any namespace works — `entity_type` is what matters).
3. Enable the matching compat toggle in `config/magicnpcs-common.toml` → `[compat]`
   (e.g. `guardvillagers = true`). Loadouts for these namespaces are **inert until
   their toggle is enabled** — a deliberate, modpack-safe default. (Before 0.6.0 these lived in the
   per-world `magicnpcs-server.toml`; that copy is still read for one release.) Magic NPCs logs a
   warning at load time if the owning mod is installed while its toggle is off.
4. `/reload`.

## Vanilla mobs (e.g. skeletons, witches) and OpenLoader

Magic NPCs ships **no** active loadout for vanilla mobs, so vanilla skeletons cast nothing until a
datapack opts them in. Copy [`examples/skeleton.json`](examples/skeleton.json) or
[`examples/witch.json`](examples/witch.json) into a datapack at
`data/<your_pack>/spellcasters/<name>.json`, or for an **OpenLoader** pack at
`config/openloader/data/<pack>/data/<pack>/spellcasters/<name>.json`. Vanilla mobs need no compat
toggle.

**Your file beats the mod's (0.6.0).** A loadout loaded from a datapack automatically outranks one
shipped inside a mod jar for the same `entity_type` (+ optional `profession`) — you do **not** need
`"replace": true` to beat the bundled `guardvillagers:guard` loadout any more. `replace` still
arbitrates between *two datapacks*: if another pack also defines your entity type, the loadouts
**pool** by default (each mob sticky-picks one), and a root-level `"replace": true` makes yours win.
`/magicnpcs validate` lists **every discovered loadout file** with its status, so a file that failed to load is visible instead of vanishing into the log.
`/magicnpcs loadout entity <targets>` shows what a given mob resolves to, which pack it came from, and
whether that is actually what its goal is running. `/magicnpcs why <targets>` explains why a mob is or
isn't casting right now.

### Loadout statuses and absent-mod handling

`/magicnpcs validate` reports every discovered loadout with one of five statuses:

| Status | Meaning |
|--------|---------|
| **ACTIVE** | Parsed and valid; this is the loadout the mob will use (or one of the pooled options). |
| **SHADOWED** | Parsed and valid, but overridden by a higher-tier or `replace: true` loadout at the same key (datapack over jar, or explicit replacement). The shadowed file's state is preserved for diagnostics but not used at runtime. |
| **SUPPRESSED** | Well-formed with `"enabled": false`; the file is intentionally inert. Useful for turning off bundled loadouts without deleting the file. |
| **REJECTED** | Could not be parsed or failed validation (JSON error, unknown spell id, broken range, etc.). An error — something the author needs to fix. The file is never used. |
| **SKIPPED (mod absent)** | Well-formed, but names a mod that is not installed, so it cannot apply here. Not an error — it is INFO-level, and the file automatically activates if the mod is later installed. Distinct from REJECTED to signal "not an author mistake". |

**Handling of missing dependencies within a file:**

- **Entity type or profession namespace absent:** The whole file becomes SKIPPED with INFO "entity type / profession `<id>` belongs to mod `<ns>`, which is not installed — loadout skipped: install the mod or delete the file". No error; runtime-invisible.
- **Spell entry from absent mod:** That spell is dropped silently with INFO "entry dropped: mod `<ns>` is not installed"; the file continues to load with the remaining spells. If every spell entry was dropped that way, the file becomes SKIPPED instead of REJECTED (since nothing is wrong with the file, only the spells' mods are absent).
- **Item reference from absent mod:** The item is dropped silently with INFO "item `<id>` belongs to mod `<ns>`, which is not installed — the item is dropped from this list; the rest of the loadout still loads". The file continues.
- **Exception:** If every item in a `required_items` field comes from absent mods (leaving it empty), the file is still REJECTED with REQUIRED_ITEMS_EMPTIED error — a list that quietly empties itself would silently widen "only while holding a staff" to "always".

**After editing a file, run `/reload`.** Every loaded mob is then re-evaluated, including mobs that
were not casters before — so a skeleton that was standing there when you added the pack becomes a
caster without needing to be respawned. (Through 0.6.1 the reload only rebuilt mobs that were
*already* casters, which is why adding a datapack appeared to do nothing.) `/magicnpcs reconcile` does
the same thing on demand.

## Turning a mob's casting off

Four ways, in increasing order of bluntness:

1. **One JSON file**, no jar edits, survives `/reload`. A disabled loadout with `"replace": true`
   suppresses every loadout for that key — including the mod's own:
   ```json
   { "entity_type": "minecraft:skeleton", "enabled": false, "replace": true }
   ```
   (A disabled loadout may omit `spells` entirely.)

   A **bare stub** also works when your file sits at the same data path as the loadout you are
   switching off — the entity type is inherited from the file being shadowed:
   ```json
   { "enabled": false }
   ```
   `/magicnpcs validate` records the inference, so you can confirm it caught the right key. If the
   stub has nothing to shadow it is rejected with that explanation, rather than silently doing
   nothing.
2. **A shipped loadout only** — set its switch to `false` under `[builtinLoadouts]` in
   `magicnpcs-server.toml`. Exactly equivalent to option 1, without writing a datapack.
3. **One config line** — add the id to `general.disabledEntityTypes` in `magicnpcs-server.toml`.
4. **The whole feature** — `general.enableSpellcasting = false`. This takes effect immediately on
   already-spawned casters.

## Mobs with their own ranged attack AI

Since 0.6.0 the casting goal declares no control flags, so it runs *alongside* a mob's built-in attack
AI instead of fighting it for the LOOK lock. A witch throws potions **and** casts; a bow skeleton
shoots **and** casts. If you want different behaviour, set `"native_attack"` on the loadout:

| Value | Effect |
|---|---|
| `"coexist"` (default) | Cast alongside the mob's own attack goals. |
| `"suppress"` | Hold the mob's native ranged/melee attack goals inert — a "pure caster" conversion. **Reversible**: the original goals are wrapped rather than destroyed, and are handed back intact when the loadout changes, is removed, or the mod is disabled. Every goal taken over is logged by class name. Note that some mobs (e.g. skeletons on weapon change) re-add theirs. |
| `"yield"` | Only cast while none of the mob's own attack goals is running. |

**`"suppress"` also switches on caster movement (0.6.3).** A mob whose own attack AI is held inert
has nothing left telling it where to stand, so Magic NPCs keeps it in the band between the widest
`min_range` and the narrowest `max_range` of its own ATTACK spells: it backs off when a target closes
inside that band and advances when one drifts out of it. Where the mob's mod has a command system,
that is respected — a Villager Recruit holding a position gets a short leash around it, one following
its owner a longer one, and one in a formation or marching to a position does not move at all. A
loadout on the default `"coexist"` keeps its own attack AI and does not reposition.

`"goal_priority"` overrides `general.castingGoalPriority` for one entity type. If a mob still never
casts, run `/magicnpcs why <target>`: it dumps every goal as `priority | class | flags | running` and
names the one that is blocking, which is the answer for mods this document can't anticipate. Mobs that
do not use the vanilla goal system at all (some animation- or Brain-driven modded mobs) cannot be
reached by goal injection — `why` will show an empty or unrelated goal list, which is the tell.

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

### Vanilla witch — `minecraft:witch`
See [`examples/witch.json`](examples/witch.json). Needs no compat toggle. The witch keeps its own
potion-throwing AI and casts alongside it.

### Guard Villagers — `guardvillagers:guard`
The jar ships a toggle-gated default at `data/magicnpcs/spellcasters/guard.json` (magic missile,
guiding bolt, heal). Since 0.6.0 **your own guard JSON simply replaces it** — a datapack outranks jar
data, so you no longer get a mix of your spells and the mod's across different guards. Confirm with
`/magicnpcs loadout entity <guard>`, which prints the winning source and its pack.

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

### Easy NPC — `easy_npc:humanoid`
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
Needs `compat.easynpc = true` **and** `easynpc.enabled = true` — the first admits loadouts on the
`easy_npc:` namespace, the second switches on the adapter that makes Easy NPCs cast. Both default off.

Easy NPC registers one entity type per NPC variant, all in the `easy_npc` namespace. The ones a
loadout is most likely to name, read from Easy NPC Core 7.11.0:

`humanoid`, `humanoid_slim`, `villager`, `wandering_trader`, `orc`, `orc_warrior`, `fairy`, `doppler`,
`illusioner`, `evoker`, `vindicator`, `pillager`, `witch`, `skeleton`, `skeleton_bogged`, `stray`,
`wither_skeleton`, `zombie`, `zombie_villager`, `husk`, `drowned`, `piglin`, `piglin_brute`,
`zombified_piglin`, `iron_golem`, `vex`, `allay`, `cat`, `wolf`, `fox`, `chicken`, `pig`, `horse`,
`skeleton_horse`, `zombie_horse`, `spider`, `cave_spider`, `creeper`, `enderman`, `ghast`, `slime`.

(There are also `epic_fight_*_raw` and `cobblemon_npc` variants that exist only when those mods are
installed.) A loadout names one variant, so a pack that uses several needs one file per variant —
`/magicnpcs loadout id <entity_type>` confirms an id resolves before you write the rest.

Owner and faction protection are automatic: an Easy NPC never casts an attack spell at its owner, at a
sibling NPC with the same owner, or at anything sharing its faction. That protection stays on even
when `easynpc.enabled` is off.

**Casting from a dialog or a trigger.** Add an Easy NPC action of type `CUSTOM` with the command:

```
magicnpcs:cast <spell_id> [level] [self|target]
```

for example `magicnpcs:cast irons_spellbooks:heal 2 self`. The namespace may be omitted
(`magicnpcs:cast heal` means `irons_spellbooks:heal`). Without `self`, the cast is aimed at the NPC's
current combat target, falling back to the player who triggered the action; an ally is never chosen.
The same spell allow-list, mob-castability and mana rules apply as to an ordinary cast — a refused
scripted cast is silent in game and explained in the log.

**Gating a dialog on the NPC's magic.** Three conditions are available:
`magicnpcs:has_school` (optionally with a school id as its custom data), `magicnpcs:can_cast`, and
`magicnpcs:has_mana` (its value is the mana required). All three are answered server-side, so Easy NPC
locks the option rather than showing it and failing.

**Casting as an objective.** Magic NPCs registers the custom objective `magicnpcs:cast_spell`. It makes
an NPC use whatever loadout or school it resolves to, at the objective's priority. Easy NPC has no UI
for custom objectives, so it has to be applied through a preset or a command — a loadout or a School
Tome assignment is the easier route and does the same thing.

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
Villagers only cast offensively when something gives them a target — a guard mod's combat AI, or the
opt-in `schools.villagers.selfDefense`. A raid will **not** do it: vanilla never targets for a
villager. So a cleric battlemage stays peaceful unless you arrange one of those. For modded
professions, use that mod's profession id (e.g. `morevillagers:fisherman`).

### Spell role: `attack` vs `support` (optional)

Each spell entry may declare a `role` field: `"attack"` (offensive, targets enemies) or `"support"` (self-cast heal/buff, used out of combat and in between fights). If omitted, the spell defaults to `attack`. SUPPORT spells are only cast when the mob is not in active combat, or when hurt and a matching condition permits it. Most self-heal and buff spells should use `"role": "support"`. Note that `haste` raycasts for a healable ally and, when the raycast finds no healable entity, it casts on the caster itself, making it safe for SUPPORT use despite its targeting behavior.

### Per-spell pacing & aim (optional)

Any spell entry accepts optional tuning fields; omit them to inherit the global config
defaults under `[balance]` / `[targeting]`:

- **`cast_chance`** `[0..1]` — chance to actually cast on each decision (a "hesitation").
- **`cooldown`** — explicit cooldown in **ticks** (20 = 1 s). Overrides `cooldown_multiplier`;
  floored by `minCooldownTicks`. e.g. `"cooldown": 100` = 5 s.
- **`cooldown_multiplier`** — scales the spell's Iron's default cooldown. **Above 1.0 = slower
  (longer), below 1.0 = faster (shorter)** — so a *bigger* multiplier means a *longer* cooldown.
- **`cast_time`** — absolute native cast duration, in **ticks**, for Iron's own LONG/CONTINUOUS cast.
  This is the clearest option when you want exact control over how long a spell charges. Highest
  precedence when set; overrides `cast_time_multiplier`.
- **`cast_time_multiplier`** — scales Iron's effective native cast duration. `0.5` = roughly twice
  as fast, `2.0` = twice as long. Ignored if `cast_time` is set.
- **`windup`** — ticks the caster spends facing/tracking the target before an attack spell
  fires (re-checking line of sight/range; it only fires if the target is still valid). The caster's
  facing is snapped onto the target right before release, so even `0` (instant) fires on-aim.

**Native cast time vs windup, precisely:**

```
windup
  Magic NPCs-specific delay before Iron's starts casting.
  Set to 0 to remove the extra telegraph/aim delay.

cast_time
  Absolute duration, in ticks, of Iron's own LONG/CONTINUOUS cast
  for this NPC spell entry.

cast_time_multiplier
  Scales Iron's effective native cast duration.
  0.5 = roughly twice as fast.
  2.0 = twice as long.

For LONG spells, this usually changes time-to-release.
For CONTINUOUS spells, this also changes channel length.
Neither field changes the lifetime of an effect spawned after casting.
```

**Gravity Fissure example** (`irons_spellbooks:gravity_fissure`, a LONG spell with 15-tick native cast in Iron's 3.16.3):

With the multiplier form — cuts the cast time in half:
```json
{
  "spell": "irons_spellbooks:gravity_fissure",
  "role": "attack",
  "windup": 0,
  "cast_time_multiplier": 0.5
}
```
Resolves to 8 ticks. The black hole's lifetime comes from spell power, not cast duration.

Or the absolute form — exactly 6 ticks:
```json
{
  "spell": "irons_spellbooks:gravity_fissure",
  "role": "attack",
  "windup": 0,
  "cast_time": 6
}
```

**Current releases before 0.9.0 have no native cast-duration syntax.** `"windup": 0` only removes Magic NPCs' own pre-cast delay; it cannot shorten Iron's native cast time. The two phases are distinct: windup → Iron's cast/channel → effect.

**Ignored for INSTANT/NONE spells:** If either field is set on a spell whose cast type is INSTANT or NONE (like `magic_missile`), both fields are ignored at runtime. Run `/magicnpcs validate` to see the `CAST_TIME_IGNORED` warning.

**Precedence:** `cast_time` always wins over `cast_time_multiplier` when both are set. The log reports this as info-level `CAST_TIME_ABSOLUTE_WINS`.

**Parser strictness:** Malformed values are file-level errors that reject the whole loadout record:
- `cast_time` must be an integer ≥ 0, else `CAST_TIME_NEGATIVE` error.
- `cast_time_multiplier` must be a finite number ≥ 0, else `CAST_TIME_MULTIPLIER_INVALID` error.
- Typo aliases exist for convenience: `cast_duration` → `cast_time`, `cast_duration_multiplier` → `cast_time_multiplier`, `casttime` → `cast_time`, `casttime_multiplier` → `cast_time_multiplier`. These are suggestions only and are never accepted as keys.

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
