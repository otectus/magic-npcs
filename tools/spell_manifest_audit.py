#!/usr/bin/env python3
"""Offline spell-manifest audit for Iron's Spells 'n Spellbooks addon packs.

Purpose
-------
Magic NPCs only lets a mob cast a spell whose *capability* it knows: what the spell
reads out of its cast data, whether it refuses a non-player caster, and so on. For
``irons_spellbooks`` that table is checked in (``SpellManifest.java``); for the
seventeen-odd addon packs a real instance carries, nobody has one. Hand-classifying
378 spells is the mistake this script exists to avoid.

Given a mods folder, this script enumerates every registered-looking ``AbstractSpell``
subclass in every jar **without starting Minecraft** -- it reads class files with
``zipfile`` and a constant-pool parser, never bytecode, never a JVM -- resolves each
class to its ``ns:path`` spell id via the pack's own lang file, and drafts a per
namespace spell manifest with a heuristic capability and a confidence per row.

Everything it emits is a *draft for human review*. The heuristics see which classes a
spell class mentions, not what it does with them. ``summary.md`` reports the script's
exact-match rate against Iron's own checked-in table as the honesty metric.

Usage
-----
    python tools/spell_manifest_audit.py <mods_dir> --out <out_dir>
                                         [--irons <jar>] [--namespace ns ...] [--verbose]

``--irons`` defaults to the first jar in ``mods_dir`` whose ``META-INF/mods.toml``
declares ``modId="irons_spellbooks"``; the script exits non-zero without one.
``--namespace`` may be repeated and restricts which namespaces get output files (the
Iron's calibration always runs). Nothing is ever written outside ``--out``, and no jar
is ever opened for writing.

Outputs (under ``--out``)
-------------------------
``<ns>.manifest.json``
    The Magic NPCs spell-manifest format::

        {"format": 1,
         "verified_against": "heuristic draft ...; NOT runtime-verified; irons <version>",
         "spells": {"ns:path": "CAPABILITY", ...},
         "_review": ["ns:path (low: reason)", ...]}

    Keys beginning with ``_`` are ignored by the loader, so ``_review`` is a comment
    channel. Every resolved row appears in ``spells``; low-confidence and ambiguous
    rows are repeated under ``_review``, and rows whose id could not be resolved appear
    *only* under ``_review``, named by class.
``<ns>.audit.md``
    One table row per spell class, plus lang keys that matched no class and classes
    that matched no id.
``summary.md``
    Per-namespace counts, namespaces whose lang file yielded nothing, and the Iron's
    calibration (found, exact-match rate, confusion table).
"""

import argparse
import collections
import json
import pathlib
import re
import struct
import sys
import zipfile

# --- constants ---------------------------------------------------------------

ABSTRACT_SPELL = "io/redspace/ironsspellbooks/api/spells/AbstractSpell"
CAST_TYPE_OWNER = "io/redspace/ironsspellbooks/api/spells/CastType"
CAST_TYPE_NAMES = ("INSTANT", "LONG", "CONTINUOUS", "NONE")
IRONS_MODID = "irons_spellbooks"

ACC_ABSTRACT = 0x0400
ACC_INTERFACE = 0x0200

MANIFEST_FORMAT = 1

# Strings that show up in half the spell classes and identify nothing.
STOPLIST = {
    "none", "spell", "level", "cast", "spells", "name", "target", "entity", "player",
    "count", "damage", "radius", "range", "duration", "amount", "type", "id", "value",
    "school", "mana", "cooldown", "empty", "main", "self", "owner", "item", "block",
    "sound", "particle", "text", "guide", "true", "false", "null", "default", "tick",
    "ticks", "speed", "height", "width", "power", "size", "time", "data", "seconds",
    "summon", "heal", "fire", "ice", "blood", "holy", "ender", "evocation", "nature",
    "lightning", "abstract", "instant", "long", "continuous",
}

# Reference tokens that mean "this spell hurts or debuffs something".
COMBAT_TOKENS = (
    "DamageSources", "DamageSource", "MobEffectInstance", "MobEffect",
    "Projectile", "AbstractArrow", "Arrow", "Fireball",
)
COMBAT_MEMBERS = ("hurt", "addEffect")

