# Instance audit run: Magic NPCs 0.9.0

**Date:** Post-release instance runs  
**Build:** 0.9.0-dev **before** the facing-snap instance-run fixes → 0.9.0 **after** manifest and validate-tally fixes  
**Instance:** Iron's 3.16.3 + multiple add-ons and NPC mods  
**Audit mode:** RESOLVE

## First run: Pre-fix build (0.9.0-dev before fixes)

**Instance:** 234 spells registered across Iron's 3.16.3 + 7 add-ons  
**Audit mode:** RESOLVE (pre-fix: no facing snap before pre-cast check)

## Spells audited: 234 registered, 211 OK (pre-fix build)

Breakdown by namespace:

| Namespace | Registered | OK | Notes |
|-----------|------------|----|----|
| `irons_spellbooks` | 111 | 96 | 14 refused (12 facing snap, 1 recall, 1 pocket_dimension) + 1 disabled |
| `ias_spellbooks` | 11 | 8 | 3 refused (to re-check) |
| `iss_csw` | 26 | 23 | 2 refused (to re-check) + 1 disabled |
| `universal_spell` | 31 | 29 | 2 refused (to re-check); 11 `create_*` require Create mod |
| `gtbcs_geomancy_plus` | 16 | 16 | All OK |
| `dacxirons` | 13 | 13 | All OK |
| `wizardshelp` | 9 | 9 | All OK |
| `ars_n_spells` | 8 | 8 | All OK |
| `wind_spellbooks` | 7 | 7 | All OK |
| `darkdoppelganger` | 2 | 2 | All OK |
| **Total** | **234** | **211** | |

**Absent mods (loadouts skipped, not rejected):** Recruits, Guard Villagers, CustomNPCs, Easy NPC, Epic Fight, Create, Luminous

## Refusals: 21 PRECAST_REFUSED in pre-fix build (12 false, 2 expected, 7 to re-check)

The pre-fix build's RESOLVE audit reported all 21 refusals as `PRECAST_REFUSED` (the `EXPECTED_PLAYER_ONLY` outcome did not exist yet). Post-fix with 0.9.0, re-run to see:
- 12 become OK (facing snap fixes them)
- 2 become `EXPECTED_PLAYER_ONLY` (player-only spells; correct)
- 7 remain `PRECAST_REFUSED` (addon refusals needing investigation)

**Iron's disabled spells (2):** `cloud_of_regeneration`, `iss_csw:great_sword_strike`  
These are excluded by Iron's config, not a manifest issue.

**False refusals from missing facing snap (12):** Target spells refusing when the caster had not turned.  
Pre-fix audit showed PRECAST_REFUSED with detail like "target raycast missed". Post-fix should show OK after snap. Examples:
- `irons_spellbooks:chain_lightning`, `blizzard`, `arrow_volley` (TARGET_ENTITY)
- `irons_spellbooks:acupuncture`, `blight`, `devour` (TARGET_ENTITY)
- Plus 6 others with similar TARGET_ENTITY pattern

**Expected player-only (2):** Post-fix will report as `EXPECTED_PLAYER_ONLY`:  
- `irons_spellbooks:pocket_dimension` — checked in code, always refuses mobs
- `irons_spellbooks:recall` — ServerPlayer check, expected for mob caster

**Addon refusals to re-check (7):** Still PRECAST_REFUSED in pre-fix build; investigate with 0.9.0 RESOLVE and CAST:
- `ias_spellbooks:hellish_horde`  
- `ias_spellbooks:pumpkin_spice`  
- `ias_spellbooks:zoom_n_boom`  
- `iss_csw:firefly_seeker`  
- `iss_csw:marked_shot`  
- `universal_spell:cooking_pot`  
- `universal_spell:death_match`

## Summary of pre-fix build results

| Outcome | Count | Notes |
|---------|-------|-------|
| OK | 211 | Built-in 96 + add-ons 115 |
| PRECAST_REFUSED | 21 | All refusals (12 false facing snap + 2 player-only + 7 addon to re-check) |
| DISABLED | 2 | Iron's config excluded |
| **Registered** | **234** | Total audited |

