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
- `canShowKeyboard` controls whether action-page editors can connect to Wisp through
  `ui.showKeyboard()`. It has no effect on an optional settings page.
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
await wisp.ui.showKeyboard();
await wisp.ui.hideKeyboard();
await wisp.ui.setExpanded(true);
await wisp.ui.close();
const environment = await wisp.ui.getEnvironment();
```

The API surface is:

| Method | Resolves with | Availability |
| --- | --- | --- |
| `settings.get(key)` | Declared value or `null` | Action and settings pages |
| `settings.set(key, value)` | `true` | Action and settings pages |
| `storage.get(key)` | Stored value or `null` | Action and settings pages |
| `storage.set(key, value)` | `true` | Action and settings pages |
| `storage.remove(key)` | `true` | Action and settings pages |
| `network.fetch(request)` | Text or media response object | Action and settings pages |
| `keyboard.insertText(text)` | `true` | Action page with permission |
| `keyboard.insertMedia(handle, mimeType)` | `true` | Action page with permission |
| `keyboard.startVoiceInput()` | Transcript or `null` | Action page with permission |
| `ui.showKeyboard()` | `true` | Action page with `canShowKeyboard` |
| `ui.hideKeyboard()` | Whether an inline keyboard was dismissed | Action page |
| `ui.setExpanded(expanded)` | Completion acknowledgement | Action page |
| `ui.close()` | `true` | Action page |
| `ui.getEnvironment()` | Environment object | Action and settings pages |

`settings` keys must be declared in the manifest. `storage` keys are private to the add-on and may
contain only letters, digits, `_`, `.`, and `-`. Storage is limited to 256 keys, 1 MiB per value,
and 5 MiB total per add-on.

`ui.getEnvironment()` reports whether the keyboard is shown and supplies current keyboard/theme
colors. The host also dispatches `wisp:environment` with the same object when those values change,
allowing a package panel to visually match native keyboard actions.

```js
const environment = await wisp.ui.getEnvironment();
// {
//   keyboardShown: false,
//   dark: true,
//   keyboardContainer: "#......",
//   onKeyboardContainer: "#......",
//   primary: "#......",
//   onSurface: "#......",
//   error: "#......",
//   surfaceContainerHighest: "#......"
// }

window.addEventListener("wisp:environment", event => {
  document.documentElement.dataset.theme = event.detail.dark ? "dark" : "light";
});
```

### Focused keyboard flow

Call `ui.showKeyboard()` only after an editable element is focused. The action manifest must set
`canShowKeyboard` to `true`.

```js
const query = document.querySelector("#query");

query.addEventListener("focus", async () => {
  try {
    await wisp.ui.showKeyboard();
  } catch (error) {
    console.error(error);
  }
});

async function submit() {
  await search(query.value);
  await wisp.ui.hideKeyboard();
}
```

While the field owns the keyboard, Wisp keeps the add-on at `compactHeightDp` so the editor remains
visible. Dismissing the keyboard blurs the connected field and restores the action panel without
closing it. Back, the panel header control, tapping outside the action, and `ui.hideKeyboard()` all
dismiss this inline keyboard without closing the panel. A later interaction uses the panel's normal
close or resize behavior.

The host disconnects the add-on input connection when the panel is disposed or the keyboard is
dismissed. Add-ons should not retain focus as application state; restore focus only after an
explicit user interaction.

### Network and GIF/media flow

Pages have no direct remote loading: CSP sets `connect-src 'none'`, and the WebView blocks non-local
requests. Use `wisp.network.fetch`.

Fixed HTTPS origins go in `networkOrigins`. `allowUserOrigins` permits the add-on to request an exact
additional origin at first use (for example, a user-entered self-hosted server).
`allowInsecureHttp` permits HTTP only after the extra warning. Redirects are limited and
cross-origin redirects require an existing grant. `Host`, `Cookie`, and `Origin` request headers
are controlled by Wisp.

`network.fetch` accepts `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, and `OPTIONS`. `GET` and
`HEAD` cannot include a body. `responseType` must be `text` (the default) or `media`. A text
response is limited to 10 MiB and has this shape:

```js
{
  status: 200,
  contentType: "application/json; charset=utf-8",
  body: "{\"results\":[]}"
}
```

Requests may follow at most five HTTP redirects. Redirects to another origin must already be
approved, and Wisp removes authorization headers when crossing origins.

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
each add-on has a 100 MiB least-recently-used cache. Wisp records and validates the downloaded
media type; the `mimeType` argument supplied to `insertMedia` is not trusted as an override. The
Promise rejects if the current editor does not accept the media.

## Updates and lifecycle

`id` is the stable identity for settings, storage, permission grants, media, and action placement.
Increase `versionCode` whenever the package contents change. Wisp recreates a panel when its
installed version changes, so add-ons must persist durable state through `wisp.settings` or
`wisp.storage` instead of relying on page globals.

Bundled add-ons are replaced when a package with the same ID has a different `versionCode`, and
sensitive permission grants are cleared so the updated package asks again at first use. An open
action is closed if its add-on is removed or replaced, preventing an old WebView from continuing
against new package files. Imported user add-ons currently cannot replace an installed package;
remove the existing add-on before importing the new ZIP.

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
