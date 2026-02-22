package cn.idiots.autoclick.util

import cn.idiots.autoclick.data.ClickRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import android.util.Log

object GkdSubscriptionManager {

    /**
     * Fetches a GKD subscription from a URL and parses it into a list of ClickRule.
     */
    suspend fun fetchAndParseUrl(url: String): Result<List<ClickRule>> {
        return withContext(Dispatchers.IO) {
            try {
                // Fetch content
                val content = URL(url).readText(Charsets.UTF_8)
                // Parse
                val rules = GkdParser.parse(content, subUrl = url)
                if (rules.isEmpty()) {
                    Result.failure(Exception("No valid rules found in the subscription."))
                } else {
                    Result.success(rules)
                }
            } catch (e: Exception) {
                Log.e("GkdSubManager", "Failed to fetch or parse URL: $url", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Parses raw JSON or JSON5 string content (e.g. from local file).
     */
    suspend fun parseContent(content: String): Result<List<ClickRule>> {
        return withContext(Dispatchers.Default) {
            try {
                val rules = GkdParser.parse(content, subUrl = null)
                if (rules.isEmpty()) {
                    Result.failure(Exception("No valid rules found in the content."))
                } else {
                    Result.success(rules)
                }
            } catch (e: Exception) {
                Log.e("GkdSubManager", "Failed to parse content", e)
                Result.failure(e)
            }
        }
    }
}
