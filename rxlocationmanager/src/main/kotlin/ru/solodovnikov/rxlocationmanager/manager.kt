package ru.solodovnikov.rxlocationmanager

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import rx.Emitter
import rx.Observable
import rx.Scheduler
import rx.Single
import rx.android.schedulers.AndroidSchedulers
import rx.functions.Action1
import java.util.*
import java.util.concurrent.TimeoutException

/**
 * Implementation of [BaseRxLocationManager] based on RxJava1
 */
class RxLocationManager internal constructor(context: Context,
                                             private val scheduler: Scheduler) : BaseRxLocationManager<Single<Location>, Single<Location>>(context) {
    constructor(context: Context) : this(context, AndroidSchedulers.mainThread())

    /**
     * @return Result [Single] will emit null if there is no location by this [provider].
     * Or it will be emit [ElderLocationException] if [howOldCanBe] not null and location is too old.
     */
    override fun baseGetLastLocation(provider: String, howOldCanBe: LocationTime?): Single<Location> =
            Single.fromCallable { locationManager.getLastKnownLocation(provider) }
                    .compose {
                        if (howOldCanBe != null) {
                            it.doOnSuccess {
                                if (it != null && !it.isNotOld(howOldCanBe)) {
                                    throw ElderLocationException(it)
                                }
                            }
                        } else {
                            it
                        }
                    }.compose { applySchedulers(it) }

    /**
     * @return Result [Single] can throw [ProviderDisabledException] or [TimeoutException] if [timeOut] not null
     */
    override fun baseRequestLocation(provider: String, timeOut: LocationTime?): Single<Location> =
            Observable.create(RxLocationListener(locationManager, provider), Emitter.BackpressureMode.NONE)
                    .toSingle()
                    .compose { if (timeOut != null) it.timeout(timeOut.time, timeOut.timeUnit) else it }
                    .compose { applySchedulers(it) }

    private fun applySchedulers(s: Single<Location>) = s.subscribeOn(scheduler)

    private class RxLocationListener(val locationManager: LocationManager, val provider: String) : Action1<Emitter<Location>> {

        override fun call(emitter: Emitter<Location>) {
            if (locationManager.isProviderEnabled(provider)) {
                val locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location?) {
                        with(emitter) {
                            onNext(location)
                            onCompleted()
                        }
                    }

                    override fun onProviderDisabled(p: String?) {
                        if (provider == p) {
                            emitter.onError(ProviderDisabledException(provider))
                        }
                    }

                    override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) {}

                    override fun onProviderEnabled(p0: String?) {}
                }

                locationManager.requestSingleUpdate(provider, locationListener, null)

                emitter.setCancellation { locationManager.removeUpdates(locationListener) }

            } else {
                emitter.onError(ProviderDisabledException(provider))
            }
        }
    }
}

/**
 * Implementation of [BaseLocationRequestBuilder] based on rxJava1
 */
class LocationRequestBuilder internal constructor(rxLocationManager: RxLocationManager) : BaseLocationRequestBuilder<Single<Location>, Single<Location>, Single.Transformer<Location, Location>, LocationRequestBuilder>(rxLocationManager) {
    constructor(context: Context) : this(RxLocationManager(context))

    private var resultObservable = Observable.empty<Location>()

    override fun baseAddRequestLocation(provider: String,
                                        timeOut: LocationTime?,
                                        transformer: Single.Transformer<Location, Location>?): LocationRequestBuilder =
            addRequestLocation(provider, timeOut, false, transformer)


    override fun baseAddLastLocation(provider: String,
                                     howOldCanBe: LocationTime?,
                                     transformer: Single.Transformer<Location, Location>?) =
            addLastLocation(provider, howOldCanBe, false, transformer)

    /**
     * Construct final observable.
     *
     * @return It will emit [defaultLocation] if final observable is empty.
     */
    override fun create(): Single<Location> =
            resultObservable.firstOrDefault(defaultLocation)
                    .toSingle()

    /**
     * Try to get current location by specific [provider].
     * It will ignore any library exceptions (e.g [ProviderDisabledException]).
     * But will fall if any other exception will occur. This can be changed via [transformer].
     *
     * @param provider    provider name
     * @param timeOut     request timeout
     * @param isNullValid if true, then this request can emit null value
     * @param transformer extra transformer
     *
     * @return same builder
     * @see baseAddRequestLocation
     */
    @JvmOverloads
    fun addRequestLocation(provider: String,
                           timeOut: LocationTime? = null,
                           isNullValid: Boolean = false,
                           transformer: Single.Transformer<Location, Location>? = null): LocationRequestBuilder =
            rxLocationManager.requestLocation(provider, timeOut)
                    .compose { if (transformer != null) it.compose(transformer) else it }
                    .toObservable()
                    .onErrorResumeNext {
                        when (it) {
                            is TimeoutException, is ProviderDisabledException, is NoSuchElementException -> Observable.empty<Location>()
                            else -> Observable.error<Location>(it)
                        }
                    }
                    .flatMap { if (it == null && !isNullValid) Observable.empty() else Observable.just(it) }
                    .let {
                        resultObservable = resultObservable.concatWith(it)
                        this
                    }

    /**
     * Get last location from specific [provider].
     * It will ignore any library exceptions (e.g [ElderLocationException]).
     * But will fall if any other exception will occur. This can be changed via [transformer].
     *
     * @param provider    provider name
     * @param howOldCanBe optional. How old a location can be
     * @param isNullValid if true, then this request can emit null value
     * @param transformer optional extra transformer
     *
     * @return same builder
     * @see baseAddLastLocation
     */
    @JvmOverloads
    fun addLastLocation(provider: String,
                        howOldCanBe: LocationTime? = null,
                        isNullValid: Boolean = false,
                        transformer: Single.Transformer<Location, Location>? = null): LocationRequestBuilder =
            rxLocationManager.getLastLocation(provider, howOldCanBe)
                    .compose { if (transformer != null) it.compose(transformer) else it }
                    .toObservable()
                    .onErrorResumeNext {
                        when (it) {
                            is ElderLocationException, is NoSuchElementException -> Observable.empty<Location>()
                            else -> Observable.error<Location>(it)
                        }
                    }
                    .flatMap { if (it == null && !isNullValid) Observable.empty() else Observable.just(it) }
                    .let {
                        resultObservable = resultObservable.concatWith(it)
                        this
                    }
}

/**
 * Use it to ignore any described error type.
 *
 * @param errorsToIgnore if null or empty, then ignore all errors, otherwise just described types.
 */
open class IgnoreErrorTransformer @JvmOverloads constructor(private val errorsToIgnore: List<Class<out Throwable>>? = null) : Single.Transformer<Location, Location> {
    override fun call(upstream: Single<Location>): Single<Location> {
        return upstream.onErrorResumeNext { t: Throwable ->
            if (errorsToIgnore == null || errorsToIgnore.isEmpty()) {
                Observable.empty<Location>().toSingle()
            } else {
                if (errorsToIgnore.contains(t.javaClass)) {
                    Observable.empty<Location>().toSingle()
                } else {
                    Single.error(t)
                }
            }
        }
    }
}
