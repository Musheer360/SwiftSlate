# SwiftSlate ProGuard Rules

# Keep the accessibility service (instantiated by Android framework via reflection)
-keep class com.musheer360.swiftslate.service.AssistantService { *; }

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
