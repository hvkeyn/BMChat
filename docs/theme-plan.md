# BMChat Theme And Brand Layer

## Goal

BMChat should have a single brand source and platform-specific generated or manually mapped outputs. The product should not rely on scattered one-off replacements across three clients.

Source of truth:

- `brand/config/bmchat-brand.json`: names, identifiers, colors, default locale.
- `brand/assets`: original logo, icon, background, screenshot, and store artwork sources.

## Brand Tokens

Initial tokens from `brand/config/bmchat-brand.json`:

- `primaryColor`: `#2563eb`
- `accentColor`: `#16a34a`
- `backgroundLight`: `#f8fafc`
- `backgroundDark`: `#0f172a`
- `shortName`: `BMChat`
- `longName`: `BroMoreChat`

These values are placeholders and should be replaced by final design decisions before release.

## Desktop

Delta Chat Desktop already has a theme system based on SCSS files in `clients/desktop/packages/frontend/themes`.

Current upstream themes:

- `light.scss`
- `dark.scss`
- `darkpurple.scss`
- `dark_amoled.scss`
- `dev_minimal.scss`
- `dev_rocket.scss`

BMChat approach:

1. Add BMChat built-in themes, for example `bmchat_light.scss` and `bmchat_dark.scss`.
2. Keep custom theme support through the existing `custom-themes` folder.
3. Replace default theme metadata names with BMChat-visible names only for BMChat themes.
4. Replace background assets referenced by `$bgImage` with BMChat backgrounds.
5. Keep `_themebase.scss` mostly upstream-compatible to reduce merge conflicts.
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

BMChat approach:

1. Introduce BMChat color aliases while preserving upstream names initially, for example map `delta_primary` to BMChat primary until a broader rename is safe.
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

BMChat approach:

1. Replace app icons and logo image sets.
2. Replace light/dark chat backgrounds.
3. Update color asset catalogs for reaction, accent, and surface colors.
4. Update display names and localized permission prompts.
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
