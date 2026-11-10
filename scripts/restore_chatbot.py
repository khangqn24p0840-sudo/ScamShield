#!/usr/bin/env python3
"""Attach the new AI conversation panel to ScamShield's original FloatingBot."""
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: restore_chatbot.py <path-to-Screens.kt>")

screens_path = Path(sys.argv[1])
source = screens_path.read_text(encoding="utf-8")

main_start = source.find("@Composable fun MainShell(")
next_func = source.find("@Composable fun BottomBar(", main_start)
if main_start < 0 or next_func < 0:
    raise SystemExit("MainShell/BottomBar markers not found in packaged Screens.kt.")

main = source[main_start:next_func]

# The older UI already has the purple FloatingBot. Reuse it and route its click
# to the new conversation panel instead of adding a second floating robot.
if "chatbotOpen" not in main:
    state_anchor = "    var openPost by remember { mutableLongStateOf(0L) }"
    if main.count(state_anchor) != 1:
        raise SystemExit("Could not locate MainShell state insertion point.")
    main = main.replace(
        state_anchor,
        state_anchor + "\n    var chatbotOpen by remember { mutableStateOf(false) }",
        1,
    )

floating_pattern = re.compile(
    r"(?m)^(?P<indent>[ \\t]*)if \\(sub != \"ai\" && !hideBot\\) FloatingBot\\((?P<modifier>.*?)\\) \\{ sub = \"ai\" \\}$"
)
matches = list(floating_pattern.finditer(main))
if len(matches) != 1:
    if "ChatbotOverlay(c, h, visible = chatbotOpen" not in main:
        raise SystemExit(
            "Expected exactly one original FloatingBot callback in packaged Screens.kt; "
            "refusing to add a duplicate."
        )
else:
    m = matches[0]
    indent = m.group("indent")
    replacement = (
        f'{indent}if (sub != "ai" && !hideBot && !chatbotOpen) '
        f'FloatingBot({m.group("modifier")}) {{ chatbotOpen = true }}'
        f'\n{indent}ChatbotOverlay(c, h, visible = chatbotOpen, onClose = {{ chatbotOpen = false }})'
    )
    main = main[:m.start()] + replacement + main[m.end():]
    source = source[:main_start] + main + source[next_func:]
    screens_path.write_text(source, encoding="utf-8")
    print("Kept the original purple FloatingBot and connected it to the AI conversation panel.")

# Never inject another floating launcher into the ZIP-based project.
# This makes the original FloatingBot the only visible chatbot button.

# Bump version for this UI update so the APK installs as an in-place update.
build_file = Path("app/build.gradle.kts")
if build_file.exists():
    gradle = build_file.read_text(encoding="utf-8")
    gradle = re.sub(r"versionCode\\s*=\\s*[0-9]+", "versionCode = 12", gradle, count=1)
    gradle = re.sub(r'versionName\\s*=\\s*"[^"]+"', 'versionName = "4.7"', gradle, count=1)
    build_file.write_text(gradle, encoding="utf-8")
    print("App version set to 4.7 (versionCode 12).")
