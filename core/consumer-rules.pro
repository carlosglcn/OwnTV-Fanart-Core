# Keep rules that :core imposes on anything consuming it. Empty for now: the rules that protect
# core's code live in each app's proguard-rules.pro. The
# file is declared by consumerProguardFiles so that moving a rule here later is a one-line change
# rather than a build-config change.

# glcn: smbj (NAS shares). Its optional logging/EL bits are absent on Android; the security provider
# classes are reached directly (BCSecurityProvider), the message factories by reflection-free code.
-dontwarn javax.el.**
-dontwarn org.slf4j.impl.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn com.sun.security.auth.module.**
-keep class com.hierynomus.security.bc.** { *; }
