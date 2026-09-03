# CustomNPCs: targeting and control

CustomNPCs NPCs can cast spells and respond to script triggers, dialog actions, and loadout conditions. This guide covers authoring loadouts and scripts for them.

## Targeting a CustomNPC in a datapack

An NPC becomes a caster when a **loadout** exists for its entity type. To target CustomNPCs, use `"entity_type": "customnpcs:customnpc"` in the loadout's root:

```json
{
  "entity_type": "customnpcs:customnpc",
  "max_mana": 110,
  "mana_regen": 9,
  "spells": [
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "role": "attack" }
  ]
}
```

Every CustomNPC is authored deliberately — given its own dialog, job, role, and faction — so Magic NPCs respects those choices. Enable the integration with `compat.customnpcs = true` in `config/magicnpcs-common.toml`, and then `customnpcs.bridgeEnabled` in the server config (both default **on**). An NPC in a blocked job or role never casts; see [Configuration](#configuration) below.

## Conditional casting: npc_traits

The `npc_traits` condition gates a loadout on an NPC's authored properties — its role, job, retaliation AI mode, movement mode, navigation mode, or faction. Used under `conditions.npc_traits`, it works the same way as world conditions like `dimensions` or `biomes`:

```json
{
  "entity_type": "customnpcs:customnpc",
  "conditions": {
    "npc_traits": {
      "any_of": [
        "customnpcs:job/guard",
        "customnpcs:job/healer"
      ]
    }
  },
  "max_mana": 110,
  "mana_regen": 9,
  "spells": [ { "spell": "irons_spellbooks:magic_missile", "level": 1, "role": "attack" } ]
}
```

All fields under `npc_traits` are optional; a missing block means no constraint. Nested arrays:

- **`all_of`** — the NPC must have all these traits (logical AND)
- **`any_of`** — the NPC must have at least one (logical OR)
- **`none_of`** — the NPC must have none of these (logical NOT)

### Trait namespaces

Traits are namespaced resource ids:

| Trait type | Namespace | Examples | Meaning |
|---|---|---|---|
| Role | `customnpcs:role/<name>` | `customnpcs:role/trader`, `customnpcs:role/follower`, `customnpcs:role/companion` | The NPC's authored social role |
| Job | `customnpcs:job/<name>` | `customnpcs:job/guard`, `customnpcs:job/healer`, `customnpcs:job/bard` | Its job or profession |
| Retaliation | `customnpcs:retaliate/<mode>` | `customnpcs:retaliate/fight`, `customnpcs:retaliate/panic` | How it responds to attack |
| Movement | `customnpcs:moving/<mode>` | `customnpcs:moving/stationary`, `customnpcs:moving/wander` | Whether it roams or stays put |
| Navigation | `customnpcs:navigation/<type>` | `customnpcs:navigation/ground`, `customnpcs:navigation/flying` | How it travels |
| Faction | `customnpcs:faction/<id>` | `customnpcs:faction/0`, `customnpcs:faction/1` | Its CustomNPCs faction id (as a string) |

The CustomNPCs example loadout ships disabled; copy it from `data/magicnpcs/spellcasters/customnpcs_example.json` into your pack, enable it, and edit the trait names to match your NPCs.

## How author rules affect casting

| Author rule | Effect |
|---|---|
| **Job in `blockedJobs`** | NPC never casts (config default: `puppet`, `builder`) |
| **Role in `blockedRoles`** | NPC never casts (config default: empty) |
| **In conversation** | If `pauseDuringDialog = true`, the NPC cannot cast while a dialog is open |
| **In a blocked faction** | If `respectFactions = true`, the NPC never casts at entities it is not hostile to |
| **Owned by a player** | If `protectOwners = true` (global), the NPC never casts at its owner |
| **Movement mode** | Affects repositioning behaviour (see below) |

### Movement policy mapping

When a loadout has `"native_attack": "suppress"` (a pure caster), the NPC may reposition to a range its own spells are eligible at. This reposition respects the NPC's authored **movement mode**:

| CustomNPCs movement type | Casting behaviour | Reason |
|---|---|---|
| **Stationary** | `PINNED` — does not move at all | NPC is pinned to its current location |
| **Wander** | `ANCHORED` at home, within its wander range | NPC may move to cast but stays near home |
| **Path** | `PINNED` — stays on the authored path | NPC marches a fixed route and does not deviate |

The reposition range comes from the loadout's own `min_range` / `max_range`, so there is one place a pack states where a caster fights and no way for two settings to disagree.

## Scripting: the MagicNPCs bridge

When `customnpcs.scriptGlobalEnabled = true` (default), CustomNPCs scripts see a global object named `MagicNPCs` that exposes Magic NPCs state and controls.

### Trigger events

Magic NPCs fires a CustomNPCs script trigger for six event types: `cast_pre` (before a cast, veto-able), `cast_started` (mana accepted, cooldown running), `cast_completed` (cast ran to end), `cast_cancelled` (cast interrupted or vetoed), `cast_failed` (could not start), and `school_changed` (school assignment changed). The trigger id is `customnpcs.scriptTriggerId` (default `8800`). The arguments passed to your trigger handler are positional:

```npc-script
onTrigger(int triggerId) {
    if (triggerId == 8800) {
        // Argument order: npc (implicit, usually 'this'), protocol, signalName, spell, level, 
        // target, source, reason, old_school, new_school, mode
        // Example: onTrigger(8800, this, "magicnpcs:v1", "cast_started", "irons_spellbooks:magic_missile", "1", "", "AI", "", "", "", "")
    }
}
```

The trigger is fired once per event (cast start/complete/cancel, school change), not once per NPC.

### Mailbox request/response channel

When `customnpcs.scriptMailboxEnabled = true` (default), scripts can query and mutate an NPC's casting state through a request/response mailbox stored in the NPC's persistent data. A script writes a request with operation and arguments, and finds the result on the next update tick.

**Request format** (write all of these before the next tick):
- `magicnpcs.request.v1.op` — the operation name (e.g., `"isCaster"`, `"setSchool"`, `"cast"`)
- `magicnpcs.request.v1.seq` — optional sequence number, echoed back in the result so you can match request to response
- `magicnpcs.request.v1.arg.<name>` — arguments (e.g., `magicnpcs.request.v1.arg.school`, `magicnpcs.request.v1.arg.spell`)

**Response format** (written by Magic NPCs on the same tick):
- `magicnpcs.result.v1.code` — numeric result code (see Result codes table below; `0` = OK)
- `magicnpcs.result.v1.message` — human-readable message
- `magicnpcs.result.v1.value` — the result value. For read operations: the queried value (string/number). For mutations: `setSchool` returns the school id string; `clearSchool`, `returnToAuto`, `cast` return `1` (true); `setCastingSuspended` returns `0` or `1` (the new suspended state)
- `magicnpcs.result.v1.seq` — echoes your request's sequence number

Example: check if an NPC can cast a spell:

```npc-script
var data = npc.getStorage();
// Write request
data.set("magicnpcs.request.v1.op", "canCast");
data.set("magicnpcs.request.v1.seq", 1);
data.set("magicnpcs.request.v1.arg.spell", "irons_spellbooks:magic_missile");
data.set("magicnpcs.request.v1.arg.level", 1);

// Wait one tick (happens automatically in the next onUpdate, event, or trigger)
// Then read result:
if (data.has("magicnpcs.result.v1.code")) {
    var code = data.getInteger("magicnpcs.result.v1.code");
    var message = data.getText("magicnpcs.result.v1.message");
    var canCast = data.getBoolean("magicnpcs.result.v1.value");
    // Clean up
    data.remove("magicnpcs.request.v1.op");
    data.remove("magicnpcs.request.v1.seq");
    data.remove("magicnpcs.request.v1.arg.spell");
    data.remove("magicnpcs.request.v1.arg.level");
}
```

### Cast cancellation handshake

When `customnpcs.scriptCancelHandshakeEnabled = true` (default), a script can veto a cast that is about to start. When the `cast_pre` trigger fires, write the value `1` (as number or string) to the NPC's temp data at key `magicnpcs.cancel.v1` to cancel the cast:

```npc-script
onTrigger(int triggerId) {
    if (triggerId == 8800) {
        // Trigger arguments are: npc, protocol, signalName, spell, ...
        // For a cast_pre signal, signalName will be "cast_pre"
        // To veto it:
        npc.getTempdata().set("magicnpcs.cancel.v1", 1);
    }
}
```

The handshake value must be the number `1` or the string `"1"`. Any other value (including `true` or `"true"`) will not cancel. The key is cleaned up automatically after the handshake, so a stale veto cannot carry over to the next cast.

### MagicNPCs script global

These methods are available inside a CustomNPCs script when the bridge is active. All operations that mutate an NPC's state require `customnpcs.scriptMutationsEnabled = true`.

#### Reads

All read operations return a `Result` object:

```npc-script
Result r = MagicNPCs.isCaster(npc);
if (r.isOk()) {
    boolean isCaster = r.getValue();
    // ...
}
```

Check `r.isOk()` or inspect `r.getCode()` and `r.getMessage()` on failure.

**`isCaster(IEntity npc)`** — whether this NPC has casting set up
- Returns: `boolean`
- Result codes: `OK`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `BRIDGE_INACTIVE`

**`getSchool(IEntity npc)`** — the NPC's current magic school, if assigned
- Returns: `String` (school id like `irons_spellbooks:fire`) or `null`
- Result codes: `OK`, `NOT_CASTER`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `BRIDGE_INACTIVE`

**`getLoadout(IEntity npc)`** — the name of the loadout the NPC is running
- Returns: `String` (resource id)
- Result codes: `OK`, `NOT_CASTER`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `BRIDGE_INACTIVE`

**`getMana(IEntity npc)`** — current mana
- Returns: `Number` (int)
- Result codes: `OK`, `NOT_CASTER`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `BRIDGE_INACTIVE`

**`getMaxMana(IEntity npc)`** — max mana
- Returns: `Number` (int)
- Result codes: `OK`, `NOT_CASTER`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `BRIDGE_INACTIVE`

**`canCast(IEntity npc, String spell, double level)`** — whether the NPC can cast this spell right now
- Returns: `boolean`
- Result codes: `OK`, `NOT_CASTER`, `SPELL_NOT_ALLOWED`, `ON_COOLDOWN`, `NO_MANA`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `BRIDGE_INACTIVE`

**`why(IEntity npc)`** — a diagnostic report on the NPC's casting state
- Returns: `String` (formatted multi-line text)
- Result codes: `OK`, `NOT_CASTER`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `BRIDGE_INACTIVE`

#### Mutations (requires `scriptMutationsEnabled = true`)

**`setSchool(IEntity npc, String school)`** — assign a magic school
- Arguments: school id like `irons_spellbooks:fire`
- Result codes: `OK`, `SCHOOL_NOT_ALLOWED`, `NOT_CASTER`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `MUTATIONS_DISABLED`, `BRIDGE_INACTIVE`, `INVALID_ARGUMENT`

**`clearSchool(IEntity npc)`** — remove a manual school assignment
- Result codes: `OK`, `NOT_CASTER`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `MUTATIONS_DISABLED`, `BRIDGE_INACTIVE`

**`returnToAuto(IEntity npc)`** — return an NPC to automatic school assignment
- Result codes: `OK`, `NOT_CASTER`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `MUTATIONS_DISABLED`, `BRIDGE_INACTIVE`

**`setCastingSuspended(IEntity npc, boolean suspended)`** — pause or resume casting
- Returns: the new state
- Result codes: `OK`, `NOT_CASTER`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `MUTATIONS_DISABLED`, `BRIDGE_INACTIVE`

**`cast(IEntity npc, String spell, double level, IEntity target)`** — ask the NPC to cast a spell
- Arguments:
  - `spell`: resource id (e.g., `irons_spellbooks:magic_missile`)
  - `level`: cast level (clamped by the spell's own min/max)
  - `target`: the entity to cast at, or `null` for a self-cast
- Applies all the same gates as an AI-selected cast: spell allow-list, mob castability, mana check, cooldown, target validation
- Result codes: `OK`, `SPELL_NOT_ALLOWED`, `NOT_CASTER`, `ON_COOLDOWN`, `NO_MANA`, `NO_TARGET`, `FRIENDLY_TARGET`, `NOT_CUSTOMNPC`, `ENTITY_GONE`, `MUTATIONS_DISABLED`, `BRIDGE_INACTIVE`, `INVALID_ARGUMENT`

## Configuration

Per-world settings in `magicnpcs-server.toml`:

| Setting | Default | Meaning |
|---|---|---|
| `[customnpcs]` `repairAfterAiRebuild` | `true` | Re-install casting goals after CustomNPCs rebuilds an NPC's AI (required for continuous casting) |
| `[customnpcs]` `respectFactions` | `true` | Route target selection through CustomNPCs faction rules so an NPC never casts at something it is not hostile to |
| `[customnpcs]` `pauseDuringDialog` | `true` | Suppress casting while an NPC has a dialog open with a player |
| `[customnpcs]` `blockedJobs` | `["puppet", "builder"]` | Job names whose NPCs never cast (lower-case) |
| `[customnpcs]` `blockedRoles` | `[]` | Role names whose NPCs never cast, lower-case (empty by default: roles describe what an NPC offers, not how it fights) |
| `[customnpcs]` `emitScriptTriggers` | `true` | Fire a CustomNPCs script trigger on cast events |
| `[customnpcs]` `scriptTriggerId` | `8800` | The trigger id Magic NPCs uses (change only if another add-on uses this number) |
| `[customnpcs]` `scriptMailboxEnabled` | `true` | Write cast events to the NPC's stored data for scripts to read |
| `[customnpcs]` `scriptMutationsEnabled` | `true` | Allow scripts to change an NPC's school assignment and request casts |
| `[customnpcs]` `scriptCancelHandshakeEnabled` | `true` | Let scripts veto a cast in the `cast_pre` event via a temp-data flag |

Installation-level settings in `config/magicnpcs-common.toml`:

| Setting | Default | Meaning |
|---|---|---|
| `[compat]` `customnpcs` | `false` | Allow datapack loadouts targeting `customnpcs:` entity types |
| `[customnpcs]` `bridgeEnabled` | `true` | Master switch for the CustomNPCs bridge (adapter, AI repair, script surface) |
| `[customnpcs]` `scriptGlobalEnabled` | `true` | Expose the Magic NPCs script surface at all |

Magic schools for CustomNPCs (under `[schools.customnpcs]`):

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Assign schools to CustomNPCs NPCs automatically (off by default: every NPC is authored deliberately) |
| `casterChance` | `0.25` | Chance [0..1] an NPC becomes a school caster (rolled once, persisted) |
| `assignmentMode` | `RANDOM` | School assignment: `RANDOM` (from `allowedSchools`), `BY_TYPE` (read from `typeSchools` map), `BY_RANK` |
| `typeSchools` | `[]` | `BY_TYPE` map: `"entityType=school[,school]"`, e.g. `"customnpcs:customnpc=irons_spellbooks:fire"` |
| `minLevelToCast` | `0` | Minimum level an NPC must reach (CustomNPCs has no progression, so every NPC reports level 0) |
