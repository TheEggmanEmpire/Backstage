# AIDL entry points and platform components are rooted by the manifest/generated Binder stubs.
-keepclassmembers class com.eggman.backstage.companion.CompanionEvent {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Shizuku instantiates its user service by the package and class name.
-keep class com.eggman.backstage.privileged.ShellUserService {
    public <init>();
}
