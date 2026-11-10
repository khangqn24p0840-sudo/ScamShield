#!/usr/bin/env python3
"""Restore ScamShield's floating chatbot in the packaged Android project."""
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: restore_chatbot.py <path-to-Screens.kt>")

screens_path = Path(sys.argv[1])
source = screens_path.read_text(encoding="utf-8")

if "ChatbotLauncher(c)" not in source:
    # Insert it as the final child of MainShell's content Box, immediately
    # before the bottom navigation bar.
    pattern = r"(?m)^(        })\n(?=        BottomBar\(tab,)"
    source, count = re.subn(
        pattern,
        "            ChatbotLauncher(c)\n\\1",
        source,
        count=1,
    )
    if count != 1:
        raise SystemExit(
            "Could not find MainShell's BottomBar insertion point in Screens.kt."
        )
    screens_path.write_text(source, encoding="utf-8")
    print("Floating chatbot launcher restored in Screens.kt.")
else:
    print("Floating chatbot launcher already present.")

# Keep the APK installable as an update to the previous app version.
build_file = Path("app/build.gradle.kts")
if build_file.exists():
    gradle = build_file.read_text(encoding="utf-8")
    gradle = re.sub(r"versionCode\\s*=\\s*\\d+", "versionCode = 11", gradle, count=1)
    gradle = re.sub(r'versionName\\s*=\\s*"[^"]+"', 'versionName = "4.6"', gradle, count=1)
    build_file.write_text(gradle, encoding="utf-8")
