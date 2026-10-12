# Keep Compose runtime
-keep class androidx.compose.** { *; }

# The local DSD FFmpeg artifact uses JNI class/method lookup (including
# FfmpegAudioDecoder.growOutputBuffer) and reflective capability diagnostics.
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.** { *; }

# Jaudiotagger constructs ID3 frame bodies by class name and reflective constructors.
# WAV metadata fallback can contain these ID3 frames too.
-keep class org.jaudiotagger.tag.id3.framebody.** { *; }

# SMBJ's MBassador event bus discovers handlers and their annotations at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep @interface net.engio.mbassy.listener.** { *; }
-keepclassmembers class com.hierynomus.** {
    @net.engio.mbassy.listener.Handler <methods>;
}

# Optional desktop-only paths: SMBJ skips SPNEGO on Android (uses NTLM), and
# its event handlers do not use MBassador's expression-language conditions.
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
