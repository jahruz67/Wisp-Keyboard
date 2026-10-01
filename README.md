# Wisp Keyboard

Wisp Keyboard is an independent, community-maintained Android keyboard focused on privacy, offline use, and a clean, customizable typing experience.

This project is a modified fork of [FUTO Keyboard](https://github.com/futo-org/android-keyboard), which is itself based on [LatinIME, the Android Open Source Project keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME). Wisp Keyboard is not affiliated with, endorsed by, or maintained by FUTO Holdings, Inc. The project has been renamed and visually rebranded to avoid confusion with the upstream application.

## Highlights

- Local-first typing, suggestions, and voice input
- Swipe typing and multilingual layouts
- Custom themes, actions, clipboard tools, and add-ons
- Importable dictionaries and language models
- No advertising or sale of personal data

## Downloads and support

Releases, source code, bug reports, and feature requests belong in this repository:

- [Releases](https://github.com/jahruz67/Wisp-Keyboard/releases)
- [Issues](https://github.com/jahruz67/Wisp-Keyboard/issues)

Please do not use FUTO's support channels for Wisp-specific bugs or feature requests.

## Building

Clone the repository with its submodules:

```sh
git clone --recursive https://github.com/jahruz67/Wisp-Keyboard.git
cd Wisp-Keyboard
```

If the repository was cloned without submodules, initialize them separately:

```sh
git submodule update --init --recursive
```

Open the project in Android Studio, or build it from the command line:

```sh
./gradlew assembleUnstableDebug
./gradlew assembleStableRelease
```

## Contributing

Contributions are welcome. Open an issue before a large change so the approach can be discussed, then submit a pull request to this repository.

The upstream translation, layout, model, and library repositories remain connected as submodules. Changes intended for those upstream projects should be proposed to their respective maintainers.

## License and upstream credit

Wisp Keyboard contains modified FUTO Keyboard code and remains subject to the [FUTO Source First License 1.1-kb](LICENSE.md), along with the third-party notices in [NOTICE](NOTICE) and [java/NOTICE](java/NOTICE). The license permits non-commercial modification and free non-commercial distribution, requires a prominent modification notice, and requires the upstream payment functionality and licensor notices to remain in distributed copies.

This repository is intended for non-commercial use and distribution. It is not the official FUTO Keyboard project. Thanks to FUTO Keyboard, LatinIME/AOSP, and all upstream contributors whose work made this fork possible.
