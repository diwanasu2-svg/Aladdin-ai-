# Keep plugin API classes so they are accessible from plugin APKs via DexClassLoader
-keep public class com.aladdin.plugin.api.** { *; }
-keep public interface com.aladdin.plugin.api.** { *; }
-keep public enum com.aladdin.plugin.api.** { *; }
-keep public class com.aladdin.plugin.model.** { *; }
