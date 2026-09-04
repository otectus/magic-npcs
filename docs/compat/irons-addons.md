# Iron's Spells 'n Spellbooks add-ons: support, auditing, and manifests

Magic NPCs only lets a mob cast a spell whose **capability** it knows — what cast data the spell reads, whether it refuses a non-player caster, and so on. The mod ships a reviewed table for every Iron's spell it has tested against a real mob cast lifecycle; Iron's add-ons fall outside that table and start life as **UNVERIFIED** — the mod skips them by default, so a spell that does nothing while spending mana never fires without explicit operator consent.

This guide covers the three ways to make an add-on spell castable, the trade-offs of each, how to audit them, and the tooling that supports both.

## Three ways to enable an add-on spell

### 1. Datapack spell manifest (recommended)

A **pack author** creates a file declaring the mob-cast capability of one or more spells. This is the primary mechanism, shareable, and survives mod updates without code changes.

**File location:** `data/<namespace>/spell_manifests/<name>.json` (same folder style as loadouts)

**Format:**
```json
{
  "format": 1,
  "verified_against": "traveloptics 1.2.0, tested against a real mob",
  "spells": {
    "traveloptics:tidal_lance": "TARGET_ENTITY",
    "traveloptics:aqua_affinity": "DIRECT",
    "traveloptics:tidal_wave": "TARGET_AREA"
  }
}
```

- `format` — version (required, must be `1`)
- `verified_against` — free-form note on what this was tested against; helps future readers (recommended)
- `spells` — map of spell ids to capabilities (required; at least one entry)

**Capabilities:** `DIRECT` (aimed projectile or simple self-effect), `TARGET_ENTITY` (reads a single target), `TARGET_AREA` (builds its own target area), `GROUND_AOE_FORWARD` (forward ground AoE from facing), `SUMMON` (summons entities), `ADDON_DEFAULT` (namespace-trusted default: works as DIRECT but supplies target data when available), `MULTI_TARGET`, `SPECIAL_PREPARATION`, `PLAYER_ONLY`, `UTILITY_NON_COMBAT`, `UNVERIFIED`.

**Merging:** Multiple manifest files are merged in resource-id order, so a later file overrides an earlier one with a logged warning. This lets a pack author correct an upstream manifest without editing it.

**Keys starting with `_` are ignored**, so you can use `"_review": [...]` as a comment channel for rows you are still uncertain about.

**On `/reload`:** Reloaded immediately, before the loadout manager, so loadout validation sees the new declarations right away.

**Problem codes** in `/magicnpcs validate`:
- `MANIFEST_FORMAT` — invalid format value or missing `spells` object (whole file skipped)
- `MANIFEST_NO_SPELLS` — empty spells object (whole file skipped)
- `MANIFEST_BAD_ID` — spell id is malformed (that row skipped)
- `MANIFEST_BAD_CAPABILITY` — unknown capability name (that row skipped)

### 2. Config `spells.trustedNamespaces`

An **operator** declares whole namespaces trusted: spells from those namespaces are treated as `ADDON_DEFAULT` when no higher layer covers them.

**Config:** `magicnpcs-server.toml` → `[spells]` → `trustedNamespaces = ["traveloptics", "esoteric_spell"]`

**Pros:**
- One line enables a whole namespace, no datapack needed
- Works immediately on config reload

**Cons:**
- A trust claim, not verified — the mod applies a default friendly-fire shape (CORRIDOR) that may be wrong for an area spell
- No cast-data guarantee: a spell that needs `TargetEntityCastData` gets it only when the mob has a live target (ADDON_DEFAULT opportunistic behavior)
- Addon spells show `[NAMESPACE_TRUSTED]` warning in `/magicnpcs validate`; a manifest is the way to silence it

**When to use:** Testing a new addon pack, or when every spell in a namespace truly is single-target.

### 3. Global opt-in `spells.allowUnverifiedSpells`

An **operator** says "I accept the risk" and lets every unverified spell cast.

**Config:** `magicnpcs-server.toml` → `[spells]` → `allowUnverifiedSpells = true`

**Pros:**
- One config line, nothing to maintain

**Cons:**
- Every spell gets its best guess at runtime; some may do nothing while spending mana
- No friendly-fire protection: area spells use CORRIDOR when the real shape is unknown
- No cast-data guarantee: a spell that needs a target gets it only if the mob has one
- This is explicitly "at your own risk"

**When to use:** Single-player testing, or an operator who understands and accepts the limitations.

## Trade-offs summary

| Mechanism | Ease | Sharing | Verification | Friendly-fire | Cast-data guarantee |
|-----------|------|---------|--------------|---------------|---------------------|
| Manifest | Medium | Yes | Yes | Yes | Yes |
| Trusted namespace | Low | N/A | No | No (CORRIDOR assumed) | No (opportunistic) |
| Allow unverified | Very low | N/A | No | No | No |

## Fixing one spell: `spells.capabilityOverrides`

