# Room, Hilt, WorkManager, Glance and kotlinx.serialization ship their own consumer rules.
# Keep this file for app-specific rules only.

# Navigation routes are @Serializable objects resolved by name.
-keepclassmembers @kotlinx.serialization.Serializable class com.variableduck.build.habittracker.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Glance creates widget click callbacks by reflection with their no-argument constructor.
# Glance's own rule keeps the class but not the constructor, so R8 full mode removes it and
# widget taps silently do nothing in release builds.
-keepclassmembers class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
