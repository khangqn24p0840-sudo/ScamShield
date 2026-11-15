# ScamShield Rive Robot

This is the source project for the ScamShield interactive mascot.

## Animation names consumed by the Android app

- `Idle`: relaxed idle loop; waves its arms gently, looks around and bobs.
- `Greeting`: two-arm greeting gesture.
- `Analyzing`: scanning eyes and antenna movement; loops while analysis is running.
- `Warning`: alert expression when suspicious content is found.
- `Sad`: lowered head and relaxed arms for errors or distress.
- `Cute`: a small playful tilt and wave.

The Android integration looks for `app/src/main/res/raw/scamshield_robot.riv`. If that file is not present yet, the app keeps the previous Compose-drawn mascot as a temporary fallback.

## Build on Windows

1. Install the Rive CLI using the official Windows PowerShell installer:
   `irm https://releases.rive.app/cli/install.ps1 | iex`
2. From the repository root, run `./build-rive-robot.ps1`.
3. The script opens the Rive CLI preview, waits for its first local build, then copies the generated `.riv` file into the Android raw resources folder.
4. Build the Android app again. No Rive account is required for local preview/build. A Rive account is only needed for publishing or syncing into the cloud Editor.

Open the same project folder in Rive Editor/CLI to refine the vector artwork and timing. The RML source remains version-controlled next to the app.
