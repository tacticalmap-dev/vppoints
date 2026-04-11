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
