# Publishing

Source lives in `源码/` (a git repository with one initial commit and a `v0.2` tag).
The build artifacts live next to it in `release_v0.2/`.

## 1. Push the source

```powershell
cd 源码
# create an empty repository on GitHub first, then:
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main --tags
```

The repository already ignores `runtime/`, `assets/engine.zip`, `out/`, `*.apk` and
`build/debug.keystore`, so no build inputs or signing keys are published.

## 2. Publish the APK

Create a GitHub Release named `v0.2` and upload:

- `release_v0.2/Harness-v0.2.apk`
- `release_v0.2/SHA256SUMS.txt`
- `release_v0.2/RELEASE_NOTES.md`

Or from the command line:

```powershell
gh release create v0.2 release_v0.2/Harness-v0.2.apk `
  release_v0.2/SHA256SUMS.txt release_v0.2/RELEASE_NOTES.md `
  --title "Harness v0.2" --notes-file release_v0.2/RELEASE_NOTES.md
```

## 3. Before making the repository public

- Confirm `git ls-files` contains no keystore, APK, engine archive or runtime binary.
- Confirm no personal data (screenshots, phone numbers, tokens) is tracked.
- Replace the debug signing key with your own before publishing future builds; the
  published APK's signature must stay stable for updates.
- If the build contains GNU bash, satisfy GPLv3 (source offer) or remove `libbash.so`
  and the bash entry from the build script.