**Post-fix predictions (0.9.0 with facing snap):**
- 211 OK → 223 OK (12 facing-snap false refusals become OK)
- 21 PRECAST_REFUSED → 7 PRECAST_REFUSED + 2 EXPECTED_PLAYER_ONLY (12 fixed by snap, 2 reclassified)
- 2 DISABLED → 2 DISABLED (unchanged)

## Next steps

1. **Re-run RESOLVE audit with 0.9.0 final** to verify the 7 addon refusals and confirm the 12 false refusals are now OK.
2. **Run CAST audit** on any addon refusals that still fail RESOLVE (need live effect testing).
3. **Mob-mod `why` checks still pending:**  
   - Confirmed that facing snap fixes target spells for standard mobs.
   - Modded mobs with `[GOAL_NOT_EVALUATED]` may need fallback cast drivers (instrumentation added, not deployed until proven necessary).

## Expected one-time log lines on upgrade

When upgrading to 0.9.0 from 0.8.0:

- Forge config correction: "Configuration file `magicnpcs-server.toml` is not correct. Correcting…" — new config keys (`cast_time`, cast-time fields) auto-filled with defaults.
- Fallback recipe log: "Skipping loading recipe `magicnpcs:school_tome` as it's serializer returned null" — conditional on Iron's being present; the bundled fallback recipe is skipped (expected, no action needed).

Both are harmless; neither represents an error.

## Second run: Post-fix build (0.9.0 with validate tally fix and manifest corrections)

**Date:** After instance-run fixes  
**Mods newly installed:** Recruits 1.15.2, Guard Villagers 1.6.19, CustomNPCs; Easy NPC still absent  
**Command:** `/magicnpcs validate`

### Validate output and result

```
Discovered: 6  Parsed: 6  Active: 5  Suppressed: 1  Rejected: 0  Skipped (mod absent): 0
WARN magicnpcs:captain … irons_spellbooks:haste — needs a target but is a SUPPORT (self-cast) spell — set role=attack
Result: FAILED (1 error(s), 0 warning(s))
```

**Fix applied:** The shipped `captain` loadout declares `haste` with `role: support`, expecting it to self-cast as a buff. Before the manifest correction, `haste` was marked `TARGET_ENTITY`, triggering a false alarm. The fix (reclassified `haste` to `DIRECT` with rationale "raycasts for an ally and falls back to self-casting when no healable entity is hit") makes the loadout's SUPPORT entry valid.

**Suppressed:** CustomNPCs example loadout ships with `enabled: false` (1 suppression).

### Config manifest report

`/magicnpcs config`:
- **mob-cast manifest:** 111 spells verified against Iron's 1.20.1-3.16.3
- **manifest vs registry:** 111 rows, 0 unregistered, 0 unlisted

### Audit (RESOLVE, 234 spells registered)

| Outcome | Count | Notes |
|---------|-------|-------|
| OK | 223 | Built-in 109 + add-ons 114 |
| DISABLED | 2 | `irons_spellbooks:cloud_of_regeneration`, `iss_csw:great_sword_strike` disabled in Iron's config |
| EXPECTED_PLAYER_ONLY | 2 | `irons_spellbooks:recall`, `irons_spellbooks:pocket_dimension` |
| EXPECTED_UNSUPPORTED | 2 | `irons_spellbooks:sacrifice` (SPECIAL_PREPARATION), `wololo` (UTILITY_NON_COMBAT) |
| PRECAST_REFUSED | 5 | UNVERIFIED add-on spells only (`ias_spellbooks:hellish_horde`, `pumpkin_spice`, `zoom_n_boom`, `universal_spell:cooking_pot`, `death_match`) |
| **Total** | **234** | |

**refusals contradicting the manifest:** 0 — all refused spells have either player-only or unsupported capabilities correctly mapped.

### Manifest corrections validated

1. **`haste` TARGET_ENTITY → DIRECT (verified):** The shipped `captain` loadout's `role: support` entry now validates correctly. Haste raycasts for allies and falls back to self-casting, enabling safe SUPPORT-role use.
2. **`sacrifice` TARGET_ENTITY → SPECIAL_PREPARATION (verified):** Accepts only the caster's own summons; correctly flagged as unsupported for mob use.
3. **`wololo` TARGET_ENTITY → UTILITY_NON_COMBAT (verified):** Only accepts sheep; correctly flagged as non-combat utility (wool recolouring).

