-keep class com.assavendo.machinevirtuel.engine.NativeEngine { *; }
-keep class com.assavendo.machinevirtuel.vm.** { *; }

# Ed25519 provider is selected by the JCA provider name at runtime.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
