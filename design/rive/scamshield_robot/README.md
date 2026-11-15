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
3. Run `rive login` and sign in to a Rive account. The Rive CLI requires an authenticated session to export the signed `.riv` runtime file.
4. From the repository root, run `./build-rive-robot.ps1`. It verifies the RML source, exports `build/scamshield_robot.riv`, then copies it into `app/src/main/res/raw/`.
5. Commit `app/src/main/res/raw/scamshield_robot.riv` to GitHub and build the APK. GitHub Actions verifies and screenshots the RML source without needing your personal Rive login.

Open the same project folder in Rive Editor/CLI to refine the vector artwork and timing. The RML source remains version-controlled next to the app.
