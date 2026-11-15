# Vendored upstream: RikkaApps/Shizuku-API

This directory is a vendored copy of <https://github.com/RikkaApps/Shizuku-API>
at commit `a27f6e4151ba7b39965ca47edb2bf0aeed7102e5` ("Bump patch version to 6"),
licensed under the MIT License (see `LICENSE`).

It replaced a git submodule whose URL was SSH-only (`git@github.com:`), which
broke clean clones for anyone without GitHub SSH keys, and which could not carry
the identity and authorization changes below.

## Local modifications (Victorious Framework Work)

Every modified file carries a `// Victorious:` comment at the change. Summary:

* `provider/.../ShizukuProvider.java`, `provider/AndroidManifest.xml`:
  manager application id, permission name and binder extra key come from
  the Victorious identity instead of upstream's.
* `aidl/.../IShizukuService.aidl`: additive `bridge*` methods (new transaction
  codes only; no existing code changed).

Upstream copyright and license headers are preserved. To compare with upstream:
`git diff --no-index <upstream-checkout> api`.
