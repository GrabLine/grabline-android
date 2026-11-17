# GrabLine brand

One look across the Android app, the desktop app and the website. The logo
(`logo.png`, a hand gripping a down arrow on a blue disc) sets the blue.

| Token | Value | Use |
| --- | --- | --- |
| Brand blue | `#2563EB` | The logo, filled buttons, the + button, progress, the app icon background |
| Blue, pressed / hover | `#1D4ED8` | Pressed fills; accent text on light backgrounds (6.7:1) |
| Accent text on dark | `#86AAFF` | Links, text buttons, selected tab text on dark backgrounds |
| Light background / surface | `#F3F5F9` / `#FFFFFF` | |
| Light text / secondary text / lines | `#161B24` / `#5A6473` / `#DBE0EA` | |
| Light selected | `#DFE8FC` | Selected rows, the active tab's pill, selected chips |
| Dark background / surface | `#07090E` / `#121821` | |
| Dark text / secondary text / lines | `#E8EEF8` / `#A4B0C4` / `#1E2736` | |
| Dark selected | `#172846` | |

- **Type:** IBM Plex Sans for text, IBM Plex Mono for sizes, speeds and versions (SIL OFL).
- **Corners:** the desktop uses 4/6/8 px. The phone uses the same scale at touch size:
  8dp for buttons, chips and fields; 12dp for cards; 16-20dp for dialogs and sheets.
  No pill-shaped buttons.
- **Buttons:** filled buttons are the brand blue with white text. On the phone, use
  `ui/theme/BrandButtons.kt` (`Button`, `OutlinedButton`, `TextButton`), never the
  Material ones directly.
- **Accent choice:** Settings, Colours offers the desktop's presets (blue, violet `#7C3AED`, green
  `#059669`, orange `#EA580C`, rose `#E11D48`, teal `#0D9488`) plus wallpaper colours. On the phone,
  green, orange and teal fills are one shade deeper (`#047857`, `#C2410C`, `#0F766E`) so white text
  stays at 4.5:1; each preset has its own light and dark text tint (`ui/theme/Accent.kt`).
- **Icon:** the launcher icon is the logo's white glyph on the brand blue; the
  monochrome (themed) icon is the same glyph.

The desktop app keeps the same tokens in `app/ui/design.py` and `docs/BRAND.md`.
