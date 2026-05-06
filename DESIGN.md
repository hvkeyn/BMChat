# BMChat Design System

BMChat is a messenger for familiar chat workflows over email and chatmail systems. The interface should feel trustworthy, direct, and open: closer to a practical communication tool than a decorative social network.

## Visual Theme And Atmosphere

Use a `Burgundy Tech + friendly messenger` direction based on `brand/assets/bmcha-logo-source.jpeg`:

- Burgundy anchors the product in BMChat's B-mark identity and gives the app a distinct, serious messenger feel.
- Crimson is the accent for primary actions, active states, unread indicators, and connection highlights.
- Light mode should feel clean and warm; dark mode should feel deep, calm, and technical.
- Surfaces should be simple, with rounded corners and restrained borders.
- Product language should emphasize communication through email systems, not a new centralized silo.

## Color Palette

| Token | Hex | Role |
| --- | --- | --- |
| BMChat Burgundy | `#7B1226` | Primary actions, active navigation, links, selected states |
| Deep Wine | `#2A030D` | Dark hero surfaces, navigation, high-contrast headers |
| Soft Rose | `#F4D8DE` | Light selected states, chips, calm highlights |
| Crimson Accent | `#C62A48` | Unread states, active delivery, secondary accents |
| Crimson Dark | `#8F1830` | Hover/pressed accent states |
| Light Canvas | `#FFF7F8` | App background in light mode |
| Dark Canvas | `#160208` | App background in dark mode |
| Light Surface | `#FFFFFF` | Cards, dialogs, message surfaces in light mode |
| Dark Surface | `#24050D` | Cards, dialogs, message surfaces in dark mode |
| Ink | `#1F050B` | Primary text on light surfaces |
| Rose Mist | `#E8C3CB` | Borders and separators |

## Typography

- Prefer system UI fonts on every platform.
- Use medium weight for screen titles and action labels.
- Keep body text at normal weight with generous line height.
- Avoid novelty fonts; BMChat should feel native on Windows, Linux, macOS, Android, and iOS.

## Components

- Primary buttons use BMChat Burgundy with white text.
- Secondary buttons use transparent or soft-rose surfaces.
- Chat bubbles should keep a clear contrast between incoming and outgoing messages.
- Dialogs should have clear titles, concise helper text, and large touch targets.
- Group creation should be labeled as an ordinary shared/group chat flow, not as a separate protocol.

## Layout

- Use an 8px spacing rhythm.
- Prefer 12px to 16px corner radius for cards and message bubbles.
- Keep navigation compact but readable.
- Preserve native platform behavior for dialogs, menus, permissions, and keyboard shortcuts.

## Do

- Use `BMChat` for normal UI and `BroMoreChat` for marketing/about copy.
- Preserve Delta Chat attribution in legal, source, and upstream-credit contexts.
- Keep the existing email/chatmail transport model visible in explanatory copy.
- Keep design tokens centralized in `brand/config/bmchat-brand.json`.

## Do Not

- Do not introduce public channels, bots, or searchable communities as UI promises until the features exist.
- Do not rename deep upstream package/class namespaces just for visual branding.
- Do not remove license notices or upstream attribution.
- Do not use Telegram branding, iconography, or copy directly.
