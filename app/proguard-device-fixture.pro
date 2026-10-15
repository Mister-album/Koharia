# AndroidJUnitRunner calls these methods across the target/test APK boundary.
-keep,allowoptimization class androidx.tracing.Trace {
    public static *;
}
