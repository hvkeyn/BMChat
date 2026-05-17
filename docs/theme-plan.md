# BMChat Theme And Brand Layer

## Goal

BMChat should have a single brand source and platform-specific generated or manually mapped outputs. The product should not rely on scattered one-off replacements across three clients.

Source of truth:

- `brand/config/bmchat-brand.json`: names, identifiers, colors, default locale.
- `brand/assets`: original logo, icon, background, screenshot, and store artwork sources.

## Brand Tokens

First implementation tokens from `brand/config/bmchat-brand.json` and `DESIGN.md`:

- `primaryColor`: `#2563EB`
- `primaryDark`: `#172554`
- `primarySoft`: `#DBEAFE`
- `accentColor`: `#10B981`
- `accentDark`: `#047857`
- `backgroundLight`: `#F8FAFC`
- `backgroundDark`: `#0F172A`
- `surfaceLight`: `#FFFFFF`
- `surfaceDark`: `#111827`
- `textPrimary`: `#0F172A`
- `textInverse`: `#F8FAFC`
- `borderLight`: `#D8E2F1`
- `shortName`: `BMChat`
- `longName`: `BroMoreChat`

The first BMChat visual direction is `Tech Utility + friendly messenger`: reliable blue for infrastructure and primary actions, green for free connection/status, calm native surfaces, and no Telegram-specific visual borrowing.

## Desktop

Delta Chat Desktop already has a theme system based on SCSS files in `clients/desktop/packages/frontend/themes`.

Current upstream themes:

- `light.scss`
- `dark.scss`
- `darkpurple.scss`
- `dark_amoled.scss`
- `dev_minimal.scss`
- `dev_rocket.scss`

BMChat first-pass implementation:

1. Recolor the built-in `light.scss` and `dark.scss` defaults to BMChat tokens.
2. Keep custom theme support through the existing `custom-themes` folder.
3. Keep `_themebase.scss` mostly upstream-compatible to reduce merge conflicts.
4. Keep separate BMChat source tokens in `brand/config/bmchat-brand.json` and `DESIGN.md`.
5. Replace background assets referenced by `$bgImage` with BMChat-colored background SVGs.
6. Update documentation and UI labels so theme help refers to BMChat, while preserving upstream attribution where relevant.

Important desktop files:

- `clients/desktop/packages/frontend/themes/*.scss`
- `clients/desktop/docs/THEMES.md`
- `clients/desktop/packages/target-electron/src/application-constants.ts`
- `clients/desktop/packages/target-electron/src/application-config.ts`
- `clients/desktop/images/background_light.svg`
- `clients/desktop/images/background_dark.svg`
- `clients/desktop/images/backgrounds/*`

## Android

Android uses native resources and AppCompat styles. The first theme pass should be conservative and resource-based.

Current brand-colored resources include:

- `clients/android/src/main/res/values/colors.xml`: `delta_primary`, `delta_primary_lite`, `delta_accent`, `delta_accent_darker`, unread and reaction colors.
- `clients/android/src/main/res/values/styles.xml`: light action bar and switch colors reference Delta colors.
- `clients/android/src/main/res/drawable/*`: button, bubble, launcher foreground, and UI drawable colors.
- `clients/android/src/main/res/values-night` and dark resources if present.

BMChat first-pass implementation:

1. Map existing `delta_*` color names to BMChat token values to preserve upstream mergeability.
2. Replace launcher icons and monochrome foreground assets.
3. Replace chat backgrounds and onboarding imagery.
4. Keep native light/dark behavior first; add user-selectable custom themes only after BMChat branding is stable.
5. Avoid Java package renames in the first pass.

## iOS

iOS branding is split across asset catalogs, plist files, localized strings, and Xcode project settings.

Important files:

- `clients/ios/deltachat-ios/Assets.xcassets/AppIcon.appiconset`
- `clients/ios/deltachat-ios/Assets.xcassets/dc_logo.imageset`
- `clients/ios/deltachat-ios/Assets.xcassets/background_light.imageset`
- `clients/ios/deltachat-ios/Assets.xcassets/background_dark.imageset`
- `clients/ios/deltachat-ios/Assets.xcassets/Colors/*`
- `clients/ios/deltachat-ios/Info.plist`
- `clients/ios/deltachat-ios.xcodeproj/project.pbxproj`

BMChat first-pass implementation:

1. Update display names, localized permission prompts, and bundle identifiers.
2. Replace app icons and logo image sets where they exist in this checkout.
3. Replace light/dark chat backgrounds.
4. Update color asset catalogs for reaction, accent, and surface colors where present.
5. Treat app extensions and app groups as part of the same brand identity.
6. Defer runtime theme switching unless product requirements explicitly demand it; start with native light/dark plus BMChat colors.

## Asset Generation

Recommended future tooling:

- Generate desktop PNG/ICO/ICNS/tray assets from a source SVG.
- Generate Android adaptive icon foreground/background and monochrome icon from source assets.
- Generate iOS AppIcon.appiconset, extension icons, and store icon from source assets.
- Generate a small markdown report listing every generated file and source asset hash.

Proposed output folders:

- `brand/generated/desktop`
- `brand/generated/android`
- `brand/generated/ios`

Generated assets should be copied into client trees in a controlled commit, not edited manually inside platform folders.

## First Implementation Pass

1. Keep upstream theme architecture intact.
2. Add BMChat themes/assets next to upstream assets.
3. Switch defaults to BMChat assets and theme names.
4. Leave upstream-compatible names in code where renaming adds merge risk.
5. Document any deliberate compatibility names in `docs/brand-map.md`.
