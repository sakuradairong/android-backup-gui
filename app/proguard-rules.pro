# ProGuard/R8 rules for Android Backup GUI
# ==========================================

# --- kotlinx.serialization ---
# Keep @SerialName classes and companion serializer fields
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.androidbackupgui.**$$serializer { *; }
-keepclassmembers class com.example.androidbackupgui.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.androidbackupgui.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- NanoHTTPD ---
# NanoHTTPD (package fi.iki.elonen despite Maven group org.nanohttpd)
-keep class fi.iki.elonen.** { *; }

# --- RemoteTransport (WebDAV/SMB) ---
-keep class com.example.androidbackupgui.backup.RemoteTransport { *; }

# --- Data classes (serialization) ---
-keep class com.example.androidbackupgui.backup.ResticProgress { *; }
-keep class com.example.androidbackupgui.backup.BackupSummary { *; }
-keep class com.example.androidbackupgui.backup.ResticSnapshot { *; }
-keep class com.example.androidbackupgui.backup.RestoreProgress { *; }
-keep class com.example.androidbackupgui.backup.BackupConfig { *; }
-keep class com.example.androidbackupgui.backup.AppError { *; }
-keep class com.example.androidbackupgui.backup.AppResult { *; }


# --- RemoteTransport implementations ---
-keep class com.example.androidbackupgui.backup.SmbTransport { *; }
-keep class com.example.androidbackupgui.backup.WebdavTransport { *; }

# --- WifiManager (called from UI, kept for safety) ---
-keep class com.example.androidbackupgui.backup.WifiManager { *; }
# --- Keep data models used by kotlinx.serialization ---
## Keep all model classes that may be referenced via @Serializable
-keep class com.example.androidbackupgui.model.** { *; }

# --- Keep R classes (referenced by code) ---
-keep class com.example.androidbackupgui.R { *; }

