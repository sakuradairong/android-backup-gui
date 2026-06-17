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
-keep class com.example.androidbackupgui.backup.restic.RemoteTransport { *; }

# --- Data classes (serialization) ---
-keep class com.example.androidbackupgui.backup.restic.ResticWrapper$ResticProgress { *; }
-keep class com.example.androidbackupgui.backup.restic.ResticWrapper$BackupSummary { *; }
-keep class com.example.androidbackupgui.backup.restic.ResticWrapper$ResticSnapshot { *; }
-keep class com.example.androidbackupgui.backup.RestoreOperation$RestoreProgress { *; }
-keep class com.example.androidbackupgui.backup.BackupConfig { *; }
-keep class com.example.androidbackupgui.backup.core.AppError { *; }
-keep class com.example.androidbackupgui.backup.core.AppResult { *; }

# --- RemoteTransport implementations ---
-keep class com.example.androidbackupgui.backup.restic.SmbTransport { *; }
-keep class com.example.androidbackupgui.backup.restic.WebdavTransport { *; }

# --- WifiManager (called from UI, kept for safety) ---
-keep class com.example.androidbackupgui.backup.WifiManager { *; }

# --- Keep data models used by kotlinx.serialization ---
-keep class com.example.androidbackupgui.model.** { *; }

# --- Keep R classes (referenced by code) ---
-keep class com.example.androidbackupgui.R { *; }

# --- jcifs-ng (SMB) — keep class/member names for reflection ---
-keep class jcifs.util.Crypto { *; }
-keep class jcifs.smb.NtlmUtil { *; }
-keep class jcifs.ntlmssp.Type3Message { *; }
-keep class jcifs.ntlmssp.NtlmContext { *; }
