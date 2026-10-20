# Third-party notices

HAM's own code and project-owned icon artwork are licensed under `GPL-3.0-only`. The dependencies below are separately authored and retain their own licenses and attribution. Versions are pinned in `gradle/libs.versions.toml`; Gradle resolves the transitive artifacts at build time. This repository does not claim ownership of those components.

| Component family | Role | License and upstream |
| --- | --- | --- |
| AndroidX Core, Activity, Lifecycle, and Jetpack Compose | Android UI/runtime | Apache-2.0; [AndroidX source and notices](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin standard library, compiler plugin, and coroutines | Language/runtime/build | Apache-2.0; [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| OkHttp and Okio | HTTP and I/O | Apache-2.0; [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio) |
| Tesseract4Android 4.9.0 and Tesseract | On-device screenshot OCR | Apache-2.0; [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android), [Tesseract](https://github.com/tesseract-ocr/tesseract) |
| Leptonica, bundled by the OCR library | Image processing for OCR | BSD-2-Clause; [Leptonica license](https://www.leptonica.org/about-the-license.html) |
| libjpeg v9f and libpng 1.6.48, bundled by the OCR library | Image decoding for OCR | Their respective upstream licenses; [Tesseract4Android dependency list](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0#dependencies), [libjpeg](https://www.ijg.org/), [libpng](http://www.libpng.org/pub/png/libpng.html). The exact upstream license materials are preserved below. |
| tessdata_fast English trained model (`eng.traineddata`) | Bundled English OCR data | Apache-2.0; [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast). SHA-256 `7D4322BD2A7749724879683FC3912CB542F19906C83BCC1A52132556427170B2` |
| JetBrains annotations, JSpecify, and Guava annotation/marker libraries | Transitive annotations | Apache-2.0; [JetBrains](https://github.com/JetBrains/java-annotations), [JSpecify](https://github.com/jspecify/jspecify), [Guava](https://github.com/google/guava) |
| JUnit 4 | Unit tests only | Eclipse Public License 1.0; [JUnit 4](https://github.com/junit-team/junit4) |
| Gradle wrapper | Build tool bootstrap | Apache-2.0; [Gradle](https://github.com/gradle/gradle) |

The full Apache License 2.0 text is at <https://www.apache.org/licenses/LICENSE-2.0>. JUnit 4's Eclipse Public License 1.0 text is at <https://www.eclipse.org/legal/epl-v10.html>. Dependency artifacts may contain their own copyright and NOTICE files; those upstream notices remain applicable. Before any Stable or store release, re-resolve the exact runtime graph and review artifact-specific notices and distribution requirements.

## OCR binary license materials

For the pinned Tesseract4Android 4.9.0 binary, `third_party_licenses/tesseract4android-4.9.0/` preserves the wrapper and Tesseract Apache-2.0 licenses, Leptonica's BSD notice, libpng's license, and the Independent JPEG Group's README including its LEGAL ISSUES and attribution terms. These were copied unchanged from upstream tag `4.9.0` at commit `15c534717b1cb58261b58d4e4c1200c7f81f668c`. The APK uses this AAR's native libraries; the public source archive and HAM download page provide these license materials alongside the signed APK. They remain upstream-authored and are not relicensed under HAM's GPL.
