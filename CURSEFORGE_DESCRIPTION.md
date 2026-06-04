# Magic NPCs

**Give your NPCs real spells.** Magic NPCs makes mobs cast spells from *Iron's
Spells 'n Spellbooks* — driven entirely by datapacks, so any mob can become a
spellcaster, and **Villager Recruits** get first-class support.

## What it does

- **Datapack-driven.** Drop a small JSON in `data/<pack>/spellcasters/` naming an
  entity type and a list of Iron's spells — that mob now casts them. No tags, no
  add-on mods. Shipped loadouts make Recruits (and, as an example, skeletons) cast
  out of the box, and any pack can override them.
- **Real Iron's casting.** Spells fire through Iron's own cast path, so you get the
  genuine projectiles, particles, and sounds. Mobs have a real mana pool that
  regenerates and gates how often they cast.
- **Smart, safe Recruits.** Spellcasting recruits scale their mana with their
  **rank**, cast **only at enemies** (never their owner or allies, using Recruits'
  own diplomacy), and hold fire when an ally is **in the line of fire**.
- **Tunable.** A server config controls global on/off, mana/cooldown balance, a
  spell allow/deny list, friendly-fire safety, and the Recruits options. Optionally,
  recruits can use Iron's *own* combat AI.

## Requirements

- **Forge 47.4.0+** for Minecraft **1.20.1**
- **Iron's Spells 'n Spellbooks** (3.15.x) + its dependencies **GeckoLib**,
  **Curios API**, **PlayerAnimator** — required for any spellcasting
- **Villager Recruits** (1.15.0+) — *optional*, enables the recruit features

Magic NPCs does not include these mods; install them separately.

## Notes

- Server-side config; settings sync to clients automatically.
- Open source under GPL-3.0.
