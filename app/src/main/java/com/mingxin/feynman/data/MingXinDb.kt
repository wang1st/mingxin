package com.mingxin.feynman.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        QuestionEntity::class,
        SessionEntity::class,
        InsightEntity::class,
        ReviewLogEntity::class,
        MetaEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class MingXinDb : RoomDatabase() {
    abstract fun questionDao(): QuestionDao
    abstract fun sessionDao(): SessionDao
    abstract fun insightDao(): InsightDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun metaDao(): MetaDao

    companion object {
        @Volatile
        private var instance: MingXinDb? = null

        fun get(context: Context): MingXinDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MingXinDb::class.java,
                "mingxin.db"
            ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
        }
    }
}
