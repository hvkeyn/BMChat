# BMChat Design System

BMChat is a messenger for familiar chat workflows over email and chatmail systems. The interface should feel trustworthy, direct, and open: closer to a practical communication tool than a decorative social network.

## Visual Theme And Atmosphere

Use a `Tech Utility + friendly messenger` direction:

- Clear blue anchors the product in reliability, transport, and infrastructure.
- Green is the accent for free connection, successful delivery, and active states.
- Light mode should feel clean and spacious; dark mode should feel calm, not neon.
- Surfaces should be simple, with rounded corners and restrained borders.
- Product language should emphasize communication through email systems, not a new centralized silo.

## Color Palette

| Token | Hex | Role |
| --- | --- | --- |
| Primary Blue | `#2563EB` | Primary actions, active navigation, links, selected states |
| Deep Mail Blue | `#172554` | Dark hero surfaces, navigation, high-contrast headers |
| Soft Blue | `#DBEAFE` | Light selected states, chips, calm highlights |
| Free Green | `#10B981` | Positive status, online/ready states, secondary accents |
| Green Dark | `#047857` | Hover/pressed accent states |
| Light Canvas | `#F8FAFC` | App background in light mode |
| Dark Canvas | `#0F172A` | App background in dark mode |
| Light Surface | `#FFFFFF` | Cards, dialogs, message surfaces in light mode |
| Dark Surface | `#111827` | Cards, dialogs, message surfaces in dark mode |
| Ink | `#0F172A` | Primary text on light surfaces |
| Mist | `#D8E2F1` | Borders and separators |

## Typography

- Prefer system UI fonts on every platform.
- Use medium weight for screen titles and action labels.
- Keep body text at normal weight with generous line height.
- Avoid novelty fonts; BMChat should feel native on Windows, Linux, macOS, Android, and iOS.

## Components

- Primary buttons use Primary Blue with white text.
- Secondary buttons use transparent or soft-blue surfaces.
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
