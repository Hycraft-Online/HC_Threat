# HC_Threat

Adds a persistent cumulative threat/aggro system for NPC targeting in Hytale. NPCs target the player with the highest cumulative damage dealt rather than the most recent attacker, enabling tank/DPS/healer dynamics in multi-player combat.

## Features

- Maintains per-NPC threat tables mapping each player to their cumulative threat value
- Overrides NPC targeting so the highest-threat player is prioritized
- Automatic threat decay over time (5% per second) with configurable thresholds
- Reverse index for efficient cleanup when players disconnect
- Cached highest-threat lookups with O(1) updates on threat addition
- Static API (`HC_ThreatAPI`) for other plugins to add/query/clear threat
- Debug command (`/threat`) for inspecting active threat tables

## Dependencies

- Hytale:EntityModule

### Optional

- HC_Attributes (compile-time only, for passive check integration)

## Building

```bash
./gradlew build
```