# --- class file parsing ------------------------------------------------------


class ClassParseError(Exception):
    """Raised when a class file's header or constant pool cannot be read."""


class ClassInfo:
    """The parts of one class file this script reasons about."""

    __slots__ = ("name", "supername", "interfaces", "access", "jar", "entry",
                 "class_refs", "member_refs", "strings")

    def __init__(self, name, supername, interfaces, access, jar, entry):
        self.name = name
        self.supername = supername
        self.interfaces = interfaces
        self.access = access
        self.jar = jar
        self.entry = entry
        self.class_refs = frozenset()
        self.member_refs = frozenset()
        self.strings = frozenset()

    @property
    def is_abstract(self):
        """True when ACC_ABSTRACT or ACC_INTERFACE is set (never a registered spell)."""
        return bool(self.access & (ACC_ABSTRACT | ACC_INTERFACE))

    @property
    def simple_name(self):
        """The class's simple name, with any outer-class prefix stripped."""
        tail = self.name.rsplit("/", 1)[-1]
        return tail.rsplit("$", 1)[-1]


def _read_constant_pool(data):
    """Parse the constant pool, returning (raw_entries, offset_after_pool)."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ClassParseError("not a class file")
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    off = 10
    i = 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:  # Utf8
            length = struct.unpack_from(">H", data, off)[0]
            off += 2
            pool[i] = ("utf8", data[off:off + length].decode("utf-8", "replace"))
            off += length
        elif tag in (3, 4):  # Integer, Float
            off += 4
        elif tag in (5, 6):  # Long, Double -- these occupy two pool slots
            off += 8
            i += 1
        elif tag == 7:  # Class
            pool[i] = ("class", struct.unpack_from(">H", data, off)[0])
            off += 2
        elif tag == 8:  # String
            pool[i] = ("string", struct.unpack_from(">H", data, off)[0])
            off += 2
        elif tag in (9, 10, 11):  # Fieldref, Methodref, InterfaceMethodref
            owner, nat = struct.unpack_from(">HH", data, off)
            pool[i] = ("ref", owner, nat)
            off += 4
        elif tag == 12:  # NameAndType
            pool[i] = ("nat", struct.unpack_from(">H", data, off)[0])
            off += 4
        elif tag == 15:  # MethodHandle
            off += 3
        elif tag == 16:  # MethodType
            off += 2
        elif tag in (17, 18):  # Dynamic, InvokeDynamic
            off += 4
        elif tag in (19, 20):  # Module, Package
            off += 2
        else:
            raise ClassParseError("unknown constant pool tag %d" % tag)
        i += 1
    return pool, off


def _utf8(pool, index):
    """Resolve a Utf8 constant, or None when the index is not one."""
    if index is None or index <= 0 or index >= len(pool):
        return None
    slot = pool[index]
    if slot and slot[0] == "utf8":
        return slot[1]
    return None


def _class_name(pool, index):
    """Resolve a Class constant to its internal name."""
    if index is None or index <= 0 or index >= len(pool):
        return None
    slot = pool[index]
    if slot and slot[0] == "class":
        return _utf8(pool, slot[1])
    return None


def _fill_details(info, pool):
    """Collect the Class constants, member references and String constants."""
    class_refs = set()
    member_refs = set()
    strings = set()
    for slot in pool:
        if not slot:
            continue
        kind = slot[0]
        if kind == "class":
            value = _utf8(pool, slot[1])
            if value:
                class_refs.add(value)
        elif kind == "string":
            value = _utf8(pool, slot[1])
            if value is not None:
                strings.add(value)
        elif kind == "ref":
            owner = _class_name(pool, slot[1])
            nat = pool[slot[2]] if 0 < slot[2] < len(pool) else None
            member = _utf8(pool, nat[1]) if nat and nat[0] == "nat" else None
            if owner:
                class_refs.add(owner)
            if member:
                member_refs.add("%s#%s" % (owner or "?", member))
    info.class_refs = frozenset(class_refs)
    info.member_refs = frozenset(member_refs)
    info.strings = frozenset(strings)


def parse_class(data, jar, entry, want_details):
    """Parse one class file into a ClassInfo; raise ClassParseError on garbage."""
    pool, off = _read_constant_pool(data)
    if off + 6 > len(data):
        raise ClassParseError("truncated after constant pool")
    access, this_idx, super_idx = struct.unpack_from(">HHH", data, off)
    off += 6
    n_ifaces = struct.unpack_from(">H", data, off)[0]
    off += 2
    ifaces = []
    for _ in range(n_ifaces):
        if off + 2 > len(data):
            break
        ifaces.append(_class_name(pool, struct.unpack_from(">H", data, off)[0]))
        off += 2
    name = _class_name(pool, this_idx)
    if not name:
        raise ClassParseError("no this_class name")
    info = ClassInfo(name, _class_name(pool, super_idx), tuple(f for f in ifaces if f),
                     access, jar, entry)
    if want_details:
        _fill_details(info, pool)
    return info


# --- jar reading -------------------------------------------------------------

_MODS_BLOCK = re.compile(r"\[\[\s*mods\s*\]\]")
_MODID = re.compile(r"""^\s*modId\s*=\s*["']([^"']+)["']""", re.MULTILINE)
_VERSION = re.compile(r"""^\s*version\s*=\s*["']([^"']+)["']""", re.MULTILINE)
_LANG_KEY = re.compile(r"^spell\.([a-z0-9_.\-]+?)\.([a-z0-9_]+)(\.name)?$")


def read_mods_toml(zf):
    """Return [(modId, version), ...] declared by a jar's META-INF/mods.toml."""
    try:
        raw = zf.read("META-INF/mods.toml")
    except KeyError:
        return []
    text = raw.decode("utf-8", "replace")
    out = []
    for block in _MODS_BLOCK.split(text)[1:]:
        m = _MODID.search(block)
        if not m:
            continue
        v = _VERSION.search(block)
        out.append((m.group(1), v.group(1) if v else "?"))
    return out


