# Aggressive R8 optimizations for ultra-small APK size and fast execution
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'o'

# Strip verbose/debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep RecyclerView layout manager constructors invoked via reflection if any
-keep public class * extends androidx.recyclerview.widget.RecyclerView$LayoutManager {
    public <init>(...);
}
