# Third-party notices

HAM's own code and project-owned icon artwork are licensed under `GPL-3.0-only`. The dependencies below are separately authored and retain their own licenses and attribution. Versions are pinned in `gradle/libs.versions.toml`; Gradle resolves the transitive artifacts at build time. This repository does not claim ownership of those components.

| Component family | Role | License and upstream |
| --- | --- | --- |
| AndroidX Core, Activity, Lifecycle, Jetpack Compose, WorkManager, Room, graphics path, and support libraries | Android UI/runtime, queued work and graphics JNI | Apache-2.0; [AndroidX source and notices](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin standard library, compiler plugin, and coroutines | Language/runtime/build | Apache-2.0; [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Kotlinx Serialization Core 1.7.3 | Transitive iCal4j runtime | Apache-2.0; [Kotlinx Serialization source and license](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt). The resolved module POM declares Apache-2.0. |
| OkHttp and Okio | HTTP and I/O | Apache-2.0; [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio) |
| iCal4j 4.4.0 | On-device iCalendar parsing | BSD-3-Clause; [iCal4j tagged source](https://github.com/ical4j/ical4j/tree/ical4j-4.4.0). Exact license: `third_party_licenses/calendar-parser/ical4j-4.4.0-LICENSE.txt`. |
| SLF4J API 2.0.12 | iCal4j logging API | MIT; [SLF4J](https://www.slf4j.org/license.html). Exact JAR license: `third_party_licenses/calendar-parser/slf4j-api-2.0.12-LICENSE.txt`. |
| Commons Codec 1.17.0 and Commons Lang 3.18.0 | iCal4j utilities | Apache-2.0; [Codec](https://commons.apache.org/proper/commons-codec/), [Lang](https://commons.apache.org/proper/commons-lang/). Exact JAR licenses and NOTICE files: `third_party_licenses/calendar-parser/`. |
| ThreeTen Extra 1.8.0 | iCal4j date and time support | BSD-3-Clause; [ThreeTen Extra](https://github.com/ThreeTen/threeten-extra). Exact JAR license: `third_party_licenses/calendar-parser/threeten-extra-1.8.0-LICENSE.txt`. |
| Tesseract4Android 4.9.0 and Tesseract 5.5.1 | On-device screenshot OCR | Apache-2.0; [Tesseract4Android 4.9.0 release](https://github.com/adaptech-cz/Tesseract4Android/releases/tag/4.9.0), [Tesseract](https://github.com/tesseract-ocr/tesseract). License texts: `third_party_licenses/tesseract4android-4.9.0/`. |
| Leptonica 1.85.0, bundled by the OCR library | Image processing for OCR | BSD-2-Clause; [Leptonica license](https://www.leptonica.org/about-the-license.html). License text: `third_party_licenses/tesseract4android-4.9.0/LEPTONICA-LICENSE.txt`. |
| libjpeg v9f and libpng 1.6.48, bundled by the OCR library | Image decoding for OCR | Their respective upstream licenses; [Tesseract4Android dependency list](https://github.com/adaptech-cz/Tesseract4Android#dependencies), [libjpeg](https://www.ijg.org/), [libpng](http://www.libpng.org/pub/png/libpng.html). License texts: `third_party_licenses/tesseract4android-4.9.0/`. |
| tessdata_fast English trained model (`eng.traineddata`) | Bundled English OCR data | Apache-2.0; [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast). SHA-256 `7D4322BD2A7749724879683FC3912CB542F19906C83BCC1A52132556427170B2` |
| JetBrains annotations and JSpecify | Transitive annotations | Apache-2.0; [JetBrains](https://github.com/JetBrains/java-annotations), [JSpecify](https://github.com/jspecify/jspecify) |
| Guava ListenableFuture 1.0 | Transitive future interface | Apache-2.0; [Guava source and license](https://github.com/google/guava/blob/master/LICENSE) |
| JUnit 4 | Unit tests only | Eclipse Public License 1.0; [JUnit 4](https://github.com/junit-team/junit4) |
| Gradle wrapper | Build tool bootstrap | Apache-2.0; [Gradle](https://github.com/gradle/gradle) |

The full Apache License 2.0 text is at <https://www.apache.org/licenses/LICENSE-2.0>. JUnit 4's Eclipse Public License 1.0 text is at <https://www.eclipse.org/legal/epl-v10.html>. Dependency artifacts may contain their own copyright and NOTICE files; those upstream notices remain applicable. Before any Stable or store release, re-resolve the exact runtime graph and review artifact-specific notices and distribution requirements.

The calendar-parser license files above were taken from the resolved release-runtime JARs in the Gradle cache, except iCal4j's license, which came from its exact `ical4j-4.4.0` source tag. Preserve these files unchanged in any audited corresponding-source publication and make their notices available with a distributed APK.
