# SwiftSlate ProGuard Rules

# LSPosed entry point: loaded by name from META-INF/xposed/java_init.list
-keep class com.musheer360.swiftslate.xposed.MainHook { public <init>(); }
-adaptresourcefilecontents META-INF/xposed/java_init.list
-dontwarn io.github.libxposed.api.**
-dontwarn io.github.libxposed.annotation.**

# Bound by the keyboard through an explicit intent
-keep class com.musheer360.swiftslate.service.HookBridgeService { <init>(); }

# Keep enum values used in JSON serialization via CommandType.valueOf()
-keepclassmembers enum com.musheer360.swiftslate.model.CommandType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Preserve line numbers for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
