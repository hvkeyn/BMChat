# BMChat Fork Strategy

BMChat is managed as a set of platform forks that stay close to Delta Chat upstream.

## Repositories

| Local path | Upstream | Purpose |
| --- | --- | --- |
| `clients/desktop` | `https://github.com/deltachat/deltachat-desktop.git` | Windows, Linux, macOS desktop client |
| `clients/android` | `https://github.com/deltachat/deltachat-android.git` | Android client |
| `clients/ios` | `https://github.com/deltachat/deltachat-ios.git` | iOS client |

## Branch Model

- Keep upstream default branch history intact.
- Use `origin` for the cloned upstream repository initially.
- Add/keep an `upstream` remote pointing to the original Delta Chat repository.
- Use `bmchat/main` as the BMChat working branch in every client.
- Keep large refactors out of the first branding pass to preserve mergeability.

## Update Flow

1. Fetch upstream changes in each client.
2. Rebase or merge upstream into `bmchat/main` depending on conflict volume.
3. Resolve conflicts in BMChat branding/localization files.
4. Run the platform checks documented in `docs/build-matrix.md`.
5. Update `docs/brand-map.md` if upstream moves branding or localization files.

## First-Pass Scope

The first implementation pass should change visible identity and documentation, not protocol behavior:

- Product names and window/app titles.
- Icons, tray icons, splash/launch assets.
- Application IDs and bundle IDs where needed for side-by-side install.
- Store metadata and screenshots source locations.
- Russian localization completeness.
- Desktop built-in themes and mobile brand colors.

Protocol, database, encryption, contact, transport, and account logic should remain upstream-compatible unless there is a specific BMChat product requirement.
