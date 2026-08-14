# SwiftSlate ProGuard Rules

# Keep the accessibility service (instantiated by Android framework via reflection)
-keep class com.musheer360.swiftslate.service.AssistantService { <init>(); }

# Keep preview-only whitelist-compat services: their class names are the whole point
# (WeChat matches them by exact fully-qualified name), so R8 must not rename them.
-keep class com.google.android.accessibility.selecttospeak.SelectToSpeakService { <init>(); }
-keep class com.dianming.phoneapp.MyAccessibilityService { <init>(); }

# Keep enum values used in JSON serialization via CommandType.valueOf()
-keepclassmembers enum com.musheer360.swiftslate.model.CommandType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Preserve line numbers for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Log.e: it carries the SwiftSlateDiag diagnostics used for field debugging.
-keepclassmembers class android.util.Log {
    public static int e(...);
}

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
