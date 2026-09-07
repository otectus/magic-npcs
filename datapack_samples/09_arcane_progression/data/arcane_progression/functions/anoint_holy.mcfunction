# Give every nearby villager the holy school.
#
# /magicnpcs school set is a manual override: it outranks any datapack loadout
# for that NPC and survives chunk reloads.
#
# Only vanilla entity types are named here on purpose. A selector such as
# type=recruits:recruit is a parse error when that mod is absent, and one bad
# selector stops the whole function from loading - so keep modded ids in their
# own function file and call it conditionally.

execute as @e[type=minecraft:villager,distance=..16] run function arcane_progression:anoint_one_holy

tellraw @s {"text":"The nearby villagers join the Arcane Order.","color":"light_purple"}
