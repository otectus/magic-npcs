# Iron's Spellbooks — valid spell IDs

A reference of the spell registry IDs you can put in a loadout's `spell` field. Every ID below is
fully namespaced as `irons_spellbooks:<id>`.

> **This list is version-specific and generated from Iron's Spellbooks 1.20.1 **3.16.1**.** Spells are
> added/renamed between Iron's versions, so the *authoritative* list is always the one in your running
> game. Run **`/magicnpcs spells`** (or `/magicnpcs spells <text>` to filter) in-game for the live list,
> including each spell's school, rarity, **default cooldown**, and cast type. Mob-friendly spells are
> shown in green there.

## The namespace gotcha (read this first)

A `spell` value with **no namespace** is interpreted as `minecraft:…`, which is never a spell. Magic
NPCs will auto-retry a bare id under `irons_spellbooks:` (so `devour` resolves to
`irons_spellbooks:devour`), but you should still write the full id. If an id can't be resolved at all,
the log shows a clear warning naming the file, entity, field, and bad id.

Common mistakes (the exact ones reported by users):

| You wrote | Problem | Correct id |
| --- | --- | --- |
| `irons_spellbooks:blight` | none — this is valid | `irons_spellbooks:blight` |
| `devour` | missing namespace | `irons_spellbooks:devour` |
| `fangward` | missing namespace **and** wrong path (needs `_`) | `irons_spellbooks:fang_ward` |

## IDs by school

Spells marked **▶** are reliable target-aimed projectiles — the best fit for a generic mob caster.
See [mob-friendly-spells.md](mob-friendly-spells.md) for the full suitability guide.

### fire
`blaze_storm`, `burning_dash`, ▶`fire_arrow`, ▶`fireball`, ▶`firebolt`, `fire_breath`, `flaming_barrage`,
`flaming_strike`, `heat_surge`, ▶`magma_bomb`, `raise_hell`, `scorch`, `wall_of_fire`

### ice
`blizzard`, `cone_of_cold`, `frostbite`, `frost_step`, `frostwave`, `ice_block`, ▶`ice_spikes`,
`ice_tomb`, ▶`icicle`, ▶`ray_of_frost`, ▶`snowball`, `summon_polar_bear`

### lightning
`ascension`, ▶`ball_lightning`, `chain_lightning`, `charge`, `electrocute`, `lightning_bolt`,
▶`lightning_lance`, `shockwave`, `thunder_step`, `thunderstorm`, `volt_strike`

### holy
`blessing_of_life`, `cleanse`, `cloud_of_regeneration`, `divine_smite`, `fortify`, `greater_heal`,
▶`guiding_bolt`, `haste`, `heal`, `healing_circle`, `sunbeam`, `wisp`

### ender
`arcane_shackle`, `black_hole`, `counterspell`, `dragon_breath`, `echoing_strikes`, `evasion`,
`gravity_fissure`, ▶`magic_arrow`, ▶`magic_missile`, `portal`, `recall`, `shadow_slash`, `starfall`,
`summon_ender_chest`, `summon_swords`, `teleport`

### blood
`acupuncture`, ▶`blood_needles`, `blood_slash`, `blood_step`, `devour`, `heartstop`, `raise_dead`,
`ray_of_siphoning`, `sacrifice`, ▶`wither_skull`

### evocation
`arrow_volley`, `chain_creeper`, `fang_strike`, `fang_swirl`, `fang_ward`, `firecracker`, `gust`,
`invisibility`, `lob_creeper`, `shield`, `slow`, `spectral_hammer`, `summon_horse`, `summon_vex`,
`throw`, `wololo`

### nature
▶`acid_orb`, `blight`, `earthquake`, `firefly_swarm`, `gluttony`, `oakskin`, ▶`poison_arrow`,
`poison_breath`, `poison_splash`, `root`, `spider_aspect`, `stomp`, `touch_dig`

### eldritch
`abyssal_shroud`, ▶`eldritch_blast`, `planar_sight`, `pocket_dimension`, `sculk_tentacles`,
`sonic_boom`, `telekinesis`

> A few spells exist in code but use a slightly different registry id than their class name suggests
> (e.g. Holy "Angel Wings" registers as `angel_wing`). When in doubt, trust `/magicnpcs spells` over
> this static list.
