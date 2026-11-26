# spookd

Morphe patches for Instagram, forked from [Piko](https://github.com/crimera/piko).

Patched clients may violate Instagram's terms of use.

## Usage

spookd patches use [Morphe](https://morphe.software).

### Morphe Manager

Add spookd as a patch source in Morphe Manager:

<a href="https://morphe.software/add-source?github=hawkff/spookd">
  <img src="docs/images/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

Then patch Instagram:
1. Tap the Instagram icon in Morphe.
2. Download the original APKM file from APKMirror. Don't split or modify it; Morphe patches the APKM directly.
3. Wait for patching to finish, then install.

### Morphe CLI

```sh
java -jar cli.jar patch --patches patches.mpp input.apkm
```

### Clone

The Clone patch installs Instagram as a separate app. Its default package is `com.instagram.android.spookd` and its default label is `spookd Instagram`. A clone patched with these defaults installs as a new app next to any earlier clone, so export settings in the old clone and import them in the new one.

## Patches

<!-- PATCHES_START -->
The patch list is generated with each release.
<!-- PATCHES_END -->

## Building

Follow the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation).

## Credits

spookd is based on [Piko](https://github.com/crimera/piko) by crimera and its contributors, and uses the Piko patches and extension libraries.

## License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

These patches are free software: you can redistribute and modify them under the [GNU General Public License v3](https://www.gnu.org/licenses/gpl.html) or later.
