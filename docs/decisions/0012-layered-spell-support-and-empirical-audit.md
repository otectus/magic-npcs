# ADR 0012 — Layered spell support and empirical audit

**Status:** Accepted (0.9.0)
**Date:** 2026-09-03

## Context

**Single choke point:** Through 0.8.0, every spell's mob-cast capability was decided by one of two paths: the reviewed built-in table for `irons_spellbooks`, or nothing (UNVERIFIED). Pack authors with Iron's add-ons had three options, all unsatisfying:

1. Set `spells.allowUnverifiedSpells = true` globally, accepting silence from spells that do nothing while spending mana.
2. List every spell in a `spellWhitelist`, duplicating work across packs.
3. Ask Magic NPCs to review a new add-on, multiplying the support burden.

For operators, the only way to fix one spell was a modpack-level override with no datapack-friendly sharing.

**Verification is hard.** When Magic NPCs was first released, Iron's itself was small enough to hand-review every spell by testing a real mob cast. That practice scaled poorly. Iron's has grown, add-ons outnumber it, and mobs are not always available in dev environments. Magic NPCs needed to answer "is this spell mob-castable?" in multiple ways: for Iron's core, via a checked-in manifest; for add-ons, via user-authored datapack manifests; for testing both, via offline heuristics and in-game proofs.

## Decision

### Five layers of spell-capability precedence

Spells now have a five-tier support chain with provenance in diagnostics:

