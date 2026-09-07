# 06 — Add-on Spells

Iron's Spells add-ons start life as `UNVERIFIED` — the mod skips them by default rather than let a
mob spend mana on a spell it can't actually drive. A `spell_manifests` file is how a pack author
declares what an add-on spell reads, turning it into something a loadout can safely use. This pack
declares two fictional add-ons and consumes the result in a loadout.

**The `traveloptics:` and `esoteric_spell:` ids in this pack are placeholders** — no such add-ons
were available to verify against in this dev environment. Replace them with real spell ids from
the add-on you're actually declaring, and confirm they resolve with `/magicnpcs spells` or
`/magicnpcs loadout id <entity_type>` before shipping.

## Files

| File | Purpose |
|---|---|
| `data/addon_spells/spell_manifests/traveloptics.json` | Declares five `traveloptics:` spells; includes a `_review` list of rows still under scrutiny |
| `data/addon_spells/spell_manifests/esoteric_spell.json` | Declares seven `esoteric_spell:` spells: two supported (`TARGET_ENTITY`, `ADDON_DEFAULT`) and one of every capability that marks a spell a mob can't drive (`MULTI_TARGET`, `SPECIAL_PREPARATION`, `PLAYER_ONLY`, `UTILITY_NON_COMBAT`, `UNVERIFIED`) |
| `data/addon_spells/spell_manifests/zz_local_corrections.json` | Corrects two rows from the other two files without editing them |
| `data/addon_spells/spellcasters/husk_addon_caster.json` | A loadout using the declared add-on spells, plus a vanilla-Iron's fallback spell |

## What this teaches

- **Manifest schema.** `format` (must be `1`), `verified_against` (a free-text note on what the
  declaration was tested against), and `spells` (a map of spell id → capability).
- **Every capability value**, across the three manifest files combined: `DIRECT`,
  `TARGET_ENTITY`, `TARGET_AREA`, `GROUND_AOE_FORWARD`, `SUMMON`, `ADDON_DEFAULT`, `MULTI_TARGET`,
  `SPECIAL_PREPARATION`, `PLAYER_ONLY`, `UTILITY_NON_COMBAT`, `UNVERIFIED`.
- **The `_review` channel.** `SpellManifestJson.parse` only reads the top-level `format`,
  `verified_against`, and `spells` keys; any other top-level key is ignored regardless of its
  name, so `traveloptics.json` uses `_review` as a comment list for rows the author isn't fully
  confident in yet. That only works at the top level — inside `spells`, every key must resolve to
  a namespaced spell id, so an underscore-prefixed key there is rejected (`MANIFEST_BAD_ID`), not
  ignored.
- **Resource-id merge order.** `zz_local_corrections.json` sorts after the other two files and
  overrides `traveloptics:riptide_surge` and `esoteric_spell:experimental_bolt`, with a logged
  warning — the mechanism for fixing an upstream manifest without editing it directly.
- **A loadout consuming declared spells.** `husk_addon_caster.json` casts
  `traveloptics:tidal_lance` and `esoteric_spell:mind_spike` because the manifests in this same
  pack made them known-castable, and falls back to `irons_spellbooks:magic_missile` so the husk
  still has something to do if the add-on isn't installed (its entries would be dropped with an
  INFO log line, not an error).
- **Manifests reload before loadouts** on `/reload`, so a loadout in the same `/reload` sees the
  new declarations immediately.

## The other ways to enable an add-on spell

A manifest is the recommended, shareable mechanism, but `magicnpcs-server.toml` → `[spells]` has
config-side alternatives with real trade-offs:

| Mechanism | Ease | Sharing | Verification | Friendly-fire | Cast-data guarantee |
|---|---|---|---|---|---|
| Manifest (this pack) | Medium | Yes | Yes | Yes | Yes |
| `spells.trustedNamespaces` | Low | N/A | No — a trust claim | No (falls back to the aim-line `CORRIDOR` friendly-fire shape, not the spell's real AoE geometry) | No (opportunistic) |
| `spells.allowUnverifiedSpells` | Very low | N/A | No | No | No |

`spells.capabilityOverrides` (e.g. `["traveloptics:tidal_lance=TARGET_ENTITY"]`) fixes a single
spell without a datapack, and outranks a manifest, the built-in table, and namespace trust — use
it for correcting one spell rather than trusting a whole namespace.

## Auditing before you write a manifest

`tools/spell_manifest_audit.py <mods_dir> --out <out_dir>` reads add-on jars offline and drafts a
manifest and per-spell audit table without starting Minecraft — treat its output as a draft for
human review, not a finished manifest. Once a manifest exists, `/magicnpcs audit spells [namespace]`
runs an in-game RESOLVE check (and, with `cast`, an actual cast on a dummy) against it. See
[`docs/compat/irons-addons.md`](../../docs/compat/irons-addons.md) for the full workflow.

## Try it

```
/magicnpcs validate
/magicnpcs audit spells traveloptics
/magicnpcs spells traveloptics
```