def read_lang_spell_paths(zf, modids):
    """Map each declared modId to {spell path: lang key} from assets/<id>/lang/en_us.json."""
    found = {}
    for modid in modids:
        try:
            raw = zf.read("assets/%s/lang/en_us.json" % modid)
        except KeyError:
            continue
        try:
            obj = json.loads(raw.decode("utf-8-sig", "replace"))
        except (ValueError, UnicodeDecodeError):
            continue
        if not isinstance(obj, dict):
            continue
        paths = {}
        for key in obj:
            m = _LANG_KEY.match(key)
            if m and m.group(1) == modid:
                paths.setdefault(m.group(2), key)
        if paths:
            found[modid] = paths
    return found


class JarInfo:
    """One mod jar: its declared mod ids, its chosen namespace and its lang keys."""

    __slots__ = ("path", "mods", "namespace", "lang_paths", "lang_namespaces")

    def __init__(self, path):
        self.path = path
        self.mods = []
        self.namespace = None
        self.lang_paths = {}
        self.lang_namespaces = []


def scan_jars(mods_dir, verbose):
    """Open every jar once: mods.toml, lang keys, and a shallow index of all classes.

    Returns (jars, classes) where classes maps internal name -> ClassInfo. References
    and string constants are filled later, only for the spell classes.
    """
    jars = []
    classes = {}
    for jar_path in sorted(mods_dir.glob("*.jar")):
        try:
            zf = zipfile.ZipFile(jar_path)
        except (zipfile.BadZipFile, OSError) as exc:
            note(verbose, "skipping unreadable jar %s: %s" % (jar_path.name, exc))
            continue
        with zf:
            info = JarInfo(jar_path)
            info.mods = read_mods_toml(zf)
            modids = [m for m, _ in info.mods]
            info.lang_paths = read_lang_spell_paths(zf, modids)
            info.lang_namespaces = [m for m in modids if m in info.lang_paths]
            if info.lang_namespaces:
                info.namespace = info.lang_namespaces[0]
            elif modids:
                info.namespace = modids[0]
            else:
                info.namespace = jar_path.stem
                note(verbose, "%s has no mods.toml; namespace falls back to the file name"
                     % jar_path.name)
            jars.append(info)
            for entry in zf.namelist():
                if not entry.endswith(".class") or entry.startswith("META-INF/versions/"):
                    continue
                try:
                    ci = parse_class(zf.read(entry), info, entry, want_details=False)
                except (ClassParseError, struct.error, KeyError, IndexError,
                        zipfile.BadZipFile) as exc:
                    note(verbose, "skipping malformed class %s!%s: %s"
                         % (jar_path.name, entry, exc))
                    continue
                classes.setdefault(ci.name, ci)
    return jars, classes


