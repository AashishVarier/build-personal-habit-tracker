# Room, Hilt, WorkManager, Glance and kotlinx.serialization ship their own consumer rules.
# Keep this file for app-specific rules only.

# Navigation routes are @Serializable objects resolved by name.
-keepclassmembers @kotlinx.serialization.Serializable class com.variableduck.build.habittracker.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
