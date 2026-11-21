# R8 rules added to the Dextop :app release build by backstage/dextop-integration.gradle.

# The Backstage agent library: components are referenced from the merged manifest, the init provider
# reaches the glue reflectively, and tool/provider schemas are (de)serialised by name.
-keep class com.eggman.backstage.** { *; }
-keepnames class com.eggman.backstage.**

# Glue compiled into :app (backstage/dextop-glue/kotlin). BackstageInitProvider calls
# moe.n4tsu.dextop.BackstageDesktopBridge.install(Context) via reflection.
-keep class moe.n4tsu.dextop.Backstage** { *; }

# Kotlin metadata keeps reflection-friendly signatures of the kept classes.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Kotlin*
