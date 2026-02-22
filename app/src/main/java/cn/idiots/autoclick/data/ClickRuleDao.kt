package cn.idiots.autoclick.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClickRuleDao {
    @Query("SELECT * FROM click_rules")
    fun getAllRules(): Flow<List<ClickRule>>

    @Query("SELECT * FROM click_rules WHERE (packageName = :packageName OR packageName = '*') AND isEnabled = 1")
    suspend fun getActiveRulesForPackage(packageName: String): List<ClickRule>

    @Insert
    suspend fun insertRule(rule: ClickRule)

    @Update
    suspend fun updateRule(rule: ClickRule)

    @Delete
    suspend fun deleteRule(rule: ClickRule)

    @Insert
    suspend fun insertRules(rules: List<ClickRule>)

    @Query("DELETE FROM click_rules WHERE subscriptionName = :subscriptionName AND isSubscription = 1")
    suspend fun deleteSubscription(subscriptionName: String)

    // Click Logs
    @Insert
    suspend fun insertClickLog(log: ClickLog)

    @Query("SELECT * FROM click_logs ORDER BY timestamp DESC LIMIT 50")
    fun getRecentLogs(): Flow<List<ClickLog>>

    @Query("SELECT COUNT(*) FROM click_logs")
    fun getTotalClickCount(): Flow<Long>
}