def load_details(classes, wanted, verbose):
    """Re-read only the wanted classes, filling in references and string constants."""
    by_jar = collections.defaultdict(list)
    for name in wanted:
        ci = classes[name]
        by_jar[ci.jar.path].append(ci)
    for jar_path, infos in by_jar.items():
        try:
            zf = zipfile.ZipFile(jar_path)
        except (zipfile.BadZipFile, OSError) as exc:
            note(verbose, "cannot reopen %s: %s" % (jar_path.name, exc))
            continue
        with zf:
            for ci in infos:
                try:
                    pool, _ = _read_constant_pool(zf.read(ci.entry))
                    _fill_details(ci, pool)
                except (ClassParseError, struct.error, KeyError, IndexError,
                        zipfile.BadZipFile) as exc:
                    note(verbose, "cannot re-read %s: %s" % (ci.entry, exc))


# --- hierarchy ---------------------------------------------------------------


def transitive_subclasses(classes, root):
    """Every class transitively extending `root`, across all jars (root excluded)."""
    children = collections.defaultdict(list)
    for ci in classes.values():
        if ci.supername:
            children[ci.supername].append(ci.name)
    found = set()
    queue = list(children.get(root, ()))
    while queue:
        name = queue.pop()
        if name in found:
            continue
        found.add(name)
        queue.extend(children.get(name, ()))
    return found


def super_chain(classes, name):
    """The superclass chain above `name`, nearest first, stopping at an unknown class."""
    chain = []
    seen = set()
    cur = classes.get(name)
    while cur is not None and cur.supername and cur.supername not in seen:
        seen.add(cur.supername)
        nxt = classes.get(cur.supername)
        if nxt is None:
            break
        chain.append(nxt)
        cur = nxt
    return chain


# --- id resolution -----------------------------------------------------------

_CAMEL = re.compile(r"(?<!^)(?=[A-Z])")
_PATHISH = re.compile(r"^[a-z0-9_]+$")


def snake_of(simple_name):
    """'FireballSpell' -> 'fireball'; the shape most Iron's spell classes use."""
    base = simple_name
    for suffix in ("Spell", "SpellEntity"):
        if base.endswith(suffix) and len(base) > len(suffix):
            base = base[: -len(suffix)]
    return _CAMEL.sub("_", base).lower()


def _row(ci, ns, path, how, ambiguous):
    """Build the mutable audit row for one spell class."""
    return {
        "namespace": ns,
        "path": path,
        "class": ci.name.replace("/", "."),
        "info": ci,
        "id_source": how,
        "id_ambiguous": ambiguous,
        "id_unresolved": path == "?",
    }


