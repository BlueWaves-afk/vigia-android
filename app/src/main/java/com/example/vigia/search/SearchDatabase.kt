package com.example.vigia.search

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// UPDATE: Added HazardEntity, bumped version to 2, disabled exportSchema warning
@Database(entities = [PlaceEntity::class, HazardEntity::class], version = 2, exportSchema = false)
abstract class SearchDatabase : RoomDatabase() {

    abstract fun placeDao(): PlaceDao
    abstract fun hazardDao(): HazardDao // UPDATE: Added Hazard DAO

    companion object {
        @Volatile private var INSTANCE: SearchDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): SearchDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SearchDatabase::class.java,
                    "vigia_places.db"
                )
                    // UPDATE: Wipes the old database to prevent crashes when upgrading version 1 -> 2
                    .fallbackToDestructiveMigration()
                    .addCallback(SearchDatabaseCallback(scope))
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    // Callback remains the same: Pre-loads critical safety places
    private class SearchDatabaseCallback(private val scope: CoroutineScope) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                scope.launch(Dispatchers.IO) {
                    val dao = database.placeDao()
                    val safeSpots = listOf(
                        PlaceEntity(name = "City General Hospital", address = "123 Main St", lat = 47.642, lon = -122.137, type = "hospital", isCritical = true),
                        PlaceEntity(name = "Central Police Station", address = "456 Safety Blvd", lat = 47.645, lon = -122.140, type = "police", isCritical = true),
                        PlaceEntity(name = "Emergency Shelter A", address = "789 Relief Rd", lat = 47.648, lon = -122.142, type = "shelter", isCritical = true)
                    )
                    dao.insertAll(safeSpots)
                }
            }
        }
    }
}