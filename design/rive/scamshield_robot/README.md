# ScamShield Rive Robot

This folder is the editable Rive Markup Language (RML) source used to create the ScamShield mascot. The primary artwork and six animation timelines are in `scene.rml`.

## Character direction

The mascot is a cute chibi-style helper, not a hard-edged mechanical robot:
- Balanced rounded head and compact body.
- Pearl-white shell with a pale-cyan chest panel and navy glass face.
- Large glossy aqua eyes with dark pupils and white highlights.
- Soft pink cheeks and a small smile.
- Shorter rounded arms, mitten-like hands, and small soft feet.
- Gentle movement only; the character should feel reassuring, not distracting.

## Animation names consumed by Android

Keep these exact, case-sensitive names in sync with `RobotMood.animationName` in `Chatbot.kt`:

- `Idle`: gentle body float, small arm movement, eye drift and blinking.
- `Greeting`: cheerful wave and wider smile.
- `Analyzing`: pupils scan, antenna moves, body gently bobs.
- `Warning`: widened eyes and surprised mouth for a real suspicious-result state.
- `Sad`: softer eyes, lowered head and relaxed arms.
- `Cute`: playful head tilt and a small wave.

## Preview and validation

The GitHub Actions workflow runs `rive design/rive/scamshield_robot --verify` and renders a preview image from the RML source. This validates the editable scene and keeps a preview in the build artifact.

## Exporting the Android runtime asset

The Android app already contains the Rive runtime and the Rive-first renderer. It looks for:

`app/src/main/res/raw/scamshield_robot.riv`

If that binary is not present, the app intentionally uses the Compose fallback.

To export the runtime file on Windows:

1. Install the Rive CLI: `irm https://releases.rive.app/cli/install.ps1 | iex`
2. Run `rive login` once in PowerShell.
3. From the repository root, run `./build-rive-robot.ps1`.
4. Commit the generated `app/src/main/res/raw/scamshield_robot.riv`, then build the APK.

The script verifies the RML project, publishes the local `.riv` asset, and copies it to Android's `res/raw` folder. Do not add an empty or placeholder `.riv` file: the Android runtime needs the real exported binary.
