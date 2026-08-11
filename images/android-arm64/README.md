# Android ARM64 build image

This image keeps the pinned Cirrus Labs Android SDK 36 base and replaces its x86_64 `aapt2` executable with the AArch64 build from `lzhiyong/android-sdk-tools`.

Versions, source URL, checksum, repository name, and image tag are configured in `image.env`. The downloaded archive is verified before the image is built. The upstream Android SDK tools are licensed under Apache-2.0.

The image does not include an Android NDK. Official NDK r27 packages do not provide a Linux ARM64 host toolchain. Projects that require React Native 0.82+, the New Architecture, or native Android modules must override `ANDROID_IMAGE` with a non-root image containing a compatible NDK, or use an amd64 Kubernetes agent. The build container must not make the system SDK directory writable to install tools at runtime.

The GitHub Actions workflow publishes a Linux ARM64 image to GHCR and records its immutable digest in the build summary. Kubernetes templates must use the digest rather than the mutable tag.
