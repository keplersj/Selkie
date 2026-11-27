package io.github.keplersj.selkie.platform

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether the car asks apps to stay out of the driver's way, from Android Automotive's UX restrictions.
 * Selkie declares itself distraction optimized so that it can show its own "hidden while you drive" screen;
 * that makes this watcher the thing that hides everything else, so every doubt counts as driving.
 */
object CarDriving {
    fun isCar(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    /**
     * Calls [onChange] with true while driving and false while parked, starting with true until the car's
     * service answers. Only call it where [isCar] is true: the android.car library exists only in cars.
     */
    fun watch(context: Context, onChange: (Boolean) -> Unit): AutoCloseable {
        onChange(true)
        var manager: CarUxRestrictionsManager? = null
        val car = runCatching {
            Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (!ready) { onChange(true); return@createCar }
                val m = runCatching { c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager }.getOrNull()
                manager = m
                if (m == null) { onChange(true); return@createCar }
                onChange(runCatching { m.currentCarUxRestrictions.isRequiresDistractionOptimization }.getOrDefault(true))
                runCatching { m.registerListener { r -> onChange(r.isRequiresDistractionOptimization) } }
                    .onFailure { onChange(true) }
            }
        }.getOrNull()
        return AutoCloseable {
            runCatching { manager?.unregisterListener() }
            runCatching { car?.disconnect() }
        }
    }
}
