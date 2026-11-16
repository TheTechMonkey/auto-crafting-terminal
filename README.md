# Auto Crafting Terminal

A Tom's Simple Storage add-on for Fabric, Minecraft 26.3. It's a terminal that crafts one recipe you pick, over and over, straight out of your storage network, and puts the results back.

## Using it

1. Place the terminal on an Inventory Connector or an inventory cable, the same way as Tom's other terminals.
2. Open it and pick a recipe from the list on the left. You can search by name.
3. It takes one craft's ingredients from storage, crafts, and puts the result (plus any empty buckets or bottles) back into storage. Then it repeats.

A craft is all or nothing. If storage is missing an ingredient or is full, nothing is taken.

The screen shows the recipe as pictures. Positions turn red when storage runs out of that item. Hover a position to see how many are in storage. The status line says what the terminal is doing.

## Redstone

- **Signal:** a redstone signal pauses the terminal, the same way it locks a hopper. A lever works as an off switch.
- **Fuel:** the terminal takes one redstone dust from storage for every 64 crafts.

## Settings

The settings are in `config/auto_crafting_table.json`, or in Mod Menu if you have it installed. On a server, the server's copy of the file is the one that counts.

| Setting | Default | What it does |
|---|---|---|
| `redstoneControl` | true | A redstone signal pauses the terminal. |
| `requireFuel` | true | Uses one redstone dust from storage per 64 crafts. |
| `mixedTypes` | true | Any item the recipe accepts works, such as any planks. When this is off, each position uses the shown type; right-click a position to change it. |

## Recipe

Shapeless: Tom's Crafting Terminal + Crafter + Redstone Dust.

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5 or newer
- Fabric API
- Tom's Simple Storage

## Building

Copy `toms_storage_fabric-26.3-2.12.2.jar` into a `libs` folder in this project, then run `gradlew build engineTest`. The engine test crafts real recipes against an in-memory storage.

## License

MIT
