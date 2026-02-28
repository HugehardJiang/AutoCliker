package cn.idiots.autoclick.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "click_rules")
data class ClickRule(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val packageName: String,
    val appName: String,
    val targetText: String? = null,
    val targetViewId: String? = null,
    val boundsInScreen: String? = null, // Stored as "left,top,right,bottom"
    val selector: String? = null,
    val activityIds: String? = null, // Comma separated list of activities
    val groupName: String? = null,
    val ruleDescription: String? = null,
    val isEnabled: Boolean = true,
    
    // Phase 3: Subscription Support
    val isSubscription: Boolean = false,
    val subscriptionUrl: String? = null,
    val subscriptionName: String? = null,
    
    // Phase 12: Exclusion Condition Support
    val excludeCondition: String? = null,

    // Phase 20: Multi-stage Click Support
    val ruleKey: Int? = null,
    val preKeys: String? = null, // Comma separated list of rule keys
    val groupKey: Int? = null
)
