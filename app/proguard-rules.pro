# JSch (mwiede fork) loads ciphers/kex/mac/hostkey classes reflectively by name.
-keep class com.jcraft.jsch.** { *; }
-keep class com.jcraft.jzlib.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
