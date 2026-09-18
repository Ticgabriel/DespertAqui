package com.destino.app.data.local

import androidx.room.withTransaction
import com.destino.app.core.model.Destination
import com.destino.app.core.model.Favorite
import com.destino.app.data.local.entities.FavoriteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface FavoriteRepository {
    fun observeAll(): Flow<List<Favorite>>
    fun search(query: String): Flow<List<Favorite>>
    suspend fun getById(id: String): Favorite?
    suspend fun saveFavorite(favorite: Favorite, destination: Destination? = null)
    suspend fun deleteFavorite(id: String)
    suspend fun reorderFavorites(orderedIds: List<String>)
}

@Singleton
class FavoriteRepositoryImpl @Inject constructor(
    private val database: DestinoDatabase
) : FavoriteRepository {

    private val favoriteDao = database.favoriteDao()
    private val destinationDao = database.destinationDao()

    override fun observeAll(): Flow<List<Favorite>> {
        return favoriteDao.observeAll().map { entities ->
            entities.map { entity ->
                val dest = destinationDao.getById(entity.destinationId)?.toDomain()
                entity.toDomain(dest)
            }
        }
    }

    override fun search(query: String): Flow<List<Favorite>> {
        return favoriteDao.search(query).map { entities ->
            entities.map { entity ->
                val dest = destinationDao.getById(entity.destinationId)?.toDomain()
                entity.toDomain(dest)
            }
        }
    }

    override suspend fun getById(id: String): Favorite? {
        val entity = favoriteDao.getById(id) ?: return null
        val dest = destinationDao.getById(entity.destinationId)?.toDomain()
        return entity.toDomain(dest)
    }

    override suspend fun saveFavorite(favorite: Favorite, destination: Destination?) {
        database.withTransaction {
            if (destination != null) {
                destinationDao.insert(destination.toEntity())
            }
            favoriteDao.insert(favorite.toEntity())
        }
    }

    override suspend fun deleteFavorite(id: String) {
        favoriteDao.deleteById(id)
    }

    override suspend fun reorderFavorites(orderedIds: List<String>) {
        database.withTransaction {
            orderedIds.forEachIndexed { index, id ->
                favoriteDao.getById(id)?.let { entity ->
                    favoriteDao.update(entity.copy(sortOrder = index))
                }
            }
        }
    }
}
