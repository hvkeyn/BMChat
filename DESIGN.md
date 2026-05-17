---
version: alpha
name: BMChat — Burgundy Tech
description: >
  Visual identity for the BMChat (BroMoreChat) email-based messenger.
  Calm, functional, "private newsroom" energy — never social-app loud.
colors:
  primary:           "#7B1226"
  primary-container: "#F4D8DE"
  primary-dark:      "#2A030D"
  accent:            "#C62A48"
  accent-pressed:    "#8F1830"
  on-primary:        "#FFF7F8"
  surface-light:     "#FFFFFF"
  surface-dark:      "#24050D"
  canvas-light:      "#FFF7F8"
  canvas-dark:       "#160208"
  ink:               "#1F050B"
  muted:             "#6B4A52"
  border:            "#E8C3CB"
  ok:                "#1B6B3A"
  warn:              "#B07A1A"
typography:
  display:
    fontFamily: "Public Sans, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize:   "3rem"
    fontWeight: 700
    letterSpacing: "-0.02em"
    lineHeight: 1.05
  h1:
    fontFamily: "Public Sans, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize:   "2.25rem"
    fontWeight: 600
    letterSpacing: "-0.01em"
    lineHeight: 1.15
  h2:
    fontFamily: "Public Sans, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize:   "1.5rem"
    fontWeight: 600
    lineHeight: 1.25
  body:
    fontFamily: "Public Sans, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize:   "1rem"
    fontWeight: 400
    lineHeight: 1.55
  label-caps:
    fontFamily: "Space Grotesk, 'Segoe UI', sans-serif"
    fontSize:   "0.75rem"
    fontWeight: 600
    letterSpacing: "0.12em"
  mono:
    fontFamily: "JetBrains Mono, ui-monospace, 'SF Mono', Consolas, monospace"
    fontSize:   "0.85rem"
    lineHeight: 1.5
rounded:
  sm: 6px
  md: 12px
  lg: 18px
  pill: 999px
spacing:
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 40px
  xxl: 72px
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor:       "{colors.on-primary}"
    rounded:         "{rounded.md}"
    padding:         "12px 18px"
    typography:      "{typography.label-caps}"
  button-primary-hover:
    backgroundColor: "{colors.accent-pressed}"
  button-secondary:
    backgroundColor: "transparent"
    textColor:       "{colors.ink}"
    rounded:         "{rounded.md}"
    padding:         "12px 18px"
  card:
    backgroundColor: "{colors.surface-light}"
    rounded:         "{rounded.lg}"
    padding:         "{spacing.lg}"
  badge-stable:
    backgroundColor: "{colors.primary-container}"
    textColor:       "{colors.primary-dark}"
    rounded:         "{rounded.pill}"
    padding:         "4px 10px"
---

# BMChat Design System

BMChat is a messenger for familiar chat workflows over email and
chatmail systems. The interface should feel trustworthy, direct, and
open: closer to a practical communication tool than a decorative
social network.

## Overview

BMChat's visual identity is `Burgundy Tech + friendly messenger`,
sourced from `brand/assets/bmcha-logo-source.jpeg`. The product
should evoke a private, deliberate workspace — somewhere between a
newsroom dashboard and a piece of communication infrastructure.

- Burgundy anchors the product in BMChat's B-mark identity.
- Crimson is the accent for primary actions, active states, unread
  indicators, and connection highlights.
- Light mode should feel clean and warm; dark mode should feel deep,
  calm, and technical — never carnival-bright.
- Surfaces are simple, with generous corner radii and restrained
  borders.
- Product language emphasises communication through email systems,
  not a new centralised silo.

## Colors

The palette is rooted in two sibling burgundies (one almost-black for
high-contrast surfaces, one mid-saturated for primary actions),
balanced by a single warm-rose neutral.

- **Primary `#7B1226`** ("BMChat Burgundy") — primary actions, active
  navigation, links, selected states, the B-mark.
- **Primary Dark `#2A030D`** ("Deep Wine") — dark hero surfaces, app
  navigation backgrounds, high-contrast headers.
- **Primary Container `#F4D8DE`** ("Soft Rose") — light selected
  states, chips, calm highlights, badge backgrounds.
- **Accent `#C62A48`** ("Crimson Accent") — unread states, active
  delivery, secondary accents.
- **Accent Pressed `#8F1830`** ("Crimson Dark") — hover/pressed
  accent states.
- **Canvas Light `#FFF7F8`** — app background in light mode (warm
  rose-tinted off-white, never pure white).
- **Canvas Dark `#160208`** — app background in dark mode.
- **Surface Light `#FFFFFF`** — cards, dialogs, message bubbles in
  light mode.
- **Surface Dark `#24050D`** — same in dark mode.
- **Ink `#1F050B`** — primary text on light surfaces.
- **Border `#E8C3CB`** ("Rose Mist") — borders and separators.

## Typography

- Prefer system UI fonts on every platform; the website may load
  Public Sans + Space Grotesk + JetBrains Mono from a self-hosted
  Google Fonts proxy.
- Use medium-bold weight for screen titles and action labels.
- Keep body text at normal weight with generous line height.
- Avoid novelty fonts. BMChat should feel native on Windows, Linux,
  macOS, Android, iOS — and on the website.
- `label-caps` (uppercase, tracked Space Grotesk) is reserved for
  short eyebrow labels, button text, and section taglines — never
  body copy.

## Layout

- 8-pixel spacing rhythm.
- 12px radius for buttons / chips, 18px radius for cards and message
  bubbles, full-pill radius reserved for status badges.
- Dense but readable navigation; no decorative whitespace eating
  primary content.
- Preserve native platform behaviour for dialogs, menus, permissions,
  keyboard shortcuts.

## Components

- **Primary button** uses BMChat Burgundy with `on-primary` text.
  Press state moves to Crimson Dark, never to a generic gray.
- **Secondary button** is transparent over the canvas with the
  burgundy text and a 1px Rose Mist border.
- **Card** uses Surface Light over Canvas Light with a 1px Rose Mist
  border and 18px radius. In dark mode it's Surface Dark over Canvas
  Dark with a hairline rgba border.
- **Badge / chip** uses Primary Container background and Primary Dark
  text in pill radius — used for stable channel labels, version
  pills, "Скачать APK" surface labels.
- **Message bubble** keeps clear contrast between incoming and
  outgoing — outgoing is Primary Burgundy with `on-primary` text;
  incoming is Surface Light/Dark with Ink text.

## Do

- Use `BMChat` for normal UI and `BroMoreChat` for marketing/about
  copy.
- Keep design tokens centralised in
  `brand/config/bmchat-brand.json` and re-derived from this file.
- Preserve Delta Chat attribution in legal, source, and
  upstream-credit contexts.
- Keep the existing email/chatmail transport model visible in
  explanatory copy — that's the product's core promise.

## Do Not

- Don't introduce public channels, bots, or searchable communities
  as UI promises until the features ship.
- Don't rename deep upstream package/class namespaces just for
  visual branding.
- Don't remove license notices or upstream attribution.
- Don't use Telegram branding, iconography, or copy directly.
- Don't pivot to pure white / pure black — burgundy is the brand,
  and "warm dark" / "warm light" is the canvas.