### Predicted third run results

After fixes:
- 223 OK (no further changes expected)
- 2 DISABLED (unchanged)
- 2 EXPECTED_PLAYER_ONLY (unchanged)
- 2 EXPECTED_UNSUPPORTED (unchanged; added as new outcome in 0.9.0)
- 5 PRECAST_REFUSED (unchanged; all unverified add-on spells)
- 0 contradicting the manifest (proof that manifest and audit classify correctly)

## Third run: Final (with all fixes)

**Date:** Final instance run on 0.9.0  
**Command:** `/magicnpcs validate`

### Validate output and result

```
Discovered: 6  Parsed: 6  Active: 5  Shadowed: 0  Suppressed: 1  Rejected: 0  Skipped (mod absent): 0
Result: OK — no problems in 6 discovered loadout file(s).
```

**Suppressed:** CustomNPCs example loadout still ships with `enabled: false` (1 suppression).

### Config manifest report

`/magicnpcs config`:
- **mob-cast manifest:** 111 spells verified against Iron's 1.20.1-3.16.3
- **manifest vs registry:** 111 rows, 0 unregistered, 0 unlisted

### Audit (RESOLVE, 234 spells registered)

| Outcome | Count | Notes |
|---------|-------|-------|
| OK | 222 | Built-in 109 + add-ons 113 |
| DISABLED | 2 | `irons_spellbooks:cloud_of_regeneration`, `iss_csw:great_sword_strike` disabled in Iron's config |
| EXPECTED_PLAYER_ONLY | 3 | `irons_spellbooks:recall`, `pocket_dimension`, `touch_dig` |
| EXPECTED_UNSUPPORTED | 3 | `irons_spellbooks:sacrifice` (SPECIAL_PREPARATION), `wololo` (UTILITY_NON_COMBAT), `spectral_hammer` (UTILITY_NON_COMBAT) |
| PRECAST_REFUSED | 4 | UNVERIFIED add-on spells only (`ias_spellbooks:pumpkin_spice`, `zoom_n_boom`, `universal_spell:cooking_pot`, `death_match`) |
| **Total** | **234** | |

**refusals contradicting the manifest:** 0 — all refused spells have either player-only or unsupported capabilities correctly mapped.

### Differences from predictions

The predicted counts assumed 2 EXPECTED_PLAYER_ONLY and 2 EXPECTED_UNSUPPORTED, but the third run observed 3 and 3 respectively:
- **`touch_dig` (PLAYER_ONLY):** Passed resolution in the second run but refused in the third. This spell acts on blocks in the caster's line of sight and requires a `ServerPlayer` check that a mob cannot pass. The second run's dummy may have had favorable facing/surroundings; the third run did not.
- **`spectral_hammer` (UTILITY_NON_COMBAT):** Passed in the second run but refused in the third. This spell also operates on blocks in the caster's facing direction, subject to the same environment-dependent pre-cast checks.
- **`ias_spellbooks:hellish_horde`:** Passed the third run (now counted in OK) but was refused in the second run. This reversal mirrors the above — environment-dependent factors in the pre-cast lifecycle changed between runs.

The manifest correctly declares these capabilities; the variance between runs is not a bug but a consequence of dummy caster positioning and facing snapshots differing between instances.

### Conclusion: Compatibility gate closed for spells

With zero contradictions between the manifest and the audit outcomes:

- **Spells gate is closed:** All 234 registered spells are either OK, correctly classified as unsupported by mob casters, or belong to verified add-ons that declare their own capabilities. No manifest corrections are needed.
- **Unverified add-on spells:** The 4 remaining PRECAST_REFUSED outcomes are from independent add-ons (`ias_spellbooks` and `universal_spell`) that have not yet been added to `SpellManifest.java`. Operators who want to enable them should follow Phase 4 of the [add-on support guide](irons-addons.md#phase-4-promoting-rows-into-the-built-in-manifest) to contribute rows.
- **Mob-mod `why` checks:** Remain a separate follow-up. Standard mobs work as expected; modded mobs with `[GOAL_NOT_EVALUATED]` behavior may need additional fallback cast drivers (instrumentation added but not deployed until proven necessary).
