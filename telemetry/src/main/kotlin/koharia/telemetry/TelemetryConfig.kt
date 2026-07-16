package koharia.telemetry

import android.content.Context

object TelemetryConfig {
    fun init(context: Context) = TelemetryImplementation.init(context)

    fun setAnalyticsEnabled(enabled: Boolean) = TelemetryImplementation.setAnalyticsEnabled(enabled)

    fun setCrashlyticsEnabled(enabled: Boolean) = TelemetryImplementation.setCrashlyticsEnabled(enabled)
}
