# Active Context

Current focus: BMChat workspace is bootstrapped as a maintainable multi-platform Delta Chat fork.

Recent decisions:
- Start with all three clients: desktop, Android, and iOS.
- Treat licensing as unresolved for commercial/closed distribution; default to GPL-compatible open fork for desktop and Android.
- Keep upstream mergeability as a primary engineering constraint.
- Create project documentation before deep code modifications.
- Keep core submodules upstream-clean unless a proven user-facing requirement needs a controlled change.

Immediate next steps:
- Decide final public name, package IDs, bundle IDs, logo, and color palette.
- Implement first-pass branding changes in client layers.
- Add localization checker scripts before release.
- Prepare platform build environments and signing secrets.

Open questions:
- Final public name: `BMChat`, `BroMoreChat`, or a combined naming scheme.
- Final package IDs and bundle IDs.
- Final logo, color palette, and store identity.
- Distribution model and legal review outcome.
