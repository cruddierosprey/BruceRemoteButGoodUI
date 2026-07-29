# Bruce Remote prototype: source and safety

The Android application, firmware patch engine, firmware source changes, build
scripts, and the exact Bruce source checkout used for these local prototype
artifacts are included in this workspace.

The Bruce firmware is licensed under AGPL-3.0-or-later. Before redistributing
the modified binaries or their binary patches, publish the complete
corresponding source and build scripts at the HTTPS `source_url` named by the
signed package (or update and re-sign that field to point at your published
source).

The included signing key is a local development key, not a production release
key. Replace it and pin only the replacement public key before distributing an
APK.

Flashing can fail if USB power or the cable disconnects. The ESP ROM bootloader
normally permits recovery, but no flashing process can promise zero risk.
Back up anything important first. The app must refuse Secure Boot, flash
encryption, secure-download mode, the wrong chip, an unexpected flash size, an
unknown base hash, or a failed post-write verification.
