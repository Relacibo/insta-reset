# InstaReset
Fabric mod for really fast single instance resetting in Minecraft: Java Edition Speedrunning. 

Replaces [fast-reset](https://github.com/jan-leila/FastReset) and [Auto-Reset-Mod](https://github.com/DuncanRuns/AutoResetMod) and uses some code of fast-reset for skip saving the Seed. Instead of one integrated server loaded at a time, with this mod multiple servers will be loaded concurrently, which will speed up world resets significantly.

## Installation
Install [fabric](https://fabricmc.net/). Move the jar file into the minecraft mods folder (`%Appdata%\.minecraft\mods`). If present, **remove Fast-Reset and AutoResetMod!**

## Configuration
When the mod first is executed, a config file *insta-reset.json* will be created. There are the following options:
* `difficulty` (default: EASY): HARDCORE currently not supported.
* `reset_counter` (default: 0): Number of past resets. Increments as you reset with this mod.
* `number_of_pregen_levels` (default: 2, min: 1): Number of ServerWorlds generating in the background (Number does not include the server the client is currently connected to. So if `number_of_pregen_levels` is 1, there are actually two servers concurrently running.) .
* `number_of_pregen_levels_in_standby` (default: 0, min: 0, max: `number_of_pregen_levels`): Number of ServerWorlds generating in the background during standby mode. The standby mode starts once a ServerWorld expires and ends when you reset, so during a run there won't be that many resets
* `expire_after_seconds` (default: 280): Levels in the queue expire after n seconds. A level will be discarded, if the player doesn't load it before then. If set to -1, never discard a level. 
* `time_between_starts_ms` (default: 1000): Delay in milliseconds between the start of a new Background server, if scheduled. This option exists so that the servers won't be starting all at once, using limited resources.
