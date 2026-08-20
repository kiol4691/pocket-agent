# Third-party notices

This project incorporates the following third-party works. Licenses are reproduced
below or linked at the given URLs. Full license texts are available at each link.

## DeepSeek Harness — design inspiration
- **Project:** https://github.com/deepseek-ai/deepseek-harness
- **License:** MIT — Copyright (c) 2026 DeepSeek
- **Use:** Pocket Agent is an independent re-implementation of the minimal-mode
  tool-calling loop concept. It shares no code with the upstream project.

## proroot
- **Project:** https://github.com/coderredlab/proroot
- **License:** Proprietary — free to use; redistribution of modified binaries is
  not permitted.
- **Use:** Unmodified, prebuilt `arm64-v8a` libraries are bundled in the APK and
  used to run the Linux userspace. See the repo's release notes for terms.

## Markwon (markdown rendering incl. tables, strikethrough, task lists, LaTeX)
- **Project:** https://github.com/noties/Markwon
- **License:** Apache-2.0 — see https://www.apache.org/licenses/LICENSE-2.0
- (also incorporates CommonMark-java, Apache-2.0)

## JLaTeXMath (formula rendering via Markwon ext-latex)
- **Project:** https://github.com/opencollab/jlatexmath
- **License:** MIT / GPL-2.0 dual — used here as a prebuilt dependency.

## OkHttp
- **Project:** https://github.com/square/okhttp
- **License:** Apache-2.0

## AndroidX (core, appcompat, constraintlayout) & Material Components
- **Project:** https://developer.android.com/jetpack
- **License:** Apache-2.0

## Ubuntu base rootfs
- **Project:** https://cdimage.ubuntu.com/ubuntu-base/releases/
- **License:** Ubuntu distribution terms (components under GPL/CC).
- **Use:** Bundled in this repository as a gzip-compressed tarball
  (`app/src/main/assets/ubuntu-base-arm64.bin`, extracted by the app at first run).
  Tsinghua / USTC / cdimage mirrors are referenced in code as fallback download sources.