# VP Points API Usage

`vppoints` owns these systems:
- capture point progress and ownership
- team resource production
- ticket drain and finish trigger
- in-match boundary rule (return warning + out-of-bounds countdown kill)
- match HUD sync payloads

Host mod (map/teleport side) should:
1. Teleport players into prepared match world.
2. Call `VpPointsApi.startMatch(...)`.
3. Register `VpPointsApi.setMatchFinishListener(...)` to receive ticket-finish callback.
4. Perform post-match teleports/restoration externally.
5. Call `VpPointsApi.endMatch(...)` to clear runtime state.

API entry:
- `src/main/java/com/flowingsun/vppoints/api/VpPointsApi.java`

Resource APIs (mapId + teamName):
- `resourceOf(mapId, teamName)` query current victory points / ammo / oil
- `adjustTeamResources(mapId, teamName, victoryPointsDelta, ammoDelta, oilDelta)` signed delta
- `addVictoryPoints(...)` / `subVictoryPoints(...)`
- `addAmmo(...)` / `subAmmo(...)`
- `addOil(...)` / `subOil(...)`

Notes:
- all subtract APIs clamp to 0 minimum (never negative)
- add/sub APIs require non-negative amount input