def resolve_ids(spells, lang_index, verbose):
    """Assign each spell class a path: a lang-key match first, then a unique string.

    `lang_index` maps namespace -> {path: lang key}. A path is claimed by at most one
    class; classes left over fall through to the unique-string fallback and then to
    `path = "?"`.
    """
    occurrences = collections.Counter()
    for ci in spells:
        for s in ci.strings:
            if _PATHISH.match(s):
                occurrences[s] += 1

    claims = collections.defaultdict(list)
    candidates = {}
    for ci in spells:
        ns = ci.jar.namespace
        known = lang_index.get(ns, {})
        cands = sorted(s for s in ci.strings if _PATHISH.match(s) and s in known)
        candidates[ci.name] = cands
        for c in cands:
            claims[(ns, c)].append(ci.name)

    rows = []
    taken = set()
    deferred = []
    for ci in spells:
        ns = ci.jar.namespace
        cands = candidates[ci.name]
        if not cands:
            deferred.append(ci)
            continue
        exact = [c for c in cands if c == snake_of(ci.simple_name)]
        pool = exact or [c for c in cands if len(claims[(ns, c)]) == 1] or cands
        chosen = pool[0]
        if (ns, chosen) in taken:
            deferred.append(ci)
            continue
        taken.add((ns, chosen))
        rows.append(_row(ci, ns, chosen, "lang", len(cands) > 1 and not exact))

    for ci in deferred:
        ns = ci.jar.namespace
        known = lang_index.get(ns, {})
        free_lang = [c for c in candidates[ci.name] if (ns, c) not in taken]
        unique = sorted(
            s for s in ci.strings
            if occurrences.get(s) == 1 and s not in STOPLIST and len(s) > 2
            and _PATHISH.match(s) and (ns, s) not in taken
        )
        preferred = [s for s in free_lang + unique if s == snake_of(ci.simple_name)]
        pool = preferred or free_lang or unique
        if pool:
            chosen = pool[0]
            taken.add((ns, chosen))
            rows.append(_row(ci, ns, chosen, "lang" if chosen in known else "unique-string",
                             not preferred and len(pool) > 1))
        else:
            note(verbose, "no id for %s" % ci.name)
            rows.append(_row(ci, ns, "?", "unresolved", False))
    rows.sort(key=lambda r: (r["namespace"], r["path"], r["class"]))
    return rows


# --- cast type ---------------------------------------------------------------


def cast_type_of(ci, classes):
    """Read CastType from field references, inheriting up the superclass chain.

    Method bodies are not parsed, so a class naming more than one constant is reported
    as all of them and flagged ambiguous rather than guessed at.
    """
    for candidate in [ci] + super_chain(classes, ci.name):
        names = sorted(
            ref.split("#", 1)[1] for ref in candidate.member_refs
            if ref.startswith(CAST_TYPE_OWNER + "#")
            and ref.split("#", 1)[1] in CAST_TYPE_NAMES
        )
        if len(names) == 1:
            return names[0], False
        if len(names) > 1:
            return "|".join(names), True
    return "?", False


# --- capability heuristics ---------------------------------------------------


def classify(row, classes):
    """Heuristic capability for one spell row: (capability, confidence, evidence).

    Checks run in a fixed order and the first match wins. Everything here is inferred
    from which classes and members a spell mentions; no method body is read.

    ``RecastInstance``/``RecastResult`` were dropped from the SPECIAL_PREPARATION
    trigger after calibration: recasts are an ordinary Iron's mechanic, so the token
    fired on thirteen Iron's spells of which none is actually SPECIAL_PREPARATION
    (``eldritch_blast``, ``wall_of_fire``, the ``summon_*`` family). It cost eight of
    eighteen calibration errors on its own.
    """
    ci = row["info"]
    refs = set()
    members = set()
    for c in [ci] + super_chain(classes, ci.name):
        refs |= set(c.class_refs)
        members |= {r.split("#", 1)[1] for r in c.member_refs if "#" in r}

    def has(*needles):
        return [n for n in needles if any(n in r for r in refs)]

    hit = has("MultiTargetEntityCastData")
    if hit:
        return "MULTI_TARGET", "high", hit
    hit = has("TargetEntityCastData")
    if hit:
        return "TARGET_ENTITY", "medium", hit
    hit = has("TargetAreaCastData")
    if hit:
        return "TARGET_AREA", "medium", hit
    hit = has("TeleportData", "IMagicEntity", "AbstractSpellCastingMob")
    if hit:
        return "SPECIAL_PREPARATION", "medium", hit
    hit = has("SummonedEntity", "OwnerHelper", "MagicSummon")
    if hit or "summon" in row["path"] or "summon" in ci.simple_name.lower():
        return "SUMMON", "medium", hit or ["name contains 'summon'"]

    combat = has(*COMBAT_TOKENS) + [m for m in COMBAT_MEMBERS if m in members]
    player = has("net/minecraft/server/level/ServerPlayer",
                 "net/minecraft/world/entity/player/Player")
    if player and not combat:
        return "PLAYER_ONLY", "low", player
    world = has("net/minecraft/world/level/block/state/BlockState")
    if "setBlock" in members:
        world.append("setBlock")
    if world and not combat:
        return "UTILITY_NON_COMBAT", "low", world
    return "DIRECT", "low", combat[:3] or ["no cast-data reference"]


