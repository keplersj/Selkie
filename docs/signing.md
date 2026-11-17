# Signing

`keystore/selkie-mvp.jks` (alias `selkie-mvp`, store and key password `selkie-mvp-insecure`) signs
**both** debug and release builds, so APKs from CI install over each other and over local builds.

**This is a public, insecure MVP test key.** It is committed to the repository with its password, so
anyone can produce an APK that Android treats as an update to Selkie, and anyone can replace an installed
Selkie with their own build. Do not rely on the signature for trust.

Before any real release: generate a private key, store it as a CI secret, sign release builds with it,
and tell existing users to reinstall once (Android will not upgrade across keys).

R8 (minify + resource shrinking) is on for release with keep rules for the Termux terminal libraries
(JNI) and kotlinx.serialization in `app/proguard-rules.pro`.
