# Build and release

The current Android build is intentionally reproducible.

Tooling pinned for the current build:
- Android Gradle Plugin 9.5.2
- Gradle 9.6
- JDK 17
- Android SDK 36
- Build Tools 36.0.0
- NDK 28.2.13676358
- stable Rust + cargo-ndk

GitHub Actions:
1. checkout;
2. JDK 17;
3. Android SDK/NDK;
4. Rust stable;
5. cargo-ndk native build for arm64-v8a and x86_64;
6. Gradle unit tests;
7. Android lint;
8. debug APK assembly;
9. APK artifact upload.

A production release must use GitHub Actions secrets for signing. Never commit keystores or passwords.
