# ScamShield mascot — Rive handoff

This folder contains the editable vector starting point for the ScamShield robot. The Android project already includes the Rive Android runtime and a Rive-first renderer in `Chatbot.kt`; until the exported `.riv` file is installed, the app intentionally uses its Compose fallback.

## Visual direction

Use the supplied `scamshield_robot.svg` as the character starting point:
- Cute chibi helper, friendly and reassuring rather than mechanical or intimidating.
- Pearl-white shell, pale cyan body panel, deep navy face screen, aqua eyes, soft pink cheeks.
- Keep the head and body balanced. Hands should read as soft rounded mittens, not thin rods.
- Maintain the transparent background and keep all parts inside the artboard.

The SVG uses named groups so the main pieces can be identified during Rive setup: `antenna`, `head_shell`, `face_visor`, `left_eye`, `right_eye`, `eyebrows`, `cheeks`, `mouth_happy`, `body`, `left_arm`, `right_arm`, and `legs`. Treat this SVG as a vector construction reference; inspect the imported hierarchy in Rive and separate any nested shapes further when needed for rigging.

## Rive authoring contract

Create one artboard named `ScamShieldRobot`. Export the runtime file as:

`app/src/main/res/raw/scamshield_robot.riv`

Create these looping/one-shot animations with the exact case-sensitive names because `RobotMood.animationName` in Android requests them:

| Animation | Suggested behavior | Loop |
|---|---|---|
| `Idle` | gentle body bob, occasional blink, pupils glance left/right, tiny antenna bounce | Loop |
| `Greeting` | left mitten waves, eyes brighten, open smile | One-shot or short loop |
| `Analyzing` | pupils scan slowly, brows focus, antenna softly pulses | Loop |
| `Warning` | eyes widen, brows lift, mouth forms a small “o”, subtle amber accent | Short loop |
| `Sad` | inner brows tilt up, eyes soften, mouth turns down | Short loop |
| `Cute` | one wink, rosy cheeks pulse, tiny bounce | One-shot |

Keep facial states readable at small on-screen sizes. Prefer easing curves and short, soft transitions; avoid rapid shaking, flashing, aggressive red effects, or large motion that would distract from safety information.

## Android integration

The current Compose code calls `setRiveResource(..., animationName = mood.animationName)` and calls `play(mood.animationName)` when the mood changes. Keep the six animation names above in sync with `RobotMood`. Do not rename the artboard/animations without updating Kotlin in the same change.

After exporting the `.riv` file, place it at the path above. The existing `getIdentifier("scamshield_robot", "raw", ...)` lookup will then find it automatically. Keep the Compose fallback until the actual Rive file has been tested on Android.
