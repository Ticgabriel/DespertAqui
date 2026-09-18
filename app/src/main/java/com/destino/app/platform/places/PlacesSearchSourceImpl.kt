package com.destino.app.platform.places

import android.content.Context
import com.destino.app.BuildConfig
import com.destino.app.core.model.Coordinates
import com.destino.app.core.model.PlaceDetails
import com.destino.app.core.model.PlaceSuggestion
import com.destino.app.core.model.PlacesSearchSource
import com.destino.app.core.model.PlacesResult
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class PlacesSearchSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val access: PlacesAccessStore
) : PlacesSearchSource {

    private val sessionLock = Any()
    private var sessionToken: AutocompleteSessionToken? = null

    private fun currentSessionToken(): AutocompleteSessionToken = synchronized(sessionLock) {
        sessionToken ?: AutocompleteSessionToken.newInstance().also { sessionToken = it }
    }

    override fun abandonSession() {
        synchronized(sessionLock) { sessionToken = null }
    }

    private val requestMutex = Mutex()
    private var activeKey = ""
    private var placesClient: PlacesClient? = null

    private suspend fun <T> request(block: suspend () -> PlacesResult<T>): PlacesResult<T> = requestMutex.withLock {
        try {
            val personal = withContext(Dispatchers.IO) { access.personalKey() }
            val key = personal.ifBlank { BuildConfig.MAPS_API_KEY }
            if (key.isBlank()) {
                access.openSettings()
                return@withLock PlacesResult.Failure("Cadastre sua chave em Ajustes → Chave de busca.")
            }
            if (activeKey != key || placesClient == null) {
                abandonSession()
                Places.deinitialize()
                if (personal.isNotBlank()) Places.initializeWithNewPlacesApiEnabled(context, key)
                else Places.initialize(context, key)
                placesClient = Places.createClient(context)
                activeKey = key
            }
            if (personal.isBlank() && !withContext(Dispatchers.IO) { access.reserve() }) {
                access.showLimit()
                return@withLock PlacesResult.Failure("Você atingiu 100 chamadas neste mês. Cadastre sua chave ou aguarde o próximo mês.")
            }
            // Wait for dispatched SDK work before changing credentials for another request.
            withContext(NonCancellable) {
                val result = block()
                if (personal.isBlank() && withContext(Dispatchers.IO) { access.usage() } >= MonthlyQuota.LIMIT) {
                    access.showLimit()
                }
                result
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PlacesResult.Failure("Não foi possível preparar a busca. Confira sua chave em Ajustes → Chave de busca.")
        }
    }

    override suspend fun searchSuggestions(query: String): PlacesResult<List<PlaceSuggestion>> {
        if (query.isBlank()) return PlacesResult.Success(emptyList())
        return request { search(query) }
    }

    private suspend fun search(query: String): PlacesResult<List<PlaceSuggestion>> {
        val client = placesClient
            ?: return PlacesResult.Failure("Busca de endereços indisponível. Verifique a configuração do Google Maps.")

        return suspendCancellableCoroutine { continuation ->
            val token = currentSessionToken()
            val request = FindAutocompletePredictionsRequest.builder()
                .setSessionToken(token)
                .setQuery(query)
                .build()

            client.findAutocompletePredictions(request)
                .addOnSuccessListener { response ->
                    val suggestions = response.autocompletePredictions.map { prediction ->
                        PlaceSuggestion(
                            placeId = prediction.placeId,
                            primaryText = prediction.getPrimaryText(null).toString(),
                            secondaryText = prediction.getSecondaryText(null).toString()
                        )
                    }
                    if (continuation.isActive) {
                        continuation.resume(PlacesResult.Success(suggestions))
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) {
                        continuation.resume(
                            PlacesResult.Failure("Não foi possível buscar. Confira a conexão, a chave, as APIs e as cotas em Ajustes → Chave de busca.")
                        )
                    }
                }
        }
    }

    override suspend fun fetchPlaceDetails(placeId: String): PlacesResult<PlaceDetails> {
        if (placeId.isBlank()) return PlacesResult.Failure("Endereço inválido.")
        return request { details(placeId) }
    }

    private suspend fun details(placeId: String): PlacesResult<PlaceDetails> {
        val client = placesClient
            ?: return PlacesResult.Failure("Busca de endereços indisponível. Verifique a configuração do Google Maps.")
        val token = currentSessionToken()

        return suspendCancellableCoroutine { continuation ->
            val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG)
            val request = FetchPlaceRequest.builder(placeId, placeFields)
                .setSessionToken(token)
                .build()

            client.fetchPlace(request)
                .addOnSuccessListener { response ->
                    val place = response.place
                    val latLng = place.latLng
                    if (latLng != null) {
                        if (continuation.isActive) continuation.resume(
                            PlacesResult.Success(PlaceDetails(
                                name = place.name ?: "Destino Selecionado",
                                coordinates = Coordinates(latLng.latitude, latLng.longitude)
                            ))
                        )
                    } else {
                        if (continuation.isActive) continuation.resume(
                            PlacesResult.Failure("O endereço selecionado não possui coordenadas.")
                        )
                    }
                    abandonSession()
                }
                .addOnFailureListener { error ->
                    abandonSession()
                    if (continuation.isActive) continuation.resume(
                        PlacesResult.Failure("Não foi possível abrir o endereço. Confira a conexão e sua chave em Ajustes → Chave de busca.")
                    )
                }
        }
    }
}