When a manifest is too heavy-handed — one spell in a namespace behaves differently from the rest — use the config override to fix just that spell:

**Config:** `magicnpcs-server.toml` → `[spells]` → `capabilityOverrides = ["traveloptics:tidal_lance=TARGET_ENTITY"]`

This outranks a manifest, the built-in table, and namespace trust, so one spell can be corrected on the fly without a datapack. Multiple overrides are comma-separated lists.

## Spell-id wildcards

Both `spells.spellWhitelist` and `spells.spellBlacklist` now accept `namespace:*` patterns to cover whole namespaces at once:

```
spellBlacklist = ["traveloptics:*", "irons_spellbooks:fireball"]
spellWhitelist = ["irons_spellbooks:*", "esoteric_spell:*"]
```

A non-empty whitelist without wildcards still excludes spells from `spells.trustedNamespaces` unless you add a wildcard for that namespace. `/magicnpcs validate` summarizes with `Spell support: <counts>; <N> excluded by spells.spellWhitelist/spellBlacklist`, showing how many spells were filtered by both lists.

## SUPPORT spell classification and addon spells

The `role: support` classification (self-cast heals/buffs) is determined by checking `spells.supportSpellIds` first, then filtering out `TARGET_ENTITY` spells, then matching keywords in the spell name. The process is namespace-agnostic, so addon SUPPORT spells work as long as they either:

1. List themselves in `spells.supportSpellIds`, or
2. Have `TARGET_ENTITY` capability set in a manifest, config override, or namespace trust, or
3. Have a name matching support keywords (heal, cure, bless, shield, protect, etc.)

## Auditing add-on spells: offline and in-game

### Phase 1: Offline heuristic with `tools/spell_manifest_audit.py`

Pack authors or operators can run the offline audit tool to draft a manifest without starting Minecraft:

```bash
python tools/spell_manifest_audit.py <mods_dir> --out <out_dir> \
  [--irons <jar>] [--namespace ns ...] [--verbose]
```