# --- Iron's built-in table (calibration) -------------------------------------

# Matched loosely: a quoted path followed by a capability name, in any row syntax.
_BUILTIN_ROW = re.compile(
    r'"([a-z0-9_]+)"\s*,\s*(?:[A-Za-z]+\.)?'
    r'(DIRECT|TARGET_ENTITY|TARGET_AREA|GROUND_AOE_FORWARD|SUMMON|MULTI_TARGET|'
    r'SPECIAL_PREPARATION|PLAYER_ONLY|UTILITY_NON_COMBAT|ADDON_DEFAULT|UNVERIFIED)\b'
)


def read_builtin_table(java_file):
    """Parse SpellManifest.java's checked-in path -> capability rows."""
    try:
        text = java_file.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {}
    return {m.group(1): m.group(2) for m in _BUILTIN_ROW.finditer(text)}


def calibrate(irons_rows, builtin):
    """Compare the heuristic against SpellManifest.java: (compared, matched, confusion)."""
    confusion = collections.Counter()
    matched = 0
    compared = 0
    for row in irons_rows:
        actual = builtin.get(row["path"])
        if actual is None:
            continue
        compared += 1
        if actual == row["capability"]:
            matched += 1
        else:
            confusion[(row["capability"], actual)] += 1
    return compared, matched, confusion


# --- reporting ---------------------------------------------------------------


def note(verbose, message):
    """Print a diagnostic to stderr only under --verbose."""
    if verbose:
        print("  note: %s" % message, file=sys.stderr)


def review_line(row):
    """The `_review` string for a row a human should look at."""
    reasons = []
    if row["id_unresolved"]:
        reasons.append("id unresolved; class %s" % row["class"])
    if row["id_ambiguous"]:
        reasons.append("id ambiguous (%s)" % row["id_source"])
    if row["cast_type_ambiguous"]:
        reasons.append("cast type ambiguous: %s" % row["cast_type"])
    if row["confidence"] != "high":
        reasons.append("%s: %s" % (row["confidence"], ", ".join(row["evidence"])))
    return "%s:%s (%s)" % (row["namespace"], row["path"], "; ".join(reasons) or "review")


