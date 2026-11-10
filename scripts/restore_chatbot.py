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
    feature_start = theme.find("@Composable fun FeatureCard(")
    feature_end = theme.find("@Composable fun RowCard(", feature_start)
    if feature_start < 0 or feature_end < 0:
        raise SystemExit("FeatureCard component not found in packaged Theme.kt.")
    compact_feature = """@Composable fun FeatureCard(modifier: Modifier = Modifier, icon: ImageVector, label: String, tint: Color, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    Pressable(onClick, modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(52.dp)
                .background(tint.copy(alpha = 0.13f), RoundedCornerShape(17.dp))
                .border(1.dp, tint.copy(alpha = 0.18f), RoundedCornerShape(17.dp)),
                contentAlignment = Alignment.Center) {
                if (leading != null) leading()
                else Icon(icon, null, tint = tint, modifier = Modifier.size(25.dp))
            }
            Text(label, color = C.Text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, lineHeight = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp))
        }
    }
}

"""
    theme = theme[:feature_start] + compact_feature + theme[feature_end:]
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
# Compact the packaged HomeBody into a 3-column, 2-row feature pager and expose Family Chat.
screens_path = Path(sys.argv[1])
source = screens_path.read_text(encoding="utf-8")
home_start = source.find("@Composable private fun HomeBody(")
status_start = source.find("/** Thẻ trạng thái bảo vệ", home_start)
if home_start < 0 or status_start < 0:
    raise SystemExit("HomeBody block not found in packaged Screens.kt.")
home = source[home_start:status_start]

if "featurePage" not in home:
    home = home.replace(
        "@Composable private fun HomeBody(c: Context, h: Hooks, open: (String) -> Unit, onPost: (Long) -> Unit, onFeed: () -> Unit) {",
        "@Composable private fun HomeBody(c: Context, h: Hooks, open: (String) -> Unit, onPost: (Long) -> Unit, onFeed: () -> Unit) {\n    var featurePage by remember { mutableIntStateOf(0) }",
        1,
    )

if '"Chat gia đình"' not in home:
    home = home.replace(
        'Cat(Icons.Rounded.CenterFocusStrong, "Quét camera", C.Primary, "scan"))',
        'Cat(Icons.Rounded.CenterFocusStrong, "Quét camera", C.Primary, "scan"),\n        Cat(Icons.Rounded.FamilyRestroom, "Chat gia đình", C.Primary, "family"))',
        1,
    )

grid_start = home.find('    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {\n        cats.chunked(2)')
grid_end = home.find('    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {\n        SectionHeader("Cảnh báo cộng đồng"', grid_start)
if grid_start < 0 or grid_end < 0:
    raise SystemExit("Could not locate the packaged feature grid and recommendation section.")

new_grid = """    val featurePages = cats.chunked(6)
    val currentPage = featurePage.coerceIn(0, featurePages.lastIndex)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        featurePages[currentPage].chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { ct ->
                    FeatureCard(Modifier.weight(1f), ct.icon, ct.label, ct.tint,
                        { if (ct.key == "scan") h.startLiveScan() else open(ct.key) })
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (featurePages.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                featurePages.indices.forEach { index ->
                    Box(Modifier.padding(horizontal = 4.dp).size(if (index == currentPage) 8.dp else 6.dp)
                        .background(if (index == currentPage) C.Primary else Color(0xFFD9EAE1), CircleShape)
                        .clickable { featurePage = index })
                }
            }
        }
    }
    SectionHeader("Tin nổi bật", "Xem thêm") { feedStartSection = "news"; onFeed() }
    FeedPreview(c, "news", { feedStartSection = "news"; onFeed() }, onPost)
"""
home = home[:grid_start] + new_grid + home[grid_end:]
home = home.replace(
    """    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Tin tức & kiến thức", "Xem tất cả") { feedStartSection = "news"; onFeed() }
        FeedPreview(c, "news", { feedStartSection = "news"; onFeed() }, onPost)
    }
""",
    "",
    1,
)
source = source[:home_start] + home + source[status_start:]
screens_path.write_text(source, encoding="utf-8")
print("Home menu changed to a compact 3-column grid with page dots and a Chat gia đình tile.")

features_file = screens_path.parent / "Features.kt"
if features_file.exists():
    features = features_file.read_text(encoding="utf-8")
    route = '"family" -> FamilyChatScreen(c, back)'
    if route not in features:
        anchor = '        "ai" -> Page("Trợ lý AI", back) { AiPanel(c, h.aiMsg(), h.pickImage) { h.setAiMsg(it) } }'
        if anchor not in features:
            raise SystemExit("Could not find the AI sub-screen route in Features.kt.")
        features = features.replace(anchor, anchor + "\n        " + route, 1)
        features_file.write_text(features, encoding="utf-8")
print("Family chat route is registered.")

# Never inject ChatbotLauncher into the ZIP-based project: the old FloatingBot
# remains the only floating robot. Its sparkle/tools now live inside the chat.

# Bump version so this build installs as an update to the previous app.
build_file = Path("app/build.gradle.kts")
if build_file.exists():
    gradle = build_file.read_text(encoding="utf-8")
    gradle = re.sub(r"versionCode\s*=\s*[0-9]+", "versionCode = 15", gradle, count=1)
    gradle = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "4.10"', gradle, count=1)
    build_file.write_text(gradle, encoding="utf-8")
    print("App version set to 4.10 (versionCode 15).")
