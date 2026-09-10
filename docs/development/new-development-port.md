# Improvements adapted from new-development

Source: `new-development` at `e9d24ae5255bdf5c1d7de983759f6b335704f850`.
Target baseline: `codex/aster-webkit-desktop` at
`4fa49aca164b3820719321a53df58842ff4028d8`.

This is a selective port. The source branch contains an earlier engine snapshot
and installer payloads for the historical Qt/Chromium v16 application. Its updated
Windows ZIP also contains theme, menu and widget changes. Copying those installers
unchanged would install that older edition.

| Source improvement | Integration into the current original-engine edition |
| --- | --- |
| Single root Linux installer, launch/removal options | Root `install-linux.sh` installs/updates the published Flatpak, verifies SHA-256, supports local bundles, checks status and preserves data on uninstall. Flatpak handles runtime dependencies. |
| Graphical Windows setup, location and shortcut choices | Extend the existing Inno Setup installer with system light/dark styling, Aster branding, visible folder selection and separate Start menu/desktop tasks. Keep the existing app/profile identity. |
| Aster ICO and PNG assets | Copy the source assets unchanged; use them for the native launcher, desktop window and setup wizard. |
| Dark surfaces and cyan accents inside the v16 ZIP | Adapt the palette to native Swing chrome, hover states and cards; retain readable text sizes and page-owned colors. |
| Compact File / View / Tools menus and shortcuts | Replace the large popup tiles with native keyboard-accessible menus. Keep prior menu destinations and expose existing save/download/reading actions; add History, Bookmarks and Settings shortcuts. Escape exits full screen. |
| Root build/editor ignore rules | Bring over the source `.gitignore`. |

The current engine, desktop HTTP/media components, site protection, Android code,
existing profiles and release identity stay on the Codex baseline. Historical kits
are retained. The source branch's built EXE, Qt application ZIP and embedded Python
payload are not shipped as original-engine packages. The separate WebKit updater
continues to use `installers/install-linux.sh`.

Qt-specific printing, page-source, new-window and widget implementations were not
copied into Swing. The bundled Manrope font was not added; native system fonts
remain in use. This port does not claim feature parity with that older application
or completion of modern web compatibility.

## Verification

- Shell subprocess tests exercise local and published-download installation,
  checksums, malformed manifests, download/install failures, launch ordering,
  unsupported CPUs, status and scoped uninstall commands.
- Existing desktop tests render the changed workspace and cover navigation,
  downloads, website data, scripting, protection, layout and reading tools.
- The Windows CI test exercises custom-directory installation, remembered
  shortcuts, application replacement, native launch and profile retention through
  uninstall/reinstall using the real setup EXE.
- The Flatpak CI job uses the root script for real installation, replacement,
  status, uninstall and reinstall, while checking retained profile data and the
  native application window. New releases include the script in their checksums.

Native Windows/Flatpak results must be read from the workflow for the exact commit;
subprocess fixtures alone do not establish platform validation. Physical Steam
Deck testing remains outstanding.
