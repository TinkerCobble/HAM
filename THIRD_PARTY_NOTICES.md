# Third-party notices

HAM's own code and project-owned icon artwork are licensed under `GPL-3.0-only`. The dependencies below are separately authored and retain their own licenses and attribution. Versions are pinned in `gradle/libs.versions.toml`; Gradle resolves the transitive artifacts at build time. This repository does not claim ownership of those components.

| Component family | Role | License and upstream |
| --- | --- | --- |
| AndroidX Core, Activity, Lifecycle, and Jetpack Compose | Android UI/runtime | Apache-2.0; [AndroidX source and notices](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin standard library, compiler plugin, and coroutines | Language/runtime/build | Apache-2.0; [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| OkHttp and Okio | HTTP and I/O | Apache-2.0; [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio) |
| JetBrains annotations, JSpecify, and Guava annotation/marker libraries | Transitive annotations | Apache-2.0; [JetBrains](https://github.com/JetBrains/java-annotations), [JSpecify](https://github.com/jspecify/jspecify), [Guava](https://github.com/google/guava) |
| JUnit 4 | Unit tests only | Eclipse Public License 1.0; [JUnit 4](https://github.com/junit-team/junit4) |
| Gradle wrapper | Build tool bootstrap | Apache-2.0; [Gradle](https://github.com/gradle/gradle) |

The full Apache License 2.0 text is at <https://www.apache.org/licenses/LICENSE-2.0>. JUnit 4's Eclipse Public License 1.0 text is at <https://www.eclipse.org/legal/epl-v10.html>. Dependency artifacts may contain their own copyright and NOTICE files; those upstream notices remain applicable. Before any Stable or store release, re-resolve the exact runtime graph and review artifact-specific notices and distribution requirements.
