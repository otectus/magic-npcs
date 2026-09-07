# Undo an assignment made by this pack.
#
#   clear  - marks the NPC a sticky non-caster and removes the casting goal
#   auto   - hands the NPC back to automatic assignment, so its datapack
#            loadout or spawn roll applies again
#
# Use auto when you want the loadout back; use clear when you want silence.

magicnpcs school auto @e[team=arcane_order,distance=..16]
team leave @e[team=arcane_order,distance=..16]
scoreboard players reset @e[distance=..16] arcane_rank
