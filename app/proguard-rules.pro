# Strip verbose/debug/info logging in release; keep Log.w/e for crash triage.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
# kotlinx-serialization, Room, Glance and manifest components ship consumer rules or are kept automatically.
