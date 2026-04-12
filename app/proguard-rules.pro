# SwiftSlate ProGuard Rules

# Keep the accessibility service (instantiated by Android framework via reflection)
-keep class com.musheer360.swiftslate.service.AssistantService { <init>(); }

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
