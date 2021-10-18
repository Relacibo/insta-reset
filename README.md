# InstaReset
Fabric mod for really fast single instance resetting in Minecraft: Java Edition Speedrunning.

Replaces [fast-reset](https://github.com/jan-leila/FastReset) and [Auto-Reset-Mod](https://github.com/DuncanRuns/AutoResetMod) and uses some code of fast-reset for optionally flushing the Minecraft Server. Instead of one integrated server loaded at a time, with this mod multiple servers will be loaded concurrently, which will speed up world resets significantly.

## Installation
Install [fabric](https://fabricmc.net/). Move the jar file into the minecraft mods folder (%Appdata%\.minecraft\mods). If present, remove Fast-Reset and AutoResetMod!

## Configuration
When the mod first is executed, a config file *insta-reset.json* will be created. There are the following options:
* `difficulty` (default: EASY): HARDCORE currently not supported.
* `reset_counter` (default: 0): Number of past resets. Increments as you reset with this mod.
* `number_of_pregenerating_levels` (default: 1, min: 1): Number of levels generating in the background (Number does not include the server the client is currently connected to. So if `number_of_pregenerating_levels` is 1, there are actually two servers concurrently running.) .
