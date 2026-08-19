package koharia.telemetry

import android.content.Context

@Suppress("UNUSED_PARAMETER")
internal object TelemetryImplementation {
    fun init(context: Context) = Unit

    fun setAnalyticsEnabled(enabled: Boolean) = Unit

    fun setCrashlyticsEnabled(enabled: Boolean) = Unit

    fun recordException(exception: Throwable, message: String?) = Unit
}