**Inputs:**
- `<mods_dir>` — folder containing your mod jars
- `--irons <jar>` — path to the Iron's jar (auto-detected if omitted)
- `--namespace <ns>` — restrict output to certain namespaces (repeatable; Iron's calibration always runs)

**Outputs** (under `--out`):
- `<ns>.manifest.json` — draft manifest with `spells` map and `_review` list for uncertain rows
- `<ns>.audit.md` — one table row per spell class
- `summary.md` — per-namespace counts and calibration against the built-in Iron's table

**What it does:** Reads class files without a JVM, finds `AbstractSpell` subclasses, resolves spell ids from lang files, and infers capability from class structure.

**What it doesn't do:** It is a heuristic, not runtime-verified. It reports confidence and evidence per row (`_review` list marks low-confidence guesses). Take its output as a **draft for human review**, not a finished manifest.

**Known limitations:**
- GROUND_AOE_FORWARD capability is never inferred (requires understanding the spell's spatial logic)
- Re-skinned spells sharing a parent's lang key cannot be distinguished (e.g., multiple `ars_cross_*` spells resolve to one id)
- Anonymous or dynamically generated classes may not resolve

### Phase 2: In-game RESOLVE audit with `/magicnpcs audit spells`

After drafting a manifest, test it in-game with no spell effects:

```
/magicnpcs audit spells [namespace]
```

**RESOLVE mode outcomes:**

| Outcome | Meaning |
|---------|---------|
| **OK** | Iron's accepted the spell; it cast or would cast. |
| **EXPECTED_PLAYER_ONLY** | Iron's refused it. The spell declares `checkPreCastConditions(ServerPlayer)` only — this is expected; the manifest or proof is correct. |
| **EXPECTED_UNSUPPORTED** | Iron's refused it. This build's verified manifest already marks the spell as a capability a mob cannot supply (e.g., `SPECIAL_PREPARATION` for summon-targeting spells, `UTILITY_NON_COMBAT` for sheep-recolouring spells, `MULTI_TARGET` for spells needing multiple simultaneous targets). The refusal is expected; manifest marks this <CAPABILITY>; a mob cannot supply it. |
| **PRECAST_REFUSED** | Iron's refused it. The spell passed resolution and provenance checks but Iron's pre-cast condition failed. Detail includes the reason (e.g., "target raycast missed (manifest: TARGET_ENTITY)" for target spells). When the detail carries `[MANIFEST_SUSPECT]` appended, this build verified a mob-castable capability but Iron's refused it — one of the two is wrong (e.g., refusal is a bug from missing facing snap, or the manifest claim is too strong). Investigate; an instance run that masses these is a signal to fix upstream. |
| **DISABLED** | The spell is registered but disabled in Iron's config. Not a cast problem; the spell is turned off. |
| **EXCEPTION** | Iron's threw an uncaught exception during resolution or pre-cast check. A bug; report with the exception details. |
| **REFUSED** / **LIFECYCLE_COMPLETED** / **CANCELLED** / **TIMEOUT** | Outcomes from CAST mode only (see below). |

**CAST mode (opt-in):** `/magicnpcs audit spells [namespace] cast` actually runs each spell through a full `MobCastSession` on spawned dummy casters. Records mana and nearby-entity deltas, flags `NO_OBSERVABLE_EFFECT` when mana is spent but nothing changed, and catches lifecycle shortcuts (spell completion or cancellation before ticks expire). Slower and has real side effects (particles, sounds, summons on dummies until cleanup), but answers "does the spell actually run". Report written to `<gamedir>/magicnpcs/audit-spells-<timestamp>.json` with table `id | provenance | cast type | mana | outcome | manaD | entD | detail` and per-outcome/per-namespace counts.

**Facing and raycast:** Iron's `Utils.preCastTargetHelper` raycasts along the caster's current facing. A target spell refusing a caster that has not turned toward its target yet is often a false refusal (SPI-001). The audit probes with `MobCastSession.prepare`, which snaps facing before the pre-cast check, so you will see OK for target spells that were previously PRECAST_REFUSED due to the facing snap being missed elsewhere. See `CasterFacing.snap` in the codebase.

### Phase 3: In-game CAST audit for a disposable world only

Once RESOLVE passes, test with real effects in a disposable world:

```
/magicnpcs audit spells [namespace] cast
```

**Warning:** This runs every spell for real on spawned dummies. Summons, projectiles, block changes, and explosions all happen.

**What it additionally checks:**
- Mana deduction (did the spell actually charge mana?)
- Entity changes (did anything die, get hit, or move?)
- `NO_OBSERVABLE_EFFECT` hint (the spell completed but nothing visible happened)

A spell showing NO_OBSERVABLE_EFFECT likely means:
- The capability is wrong (spell builds its own cast data that a manifest didn't declare)
- It is player-only and does nothing for a mob
- It is broken

**Report:** `audit-spells-<timestamp>.json` includes per-spell `manaD` (mana delta) and `entD` (nearby-entity delta) to help diagnose what happened.

### Phase 4: Promoting rows into the built-in manifest

After completing audits and confirming spells work, send a pull request to add them to `SpellManifest.java`. The file is checked in and locked to the Iron's version in `gradle.properties` — every `MAJOR.MINOR` bump requires regenerating the table to ensure claims are accurate. This is intentional: a version mismatch is caught at compile time, not hidden in the audit log.

## Workflow for a new addon pack

1. **Install the pack and Magic NPCs.** Run `/magicnpcs validate` — all addon spells show UNVERIFIED.

2. **Run the offline audit:**
   ```bash
   python tools/spell_manifest_audit.py ~/mods --out ~/audit --namespace addonname
   ```

3. **Review the draft:** Read `addonname.audit.md` and `addonname.manifest.json`. Move spells from `_review` to `spells` once you are confident.

4. **Create a datapack manifest:** Place your edited `addonname.manifest.json` at `data/addonname/spell_manifests/reviewed.json` in a datapack.

5. **Run RESOLVE audit in-game:**
   ```
   /magicnpcs audit spells addonname
   ```

6. **Fix any PRECAST_REFUSED or REFUSED outcomes:** Read the report, adjust the manifest, and re-test.

7. **(Optional) CAST audit on a copy world:** For a final check, run the spell on real dummies.

8. **Promote the manifest to production:** Remove the `_review` comments and ship the datapack with your modpack.

## Known limitations

- **No dynamic trait discovery:** Addon spells whose behavior changes based on config are not inspected — you must understand the spell and declare it yourself.
- **ars_n_spells `ars_cross_*` spells:** These proxy spells require a `ServerPlayer` in `onCast` and do nothing silently for a mob, even with a correct capability declared. They are single-target and have no visible effect on a non-player caster.
- **universal_spell utilities:** Spells like crafting table, smelting, portable anvil, and harvest have no combat role. Even with a correct manifest they are not useful for NPCs and should be excluded with `spellBlacklist`.

## Instance addons observed

In the test instance for this release:
- traveloptics (school `aqua`)
- esoteric_spell (schools `barrier`, `esoteric`)
- universal_spell (mostly utility)
- gtbcs_geomancy_plus (school `geo`) + gtbcs_spell_lib
- dacxirons
- ias_spellbooks
- wizardshelp (summons)
- wind_spellbooks (school `wind`)
- darkdoppelganger
- iss_csw
- covenant_of_the_seven
- ironsarms
- efiscompat
- twilight_spellbooks
- vampire_spells_addon
- ars_n_spells

## Further reading

- [Magic NPCs loadout guide](../loadouts/README.md) — how to define a caster
- The diagnostic commands (`/magicnpcs why`, `/magicnpcs validate`, `/magicnpcs spells`, etc.) are described in the [README's diagnosing section](../../README.md#13-diagnosing-a-mob-that-isnt-casting).
- [ADR 0012: Layered spell support](../decisions/0012-layered-spell-support-and-empirical-audit.md)
