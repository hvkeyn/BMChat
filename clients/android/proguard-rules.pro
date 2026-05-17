# native methods
-keep class com.b44t.messenger.** { * ; }

# Keep metadata needed by the JSON parser
-keep class chat.delta.rpc.** { * ; }
-keepattributes *Annotation*,EnclosingMethod,Signature
-keepnames class com.fasterxml.jackson.** { *; }

# bug with video recoder
-keep class com.coremedia.iso.** { *; }

# unused SealedData constructor needed by JsonUtils
-keep class org.thoughtcrime.securesms.crypto.KeyStoreHelper* { *; }

-dontwarn com.google.firebase.analytics.connector.AnalyticsConnector

# Keep WebRTC classes
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-keepattributes InnerClasses

# 2.49.39: keep LocalBroadcastManager + our UpdateDownloadService
# state fields. R8 was stripping the static volatiles
# (PROGRESS/STATE/READY_APK_PATH/...) used by the in-app banner,
# causing the conversation list to NPE on resume.
-keep class androidx.localbroadcastmanager.** { *; }
-keep class org.thoughtcrime.securesms.update.UpdateDownloadService {
    public static *;
}
-keep class org.thoughtcrime.securesms.update.UpdateDownloadService$* {
    *;
}
