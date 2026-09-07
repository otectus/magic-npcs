# The School Tome is the mod one item. Right-click an NPC with it to inspect which
# school it has and which source is driving it; sneak-right-click to cycle schools.
# Cycling past the last school clears the assignment, so stop casting stays reachable
# from the item. Toggle the whole behaviour with schools.control.itemEnabled.

give @s magicnpcs:school_tome 1
tellraw @s {"text":"Right-click an NPC to inspect it, sneak-right-click to cycle its school.","color":"gray"}
