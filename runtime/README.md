# runtime/

Build inputs live here but are not committed (see `.gitignore`).

Required files (produced on an arm64 Android device or arm64 Linux, see
`../docs/BUILD.md`):

```
libnode.so          # node binary, renamed so PackageManager extracts it
libbash.so          # bash binary (optional, for shell tools)
libz.so
libcares.so
libsqlite3.so
libcrypto.so
libssl.so
libicui18n.so
libicuuc.so
libicudata.so
libc++_shared.so
libandroid-support.so
libreadline.so
libncursesw.so
libiconv.so
```

`tools/export-runtime.sh` produces exactly this set from a Termux install.
