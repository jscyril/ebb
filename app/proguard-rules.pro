# Strip verbose/debug/info logging in release; keep Log.w/e for crash triage.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
# kotlinx-serialization, Room, Glance and manifest components ship consumer rules or are kept automatically.

# WorkManager (used by Glance widgets) builds input mergers and workers by reflection; R8 full mode dropped
# OverwritingInputMerger's constructor, so every widget update failed and widgets sat on the loading screen.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
