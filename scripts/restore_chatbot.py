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

# Emit focused source context from the packaged ZIP so UI adjustments target its real components.
theme_file = screens_path.parent / "Theme.kt"
for target in (theme_file, screens_path):
    if target.exists():
        source_lines = target.read_text(encoding="utf-8", errors="replace").splitlines()
        print(f"===== SOURCE CONTEXT: {target.name} =====")
        keys = ("FloatingBot", "FeatureTile", "FeatureCard", "CatTile", "TILE", "Thẻ tính năng", "Ô tính năng", "Danh mục")
        selected = set()
        for i, line in enumerate(source_lines):
            if any(key.lower() in line.lower() for key in keys):
                selected.update(range(max(0, i - 8), min(len(source_lines), i + 18)))
        for i in sorted(selected):
            print(f"{i+1}: {source_lines[i]}")

# The ZIP source already has a purple FloatingBot. Reuse it rather than adding
# another floating button, and make its click open the new conversation panel.
if "chatbotOpen" not in main:
    state_anchor = "    var openPost by remember { mutableLongStateOf(0L) }"
    if main.count(state_anchor) != 1:
        raise SystemExit("Could not locate MainShell state insertion point.")
    main = main.replace(
        state_anchor,
        state_anchor + "\n    var chatbotOpen by remember { mutableStateOf(false) }",
        1,
    )

lines = main.splitlines()
matches = [
    i for i, line in enumerate(lines)
    if "FloatingBot(" in line and '{ sub = "ai" }' in line
]
if len(matches) != 1:
    if "ChatbotOverlay(c, h, visible = chatbotOpen" not in main:
        raise SystemExit(
            "Expected exactly one original FloatingBot callback in packaged Screens.kt; "
            "refusing to add a duplicate."
        )
else:
    i = matches[0]
    line = lines[i]
    expected_condition = 'if (sub != "ai" && !hideBot)'
    expected_action = '{ sub = "ai" }'
    if expected_condition not in line or expected_action not in line:
        raise SystemExit("Original FloatingBot line changed; refusing an unsafe UI rewrite.")

    lines[i] = line.replace(
        expected_condition,
        'if (sub != "ai" && !hideBot && !chatbotOpen)',
        1,
    ).replace(expected_action, '{ chatbotOpen = true }', 1)

    indent = line[:len(line) - len(line.lstrip())]
    lines.insert(
        i + 1,
        indent + "ChatbotOverlay(c, h, visible = chatbotOpen, onClose = { chatbotOpen = false })",
    )

    main = "\n".join(lines) + ("\n" if main.endswith("\n") else "")
    source = source[:main_start] + main + source[next_func:]
    screens_path.write_text(source, encoding="utf-8")
    print("Kept original purple FloatingBot; connected it to the AI conversation panel.")

# Never inject ChatbotLauncher into the ZIP-based project: the old FloatingBot
# remains the only floating robot. Its sparkle/tools now live inside the chat.

# Bump version so this build installs as an update to the previous app.
build_file = Path("app/build.gradle.kts")
if build_file.exists():
    gradle = build_file.read_text(encoding="utf-8")
    gradle = re.sub(r"versionCode\s*=\s*[0-9]+", "versionCode = 12", gradle, count=1)
    gradle = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "4.7"', gradle, count=1)
    build_file.write_text(gradle, encoding="utf-8")
    print("App version set to 4.7 (versionCode 12).")
