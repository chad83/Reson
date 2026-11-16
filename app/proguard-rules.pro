# SMBJ uses NTLM on Android. Kerberos is available only on the JVM.
-dontwarn org.ietf.jgss.**

# SMBJ's event bus does not use expression-language filters.
-dontwarn javax.el.**
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class com.hierynomus.** {
    @net.engio.mbassy.listener.Handler <methods>;
}

# The SRVSVC client references this desktop JVM exception for malformed RPC replies.
-dontwarn java.rmi.UnmarshalException
