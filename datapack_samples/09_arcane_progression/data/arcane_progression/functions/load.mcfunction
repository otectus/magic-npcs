# Runs on world load and on every /reload.
# Magic NPCs re-evaluates every loaded mob on /reload, so a mob that was standing
# there before this pack was added becomes a caster without needing to be respawned.
# Re-adding an existing objective or team is a harmless no-op.

scoreboard objectives add arcane_rank dummy {"text":"Arcane Rank"}
team add arcane_order
team modify arcane_order color light_purple

tellraw @a {"text":"[Arcane Progression] loaded. Run /magicnpcs validate to check the loadouts.","color":"dark_purple"}
