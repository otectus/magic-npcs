# 05 — Staff and Focus

Arming a mob on spawn and then gating its spells on actually holding the right item. This pack
covers the `equipment` block, the per-spell held-item gate, and extending the mod's own
`magicnpcs:spell_focuses` item tag.

## Files

| File | `entity_type` | Notes |
|---|---|---|
| `data/magicnpcs/tags/items/spell_focuses.json` | — | Extends the `magicnpcs:spell_focuses` tag with `minecraft:blaze_rod`, `minecraft:end_rod`, and `irons_spellbooks:blank_rune` |
| `skeleton_staffbearer.json` | `minecraft:skeleton` | Weighted spawn gear on `mainhand`/`offhand`; one spell requires `#magicnpcs:spell_focuses` in the main hand, one has no item gate at all |
| `pillager_warlock.json` | `minecraft:pillager` | Explicit item list (not a tag) for spawn gear; `required_hand: "either"` |
| `witch_offhand_talisman.json` | `minecraft:witch` | Offhand-only spawn gear; `required_hand: "off"` |

## What this teaches

- **`equipment` block.** Each hand takes a weighted list — a bare item id string (weight 1) or an
  `{item, weight}` object — plus `chance` (odds of equipping at all) and `only_if_empty` (whether
  to leave a hand alone if the mob already holds something). It's applied once, on the reconcile
  pass at spawn/reload, not re-rolled every tick or on a later `/reload`.
- **`require_held_item` / `required_items` / `required_hand`.** A spell entry can require the
  caster to be holding one of `required_items` (item ids or `#tag`) in the hand named by
  `required_hand` (`main`, `off`, or `either`). `skeleton_staffbearer.json`'s `magic_missile`
  requires `#magicnpcs:spell_focuses` in the main hand; its `snowball` has no such gate, so an
  unarmed skeleton still has something to cast.
- **`spell_focuses` is an ordinary item tag**, so a pack can extend it (`"replace": false`) rather
  than override it — this sample adds three items to whatever the mod (or another pack) already
  lists.
- **This is not the only way to gate on an item.** `pillager_warlock.json` uses an explicit item
  list instead of the tag, and `witch_offhand_talisman.json` mixes a tag entry with a plain item
  id in the same `required_items` list.

## Prerequisites

Iron's Spells 'n Spellbooks, for the spell ids and the `irons_spellbooks:pyrium_staff` /
`graybeard_staff` / `blank_rune` item ids. **Verify those item ids against your installed Iron's
version** — run `/magicnpcs spells` or check the item's registry name in-game before relying on
this pack as-is.

## Try it

```
/summon minecraft:skeleton ~ ~ ~
/magicnpcs loadout entity @e[type=minecraft:skeleton,limit=1,sort=nearest]
```

Watch the skeleton's main hand after it spawns (equipping is a 35% chance here); disarm it and
run `/magicnpcs why` to see `magic_missile` drop out of the eligible spell list while `snowball`
stays available.
