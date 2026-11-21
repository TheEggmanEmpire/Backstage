# Consumer R8 rules of the :backstage library (merged into the app's release build).

# Components are referenced from the merged manifest; the init provider reaches the Dextop glue reflectively.
-keep class com.eggman.backstage.init.BackstageInitProvider { <init>(); }

# Vosk (wake word) binds through JNA, which looks classes, fields and methods up by name at runtime; neither
# AAR ships R8 rules.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
