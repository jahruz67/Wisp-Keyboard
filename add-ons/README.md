# Wisp Keyboard add-ons

Wisp add-ons are ZIP packages containing a manifest and local web assets. They run in a restricted
WebView and can interact with the keyboard only through capabilities declared in `addon.json` and
approved by the user.

Add-ons cannot ship Kotlin, Java, DEX, APK, or native libraries. Version 1 contributes one keyboard
action panel and optional settings. It cannot replace keyboard rows, inspect surrounding editor
text, read the clipboard, install applications, or access arbitrary app files.

## Importing an add-on

1. Open **Settings → Add-ons**.
2. Tap **Import add-on**.
3. Select a ZIP whose root contains `addon.json` (not a ZIP containing an extra parent folder).
4. Review the unsigned-package warning, author, version, network origins, and requested
   capabilities.
5. Tap **Install**. Sensitive capabilities ask once more when first used.

Importing an ID that is already installed is rejected. IDs under `org.futo.*` are reserved.
Deleting a user add-on erases its files, settings, secrets, grants, cached media, and keyboard
action placements. System add-ons are built into Wisp, cannot be imported, and cannot be
uninstalled.

## Source and ZIP layout

```text
my-addon/
├── addon.json
├── icon.png                 # PNG, WebP, or simple path-only SVG
├── action/
│   ├── index.html
│   ├── app.js
│   └── style.css
└── settings/                # optional advanced settings page
    └── index.html
```

ZIP the *contents* of `my-addon`, so `addon.json` is at the archive root. Packages are limited to
25 MiB compressed, 100 MiB expanded, and 1,000 entries. Paths containing traversal, absolute
paths, or backslashes are rejected.

The repository build packages every child folder containing `addon.json`:

```shell
./gradlew packageAddons
```

Generated ZIPs are written under `build/generated/addon-assets/addons` and bundled into the app.
Only repository-bundled packages may set `"system": true`.

## Manifest schema v1

```json
{
  "schemaVersion": 1,
  "id": "com.example.gifsearch",
  "name": "GIF Search",
  "description": "Search for and insert GIFs",
  "author": "Example Author",
  "versionCode": 1,
  "versionName": "1.0.0",
  "icon": "icon.png",
  "system": false,
  "action": {
    "entrypoint": "action/index.html",
    "canShowKeyboard": true,
    "preferredHeight": "adaptive",
    "compactHeightDp": 160,
    "expandedHeightDp": 280
  },
  "settings": [],
  "settingsEntrypoint": "settings/index.html",
  "permissions": {
    "networkOrigins": ["https://api.example.com"],
    "allowUserOrigins": false,
    "allowInsecureHttp": false,
    "insertText": false,
    "insertMedia": true,
    "voiceInput": false
  }
}
```

- `schemaVersion` must be `1`.
- `id` uses lowercase reverse-domain notation and is the permanent storage/action identity.
- `versionCode` is a positive integer used for bundled updates; `versionName` is display text.
- `icon`, action `entrypoint`, and optional `settingsEntrypoint` are package-relative files.
- `preferredHeight` is `compact`, `expanded`, or `adaptive`.
- `compactHeightDp` and `expandedHeightDp` control adaptive action-panel heights.
- `system` must be false or omitted in imported packages.

### Native settings

The optional `settings` array lets the host render ordinary settings without trusting page UI:

```json
{
  "key": "safeSearch",
  "type": "boolean",
  "title": "Safe search",
  "description": "Hide explicit results",
  "default": "true"
}
```

Supported types are `boolean`, `string`, `secret`, `url`, and `select`. A select supplies:

```json
"options": [
  {"value": "small", "label": "Small"},
  {"value": "large", "label": "Large"}
]
```

Conditional visibility is optional:

```json
"visibleWhen": {"key": "provider", "values": ["custom"]}
```

The advanced settings entry point, when present, receives the settings/storage/network portions of
the same sandbox API. Keyboard insertion and voice operations fail outside an action panel.

## JavaScript API

The host injects `window.wisp` after the local page loads. Every call returns a Promise and rejects
with an `Error` when validation, permission, network, or host operations fail.

```js
await wisp.settings.get("safeSearch");
await wisp.settings.set("safeSearch", "true");

await wisp.storage.get("lastQuery");
await wisp.storage.set("lastQuery", "cats");
await wisp.storage.remove("lastQuery");

const response = await wisp.network.fetch({
  url: "https://api.example.com/search?q=cats",
  method: "GET",
  headers: {"Accept": "application/json"},
  responseType: "text"
});

await wisp.keyboard.insertText("hello");
const transcript = await wisp.keyboard.startVoiceInput();
await wisp.ui.setExpanded(true);
await wisp.ui.close();
const environment = await wisp.ui.getEnvironment();
```

`settings` keys must be declared in the manifest. `storage` keys are private to the add-on and may
contain only letters, digits, `_`, `.`, and `-`.

`ui.getEnvironment()` reports whether the keyboard is shown and supplies current keyboard/theme
colors. The host also dispatches `wisp:environment` with the same object when those values change,
allowing a package panel to visually match native keyboard actions.

### Network and GIF/media flow

Pages have no direct remote loading: CSP sets `connect-src 'none'`, and the WebView blocks non-local
requests. Use `wisp.network.fetch`.

Fixed HTTPS origins go in `networkOrigins`. `allowUserOrigins` permits the add-on to request an exact
additional origin at first use (for example, a user-entered self-hosted server).
`allowInsecureHttp` permits HTTP only after the extra warning. Redirects are limited and
cross-origin redirects require an existing grant. `Host`, `Cookie`, and `Origin` request headers
are controlled by Wisp.

For a GIF or image, request an opaque cached handle:

```js
const media = await wisp.network.fetch({
  url: gif.downloadUrl,
  responseType: "media"
});

preview.src = media.previewUrl; // local sandbox URL
await wisp.keyboard.insertMedia(media.handle, media.mimeType);
```

An add-on can use only handles it downloaded. Individual media responses are limited to 25 MiB and
each add-on has a 100 MiB least-recently-used cache.

## Security behavior

- JavaScript, file/content access, DOM persistence, cookies, popups, external navigation, remote
  subresources, frames, forms, and mixed content are disabled or blocked.
- Package resources are served from a synthetic local HTTPS origin with a restrictive CSP.
- Persistent settings, state, permission grants, and media are namespaced by add-on ID.
- Network, text insertion, media insertion, and voice input require both manifest declaration and
  a stored first-use approval.
- Add-ons never receive surrounding editor text. They see only data entered in their panel or
  returned by an explicitly requested host operation.

## System and user add-ons

Translate is a native system add-on. It uses Wisp's original Translate action, providers,
settings, voice flow, and saved values. It appears in the Add-ons overview with a **System
add-on** tag, but it is not a ZIP package and has no install or uninstall action.

ZIP packages are user add-ons. A GIF search/insertion add-on, for example, can use native
settings, `network.fetch`, media handles, and `keyboard.insertMedia`, and the user can remove it
at any time.