def write_manifest(out_dir, ns, rows, irons_version):
    """Write <ns>.manifest.json: every resolved row, plus a _review comment channel."""
    spells = {}
    review = []
    for row in rows:
        if row["id_unresolved"]:
            review.append(review_line(row))
            continue
        spells["%s:%s" % (ns, row["path"])] = row["capability"]
        if row["confidence"] != "high" or row["id_ambiguous"] or row["cast_type_ambiguous"]:
            review.append(review_line(row))
    doc = collections.OrderedDict()
    doc["format"] = MANIFEST_FORMAT
    doc["verified_against"] = (
        "heuristic draft by spell_manifest_audit.py; NOT runtime-verified; irons %s"
        % irons_version
    )
    doc["spells"] = collections.OrderedDict(sorted(spells.items()))
    doc["_review"] = review
    (out_dir / ("%s.manifest.json" % ns)).write_text(
        json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    return len(spells), len(review)


def write_audit(out_dir, ns, rows, lang_paths):
    """Write <ns>.audit.md: the per-class table plus the two 'did not match' lists."""
    lines = ["# %s spell audit" % ns, "",
             "Heuristic draft, not runtime-verified. Evidence is the reference that fired.",
             "",
             "| id | class | cast type | capability | confidence | evidence |",
             "|---|---|---|---|---|---|"]
    matched = set()
    for row in rows:
        if row["id_unresolved"]:
            ident = "-"
        else:
            ident = "%s:%s" % (ns, row["path"])
            matched.add(row["path"])
        lines.append("| %s | %s | %s | %s | %s | %s |" % (
            ident, row["class"], row["cast_type"], row["capability"], row["confidence"],
            ", ".join(row["evidence"])[:120] or "-"))
    orphans = sorted(p for p in lang_paths if p not in matched)
    unresolved = [r for r in rows if r["id_unresolved"]]
    lines += ["", "## Lang keys without a class", ""]
    lines += ["- `%s` (`%s`)" % (p, lang_paths[p]) for p in orphans] or ["_none_"]
    lines += ["", "## Classes without an id", ""]
    lines += ["- `%s`" % r["class"] for r in unresolved] or ["_none_"]
    lines.append("")
    (out_dir / ("%s.audit.md" % ns)).write_text("\n".join(lines), encoding="utf-8")
    return len(orphans), len(unresolved)


def write_summary(out_dir, per_ns, langless, irons_version, calib, builtin_size,
                  irons_found, mods_dir):
    """Write summary.md: per-namespace counts, lang-less namespaces, calibration."""
    compared, matched, confusion = calib
    rate = (100.0 * matched / compared) if compared else 0.0
    lines = [
        "# Spell manifest audit summary", "",
        "Source: `%s`" % mods_dir, "",
        "Generated by `tools/spell_manifest_audit.py` from jar contents only -- no game was",
        "run. Every capability below is a heuristic guess; confirm it with",
        "`/magicnpcs audit spells <namespace>` before trusting a manifest.", "",
        "Iron's Spells 'n Spellbooks version: `%s`" % irons_version, "",
        "## Per-namespace counts", "",
        "| namespace | classes | resolved ids | unresolved | by capability | by confidence |",
        "|---|---|---|---|---|---|",
    ]
    for ns in sorted(per_ns):
        s = per_ns[ns]
        caps = ", ".join("%s %d" % (k, v) for k, v in sorted(s["caps"].items()))
        conf = ", ".join("%s %d" % (k, v) for k, v in sorted(s["conf"].items()))
        lines.append("| %s | %d | %d | %d | %s | %s |"
                     % (ns, s["classes"], s["resolved"], s["unresolved"], caps, conf))
    lines += ["", "## Namespaces whose lang file yielded no spell keys", ""]
    if langless:
        lines += ["- `%s` -- ids only discoverable at runtime; use `/magicnpcs spells`" % n
                  for n in sorted(langless)]
    else:
        lines.append("_none_")
    lines += [
        "", "## Calibration against Iron's own table", "",
        "The same heuristics were run over `%s` and compared with the checked-in table in"
        % IRONS_MODID,
        "`src/main/java/com/otectus/magicnpcs/integration/irons/SpellManifest.java`.", "",
        "- spell classes found in Iron's: **%d**" % irons_found,
        "- rows in the checked-in table: **%d**" % builtin_size,
        "- ids resolved and comparable: **%d**" % compared,
        "- exact matches: **%d** (**%.1f%%**)" % (matched, rate),
        "", "### Confusion (heuristic -> actual)", "",
        "| heuristic | actual | count |", "|---|---|---|",
    ]
    if confusion:
        for (guess, actual), n in confusion.most_common():
            lines.append("| %s | %s | %d |" % (guess, actual, n))
    else:
        lines.append("| _none_ | | |")
    lines.append("")
    (out_dir / "summary.md").write_text("\n".join(lines), encoding="utf-8")


# --- main --------------------------------------------------------------------


def find_irons_jar(jars):
    """The first scanned jar declaring modId=irons_spellbooks, or None."""
    for jar in jars:
        if any(m == IRONS_MODID for m, _ in jar.mods):
            return jar
    return None


def irons_version_of(jar):
    """The Iron's version from mods.toml, falling back to the jar file name."""
    for modid, version in jar.mods:
        if modid == IRONS_MODID and version and "${" not in version:
            return version
    m = re.search(r"(\d[\w.\-]*)$", jar.path.stem)
    return m.group(1) if m else jar.path.stem


def build_parser():
    """Build the command-line parser."""
    p = argparse.ArgumentParser(
        description="Draft Magic NPCs spell manifests from Iron's addon jars, offline.")
    p.add_argument("mods_dir", help="folder containing the mod jars")
    p.add_argument("--out", required=True, help="output folder (created if missing)")
    p.add_argument("--irons", help="path to the Iron's jar (default: auto-detected)")
    p.add_argument("--namespace", action="append", default=[],
                   help="restrict output to this namespace (repeatable)")
    p.add_argument("--verbose", action="store_true",
                   help="report skipped classes and unresolved ids")
    return p


def main(argv=None):
    """Scan the mods folder and write the manifests, audits and summary."""
    args = build_parser().parse_args(argv)
    mods_dir = pathlib.Path(args.mods_dir)
    if not mods_dir.is_dir():
        print("error: %s is not a directory" % mods_dir, file=sys.stderr)
        return 2
    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    jars, classes = scan_jars(mods_dir, args.verbose)
    print("scanned %d jars, %d classes" % (len(jars), len(classes)))

    if args.irons:
        wanted = pathlib.Path(args.irons).resolve()
        irons_jar = next((j for j in jars if j.path.resolve() == wanted), None)
        if irons_jar is None:
            print("error: --irons %s was not found among the scanned jars" % args.irons,
                  file=sys.stderr)
            return 2
    else:
        irons_jar = find_irons_jar(jars)
    if irons_jar is None:
        print('error: no jar in %s declares modId="%s"; pass --irons <jar>'
              % (mods_dir, IRONS_MODID), file=sys.stderr)
        return 2
    irons_version = irons_version_of(irons_jar)

    spell_names = transitive_subclasses(classes, ABSTRACT_SPELL)
    if not spell_names:
        print("error: no subclasses of %s found; is %s the right Iron's jar?"
              % (ABSTRACT_SPELL, irons_jar.path.name), file=sys.stderr)
        return 2
    load_details(classes, spell_names, args.verbose)
    spells = [classes[n] for n in sorted(spell_names) if not classes[n].is_abstract]
    print("found %d concrete spell classes (%d in the hierarchy)"
          % (len(spells), len(spell_names)))

    lang_index = {}
    for jar in jars:
        for ns, paths in jar.lang_paths.items():
            lang_index.setdefault(ns, {}).update(paths)

    rows = resolve_ids(spells, lang_index, args.verbose)
    for row in rows:
        row["cast_type"], row["cast_type_ambiguous"] = cast_type_of(row["info"], classes)
        row["capability"], row["confidence"], row["evidence"] = classify(row, classes)

    by_ns = collections.defaultdict(list)
    for row in rows:
        by_ns[row["namespace"]].append(row)

    selected = set(args.namespace) if args.namespace else None
    per_ns = {}
    langless = set()
    for ns, ns_rows in sorted(by_ns.items()):
        per_ns[ns] = {
            "classes": len(ns_rows),
            "resolved": sum(1 for r in ns_rows if not r["id_unresolved"]),
            "unresolved": sum(1 for r in ns_rows if r["id_unresolved"]),
            "caps": collections.Counter(r["capability"] for r in ns_rows),
            "conf": collections.Counter(r["confidence"] for r in ns_rows),
        }
        if not lang_index.get(ns):
            langless.add(ns)
        if ns == IRONS_MODID:
            continue  # Iron's has a checked-in table; it is calibration input, not output.
        if selected is not None and ns not in selected:
            continue
        n_spells, n_review = write_manifest(out_dir, ns, ns_rows, irons_version)
        write_audit(out_dir, ns, ns_rows, lang_index.get(ns, {}))
        print("  %-24s %3d classes  %3d ids  %3d review"
              % (ns, len(ns_rows), n_spells, n_review))

    builtin = read_builtin_table(
        pathlib.Path(__file__).resolve().parent.parent
        / "src" / "main" / "java" / "com" / "otectus" / "magicnpcs" / "integration"
        / "irons" / "SpellManifest.java")
    irons_rows = by_ns.get(IRONS_MODID, [])
    calib = calibrate(irons_rows, builtin)
    write_summary(out_dir, per_ns, langless, irons_version, calib, len(builtin),
                  len(irons_rows), mods_dir)
    compared, matched, _ = calib
    print("calibration: %d/%d Iron's rows matched (%.1f%%)"
          % (matched, compared, 100.0 * matched / compared if compared else 0.0))
    print("wrote %s" % out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
