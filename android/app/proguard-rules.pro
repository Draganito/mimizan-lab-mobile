# JNA (UniFFI's Kotlin bindings call Rust through it).
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
# UniFFI generated bindings: keep the library interface and converters.
-keep class ch.bojovic.mimizanlab.engine.** { *; }
