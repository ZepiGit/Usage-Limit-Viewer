# Provider artwork

Provider logos are bundled locally and have transparent backgrounds. Rendering them in the app or a widget does not contact an external website. The marks remain the property of their respective owners.

## Source and choices

The source is the user-supplied `lobehub-icons.zip` archive, received on 12 September 2026. Its 19 SVGs are preserved in `assets/providers/lobehub/`; `assets/providers/catalog.json` records the choices. They correspond to the [LobeHub icon library](https://lobehub.com/icons).

| App provider | Default | Other available artwork |
| --- | --- | --- |
| Claude | Claude Code, color | Claude Code monochrome and wordmark; Claude color, monochrome and wordmark |
| OpenAI Codex | OpenAI | OpenAI wordmark |
| Grok | Grok | Grok wordmark |
| Antigravity | Gemini, color | Gemini monochrome and wordmark; Antigravity color, monochrome and wordmark |
| Kimi | Kimi, color | Kimi monochrome and wordmark |

Claude/Claude Code share one provider identity. Antigravity/Gemini also share one provider identity. Changing artwork never changes the account, authentication flow or quota parser.

## App and widget integration

Settings → Provider icons presents the same choices on Android and iOS. Each provider has one saved selection that applies across the app and its widgets. Unknown or removed choice IDs fall back to the provider default.

Android uses `ProviderIconCatalog`, DataStore preferences and `drawable-nodpi/icon_*.png`. iOS uses `ProviderIconCatalog`, persisted `AppSettings.providerIcons` and `ProviderIcon*.imageset` assets shared with the widget extension. Updated icon choices are included when publishing the widget cache.

The packaged PNGs preserve transparency, proportions and source colors. Monochrome SVG `currentColor` paths are rendered white for the existing dark interface. Vector sources remain available for future theme-specific exports. No opaque badge is baked into the artwork.

All 19 Android exports and all 19 iOS exports were checked for an alpha channel and transparent pixels. Visual checks covered the default marks, alternate Claude artwork, the settings picker, account lists and Android home-screen widgets.
