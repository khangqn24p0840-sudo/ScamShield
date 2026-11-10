#!/usr/bin/env python3
"""Restore ScamShield's floating chatbot in the packaged Android project."""
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: restore_chatbot.py <path-to-Screens.kt>")

screens_path = Path(sys.argv[1])

# Temporary build diagnostics: identify any pre-existing floating robot/chat entry
# in the ZIP source before adding the current launcher.
source_root = screens_path.parent
for candidate in source_root.glob("*.kt"):
    lines = candidate.read_text(encoding="utf-8", errors="replace").splitlines()
    matches = [(i, line.strip()) for i, line in enumerate(lines, 1)
               if any(key in line.lower() for key in
                      ("chatbot", "floatingactionbutton", "smarttoy", "robot", "alignment.bottomend"))]
    if matches:
        print(f"--- EXISTING FLOATING/CHAT MATCHES: {candidate.name} ---")
        for number, line in matches[:30]:
            print(f"{number}: {line[:240]}")
source = screens_path.read_text(encoding="utf-8")
if "ChatbotLauncher(c)" not in source:
    start = source.find("@Composable fun MainShell(")
    if start < 0:
        raise SystemExit("MainShell not found in Screens.kt.")
    next_func = source.find("@Composable fun BottomBar(", start)
    if next_func < 0:
        raise SystemExit("BottomBar not found after MainShell in Screens.kt.")

    region = source[start:next_func]
    root = re.compile(
        r"(?m)^([ \t]*)Column\(Modifier\.fillMaxSize\(\)\.statusBarsPadding\(\)\) \{$"
    )
    match = root.search(region)
    if not match:
        raise SystemExit("MainShell root Column not found; refusing to patch the wrong UI.")

    indent = match.group(1)
    region = root.sub(
        indent + "Box(Modifier.fillMaxSize()) {\n" +
        indent + "    Column(Modifier.fillMaxSize().statusBarsPadding()) {",
        region,
        count=1,
    )
    close = region.rfind("\n}")
    if close < 0:
        raise SystemExit("MainShell closing brace not found.")
    region = (
        region[:close] +
        "\n        ChatbotLauncher(c)\n    }\n" +
        region[close:]
    )
    source = source[:start] + region + source[next_func:]
    screens_path.write_text(source, encoding="utf-8")
    print("Floating chatbot launcher restored in Screens.kt.")
else:
    print("Floating chatbot launcher already present.")

# Keep the chatbot APK installable as an update to the previous app version.
build_file = Path("app/build.gradle.kts")
if build_file.exists():
    gradle = build_file.read_text(encoding="utf-8")
    gradle = re.sub("versionCode[ \t]*=[ \t]*[0-9]+", "versionCode = 11", gradle, count=1)
    gradle = re.sub('versionName[ \t]*=[ \t]*"[^"]+"', 'versionName = "4.6"', gradle, count=1)
    build_file.write_text(gradle, encoding="utf-8")
    print("App version set to 4.6 (versionCode 11).")
