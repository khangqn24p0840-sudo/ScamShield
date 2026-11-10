#!/usr/bin/env python3
"""Restore ScamShield's floating chatbot in the packaged Android project."""
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: restore_chatbot.py <path-to-Screens.kt>")

screens_path = Path(sys.argv[1])

screens_path = Path(sys.argv[1])
source = screens_path.read_text(encoding="utf-8")
if "ChatbotLauncher(c, h)" not in source:
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
    gradle = re.sub("versionCode[ \t]*=[ \t]*[0-9]+", "versionCode = 12", gradle, count=1)
    gradle = re.sub('versionName[ \t]*=[ \t]*"[^"]+"', 'versionName = "4.7"', gradle, count=1)
    build_file.write_text(gradle, encoding="utf-8")
    print("App version set to 4.6 (versionCode 11).")
