#!/usr/bin/env python3
"""Attach the new AI conversation panel to ScamShield's original FloatingBot."""
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: restore_chatbot.py <path-to-Screens.kt>")

screens_path = Path(sys.argv[1])
source = screens_path.read_text(encoding="utf-8")

# Temporary read-only inspection of the packaged home/category source and sub-screen routing.
for candidate, keys, limit in (
    (screens_path.parent / "Screens.kt", ("catsBase", "cats.chunked", "FeatureCard", "HomeTab"), 150),
    (screens_path.parent / "Features.kt", ("fun SubScreen", '"ai" ->'), 60),
    (screens_path.parent / "Theme.kt", ("fun FeatureCard", "fun CatTile", "fun AiBotBadge"), 75),
):
    if candidate.exists():
        source_lines = candidate.read_text(encoding="utf-8", errors="replace").splitlines()
        selected = set()
        for i, line in enumerate(source_lines):
            if any(k in line for k in keys):
                selected.update(range(max(0, i - 12), min(len(source_lines), i + limit)))
        print(f"===== INSPECT {candidate.name} =====")
        for i in sorted(selected):
            print(f"{i+1}: {source_lines[i]}")

main_start = source.find("@Composable fun MainShell(")
next_func = source.find("@Composable fun BottomBar(", main_start)
if main_start < 0 or next_func < 0:
    raise SystemExit("MainShell/BottomBar markers not found in packaged Screens.kt.")

main = source[main_start:next_func]

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
    print("Kept the original FloatingBot; connected it to the AI conversation panel.")

# Harmonize the package's home feature cards with the green theme and reduce oversized whitespace.
theme_file = screens_path.parent / "Theme.kt"
if theme_file.exists():
    theme = theme_file.read_text(encoding="utf-8")
    theme = theme.replace(
        ".padding(horizontal = 14.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {",
        ".padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {",
        1,
    )
    theme = theme.replace(
        "IconBadge(icon, tint, 40.dp, 21.dp)",
        "IconBadge(icon, C.Primary, 36.dp, 19.dp)",
        1,
    )
    # The recommendation rows use the same family of feature accents.
    theme = theme.replace(
        "IconBadge(icon, tint, 48.dp, 24.dp)",
        "IconBadge(icon, C.Primary, 42.dp, 21.dp)",
        1,
    )
    # Use the existing brand green for the floating assistant's label and shadow.
    theme = theme.replace("Color(0x40403C9E)", "Color(0x40206F58)")
    theme = theme.replace("Color(0xFFE3E0FA)", "Color(0xFFD6E9DF)")
    theme = theme.replace('Text("Hỏi AI", color = C.Blue', 'Text("Hỏi AI", color = C.Primary')
    print("Feature cards compacted and unified to the green palette.")
    # Recolor only the floating AI robot badge, keeping its friendly white robot and sparkle.
    bot_start = theme.find("@Composable fun AiBotBadge(")
    if bot_start >= 0:
        bot_end = theme.find("\n}", bot_start)
        if bot_end < 0:
            raise SystemExit("AiBotBadge block is incomplete; refusing a partial palette edit.")
        bot_end += 2
        bot = theme[bot_start:bot_end]
        bot = bot.replace("gradient tím-xanh", "gradient xanh-mint")
        bot = bot.replace("Color(0xFF8B7CF6)", "Color(0xFF2FA77B)")
        bot = bot.replace("Color(0xFF4F8DF7)", "Color(0xFF0D795B)")
        bot = bot.replace("Color(0xFFFFE3A3)", "Color(0xFFCBEBDD)")
        bot = bot.replace("Color(0xFFF2A91B)", "Color(0xFF168562)")
        theme = theme[:bot_start] + bot + theme[bot_end:]
    else:
        print("AiBotBadge not found; retained the existing robot component.")
    theme_file.write_text(theme, encoding="utf-8")
    print("Theme colors saved: unified green accents and compact feature cards.")
# Never inject ChatbotLauncher into the ZIP-based project: the old FloatingBot
# remains the only floating robot. Its sparkle/tools now live inside the chat.

# Bump version so this build installs as an update to the previous app.
build_file = Path("app/build.gradle.kts")
if build_file.exists():
    gradle = build_file.read_text(encoding="utf-8")
    gradle = re.sub(r"versionCode\s*=\s*[0-9]+", "versionCode = 14", gradle, count=1)
    gradle = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "4.9"', gradle, count=1)
    build_file.write_text(gradle, encoding="utf-8")
    print("App version set to 4.9 (versionCode 14).")