1. **Config overrides** (`spells.capabilityOverrides`) — operator's explicit statement about one spell
2. **Datapack manifests** (`data/<namespace>/spell_manifests/*.json`) — pack author's declaration
3. **Built-in reviewed table** (Iron's 1.20.1-3.16.3) — Magic NPCs' tested set
4. **Namespace trust** (`spells.trustedNamespaces`) — weak claim, treated as ADDON_DEFAULT
5. **Nothing** — UNVERIFIED, skipped by default

Each layer only applies if no higher layer covered the spell. The **provenance** (which layer decided) is printed in diagnostics (`/magicnpcs spells`, `/magicnpcs validate`), so the fix is obvious: add a manifest, name it in a namespace-trust list, or adjust a config line.

### Datapack manifests as the primary mechanism

Pack authors can now declare spells in a shareable, version-stable way:

- **File location:** `data/<namespace>/spell_manifests/<name>.json`
- **Format:**
  ```json
  {
    "format": 1,
    "verified_against": "traveloptics 1.2.0, tested 2026-09-01",
    "spells": {
      "traveloptics:tidal_lance": "TARGET_ENTITY",
      "traveloptics:aqua_affinity": "DIRECT"
    }
  }
  ```
- **Capabilities:** `DIRECT`, `TARGET_ENTITY`, `TARGET_AREA`, `GROUND_AOE_FORWARD`, `SUMMON` (castable); `ADDON_DEFAULT`, `MULTI_TARGET`, `SPECIAL_PREPARATION`, `PLAYER_ONLY`, `UTILITY_NON_COMBAT`, `UNVERIFIED` (not castable).
- **Merging:** Multiple manifest files are merged in resource-id order; a later file overrides an earlier one with a logged warning. This lets a pack author correct an upstream manifest.
- **Reloadable:** Manifests are loaded before the loadout manager (`SpellManifestLoader` registered before `LoadoutManager`), so `/reload` sees new declarations immediately.

**Why a manifest over a config:** Config is per-world, per-server, per-operator. A manifest is per-pack, authored once, shared across servers. The manifest is the datapack-native solution; config is the fallback for operators without datapack control.

### Config overrides as secondary mechanism

Operators can fix one spell without a datapack:

```toml
spells.capabilityOverrides = ["traveloptics:tidal_lance=TARGET_ENTITY"]
```

This outranks manifests and the built-in table, so a single spell can be corrected on the fly. Multiple overrides are comma-separated.

### Namespace trust with ADDON_DEFAULT semantics

`spells.trustedNamespaces` treats spells from those namespaces as `ADDON_DEFAULT` — a weak claim, not verified:

- Friendly-fire check uses CORRIDOR geometry (may be wrong for area spells)
- Cast session supplies target data **opportunistically**: only when the caster has a live target (`MobCastSession.begin` lines 183-189)
- No per-spell verification

This is useful for single-target add-ons that work without a manifest. `/magicnpcs validate` flags each one `[NAMESPACE_TRUSTED]`; a datapack manifest is the way to silence the warning.

### Wildcards in whitelist/blacklist

Both `spells.spellWhitelist` and `spells.spellBlacklist` now accept `namespace:*` patterns:

```toml
spellBlacklist = ["traveloptics:*", "irons_spellbooks:fireball"]
spellWhitelist = ["irons_spellbooks:*", "esoteric_spell:*"]
```

A non-empty whitelist without wildcards still excludes spells from `spells.trustedNamespaces`. The `/magicnpcs validate` summary line is: `Spell support: <provenance counts>; <N> excluded by spells.spellWhitelist/spellBlacklist`, naming both filter lists together.

### Provenance in diagnostics

`/magicnpcs spells` shows a one-letter provenance column:

- `V` — VERIFIED (built-in table)
- `O` — OVERRIDE (config)
- `M` — MANIFEST (datapack)
- `T` — Namespace-TRUSTED
- `U` — UNVERIFIED

`/magicnpcs validate` appends the full provenance and a fix hint for each unsupported spell, naming the exact config line or manifest path to edit.

### Bare spell-id suggestions without autopick

When a loadout references a bare id (e.g., `"spell": "fireball"`), the resolver suggests matches across all namespaces but still resolves only to `irons_spellbooks:` as the fallback. This is intentional: a suggestion is cheaper than a wrong guess, and the author must be explicit.

### Empirical auditing: offline heuristics and in-game proof

Two complementary tools for add-on packs:

#### Offline: `tools/spell_manifest_audit.py`

A stdlib Python 3 script that reads class files (no JVM) to draft a manifest:

```bash
python tools/spell_manifest_audit.py <mods_dir> --out <out_dir> \
  [--irons <jar>] [--namespace ns ...]
```

**What it does:**
- Finds `AbstractSpell` subclasses in jar files
- Resolves spell ids from lang files
- Infers capability (cast type, class references) with confidence and evidence
- Outputs `<ns>.manifest.json` (draft with `_review` list), `<ns>.audit.md` (table), `summary.md` (calibration)

**What it doesn't do:**
- This is a **heuristic, not verified**. The `_review` list marks uncertain rows.
- Cannot resolve re-skinned spells (shared lang keys) or dynamically generated classes
- GROUND_AOE_FORWARD is never inferred

**Calibration:** `summary.md` reports the heuristic's exact-match rate against the built-in Iron's table on this machine's instance (e.g., "114 Iron's spells, 91.2% exact match") so you know the confidence band.

#### In-game Phase 2: RESOLVE audit

```
/magicnpcs audit spells [namespace]
```

Checks every spell (or namespace) for:
- Resolution (can the id be found?)
- Provenance (which layer decided it?)
- Enabled status
- Cast type, mana cost, effective cast time
- Iron's `checkPreCastConditions` (passes pre-cast gate?)

**Outcomes:** OK, PRECAST_REFUSED, DISABLED, EXCEPTION, REFUSED, LIFECYCLE_COMPLETED, CANCELLED, TIMEOUT.

Report written to `<gamedir>/magicnpcs/audit-spells-<timestamp>.txt` and `.json`.

#### In-game Phase 3: CAST audit (disposable world only)

```
/magicnpcs audit spells [namespace] cast
```

Additionally runs each spell through a real `MobCastSession` on two spawned no-AI invulnerable zombie dummies with 2-tick resolved duration and 40-tick budget. Records mana and nearby-entity deltas, `NO_OBSERVABLE_EFFECT` hint.

**Warning:** Summons, projectiles, block changes all happen. Use a disposable world copy.

**What it proves:** The spell resolves, passes pre-cast, completes the Iron's lifecycle without exceptions. It does **not** prove the spell does anything useful — a mage tower that builds a solid block may complete successfully but block the mob permanently.

### Pattern-based attack-goal matching

The new `general.attackGoalNamePatterns` config (default: `Attack, Ranged, Shoot, Bow, Crossbow, Gun, Spit, Breath, Charge, Cast`) extends the exact-name list `general.suppressibleAttackGoals` for modded mobs. Patterns are:

- Case-sensitive regexes matched with `find()`
- Applied only to goals that are not target goals, are not Magic NPCs' own, and declare MOVE or LOOK
- Checked after exact names, so explicit wins

`/magicnpcs why` prints a goal table (sorted by priority then class name) with a `native-attack` column showing `exact`, `pattern:<regex>`, or `-`. Below the table, `candidate attack goals not matched: <names>` lists mismatchesand hints to add them.

### No shipped add-on manifests until audit proves support

Magic NPCs ships no hardcoded manifests for add-ons, even after auditing a popular pack. Reasons:

1. Audits prove lifecycle, not usefulness — a spell that builds its own cast data may complete but do nothing visible.
2. Each pack authors its own manifest or datapack author inherits the responsibility.
3. Operator has final say via config overrides.

The audit tooling exists to make the process cheap; the decision stays with the operator.

### One new FORGE-bus server-tick subscriber for the audit

`SpellAuditRun` (one run per server, op-only, one live subscription to `TickEvent.ServerTickEvent`) orchestrates the in-game audit. No per-addon Java, no mixins.

## Rejected alternatives

### Single operator config instead of manifests

**Rejected: put all spell statements in `spells.capabilityOverrides`.** Scales poorly for large add-on packs (70+ spells per addon) and is not shareable across servers.

**Chosen: datapack manifests primary, config secondary.** Manifests are authored once, shared in a datapack; config is for one-off fixes.

### Empirical proof via live mob testing only

**Rejected: require every add-on spell to be tested in-game against a real mob before enabling.** Requires time, a test world, and knowledge of the spell's behavior. The heuristic is cheaper for initial triage.

**Chosen: offline heuristic → in-game RESOLVE → optional in-game CAST.** Three phases of increasing cost. Heuristic catches most cases; RESOLVE catches pre-cast failures; CAST is opt-in for final verification.

### ADDON_DEFAULT semantic: full vs opportunistic target data

**Rejected: never supply target data for ADDON_DEFAULT spells.** Single-target add-ons do nothing.

**Rejected: always supply target data when castable.** Area spells that build their own target from cast data would get stale data, wrong target.

**Chosen: opportunistic — supply when a live target exists.** Single-target add-ons work; area spells that build their own data overwrite it. Manifests let pack authors opt into per-spell control.

### Audit output format

**Rejected: emit only a plain-text report.** Makes post-processing (grouping by outcome, per-namespace stats) hard.

**Chosen: JSON + human-readable text.** `.json` is machine-readable (sorting, filtering); `.txt` is human-readable. Both get written.

## Consequences

- Pack authors can share spells via datapack manifests without waiting for Magic NPCs or per-world config.
- Operators can fix one spell via config without a datapack.
- Weak namespace trust (`ADDON_DEFAULT`) makes single-target add-ons work zero-config.
- `/magicnpcs spells` and `/magicnpcs validate` show provenance, making the fix obvious.
- Offline audit tool is heuristic but free; in-game audit phases 2 and 3 prove lifecycle without modifying code.
- No shipped add-on manifests = no maintenance burden, no version creep, full operator control.
- Pattern-based attack-goal matching scales to modded mobs without listing every class.

## See also

- [ADR 0001](0001-irons-mob-casting.md) — Iron's mob casting foundations
- [ADR 0002](0002-casting-goal-injection.md) — goal injection and native-attack policies
- [ADR 0008](0008-cast-session-and-reconciliation.md) — cast session lifecycle
- [`docs/compat/irons-addons.md`](../compat/irons-addons.md) — user guide for add-on manifests and auditing
