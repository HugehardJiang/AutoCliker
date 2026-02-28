package cn.idiots.autoclick.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ClickRule::class, ClickLog::class], version = 10, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun clickRuleDao(): ClickRuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autoclick_database"
                )
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10) // Simplified migration list for clarity
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE click_rules ADD COLUMN selector TEXT")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE click_rules ADD COLUMN activityIds TEXT")
                database.execSQL("ALTER TABLE click_rules ADD COLUMN groupName TEXT")
                database.execSQL("ALTER TABLE click_rules ADD COLUMN ruleDescription TEXT")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS click_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ruleId INTEGER NOT NULL,
                        packageName TEXT NOT NULL,
                        appName TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE click_rules ADD COLUMN isSubscription INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE click_rules ADD COLUMN subscriptionUrl TEXT")
                database.execSQL("ALTER TABLE click_rules ADD COLUMN subscriptionName TEXT")
            }
        }

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE click_rules ADD COLUMN excludeCondition TEXT")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Try to add columns, ignore if already exists (safe for dev)
                try { database.execSQL("ALTER TABLE click_rules ADD COLUMN ruleKey INTEGER") } catch(e: Exception) {}
                try { database.execSQL("ALTER TABLE click_rules ADD COLUMN preKeys TEXT") } catch(e: Exception) {}
                try { database.execSQL("ALTER TABLE click_rules ADD COLUMN groupKey INTEGER") } catch(e: Exception) {}
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Recovery migration
                try { database.execSQL("ALTER TABLE click_rules ADD COLUMN ruleKey INTEGER") } catch(e: Exception) {}
                try { database.execSQL("ALTER TABLE click_rules ADD COLUMN groupKey INTEGER") } catch(e: Exception) {}
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Recovery migration
                try { database.execSQL("ALTER TABLE click_rules ADD COLUMN ruleKey INTEGER") } catch(e: Exception) {}
                try { database.execSQL("ALTER TABLE click_rules ADD COLUMN groupKey INTEGER") } catch(e: Exception) {}
            }
        }

        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // The "Nuclear" fix: SQLite doesn't support DROP COLUMN 'key' easily in old versions.
                // We recreate the table to match the Entity EXACTLY.
                
                // 1. Create temporary table
                database.execSQL("""
                    CREATE TABLE click_rules_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        appName TEXT NOT NULL,
                        targetText TEXT,
                        targetViewId TEXT,
                        boundsInScreen TEXT,
                        selector TEXT,
                        activityIds TEXT,
                        groupName TEXT,
                        ruleDescription TEXT,
                        isEnabled INTEGER NOT NULL,
                        isSubscription INTEGER NOT NULL,
                        subscriptionUrl TEXT,
                        subscriptionName TEXT,
                        excludeCondition TEXT,
                        ruleKey INTEGER,
                        preKeys TEXT,
                        groupKey INTEGER
                    )
                """.trimIndent())

                // 2. Copy data (mapping ruleKey/groupKey from possible old 'key' or existing columns)
                database.execSQL("""
                    INSERT INTO click_rules_new (
                        id, packageName, appName, targetText, targetViewId, boundsInScreen, 
                        selector, activityIds, groupName, ruleDescription, isEnabled, 
                        isSubscription, subscriptionUrl, subscriptionName, excludeCondition, 
                        ruleKey, preKeys, groupKey
                    )
                    SELECT 
                        id, packageName, appName, targetText, targetViewId, boundsInScreen, 
                        selector, activityIds, groupName, ruleDescription, isEnabled, 
                        isSubscription, subscriptionUrl, subscriptionName, excludeCondition, 
                        ruleKey, preKeys, groupKey
                    FROM click_rules
                """.trimIndent())

                // 3. Drop old and rename
                database.execSQL("DROP TABLE click_rules")
                database.execSQL("ALTER TABLE click_rules_new RENAME TO click_rules")
            }
        }
    }
}
