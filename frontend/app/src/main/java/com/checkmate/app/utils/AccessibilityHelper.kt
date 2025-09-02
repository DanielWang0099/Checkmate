package com.checkmate.app.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import com.checkmate.app.data.AppSourceInfo
import timber.log.Timber

/**
 * Helper class for accessibility service operations.
 */
object AccessibilityHelper {

    /**
     * Get app information for a given package name
     */
    fun getAppInfo(context: Context, packageName: String?): AppSourceInfo? {
        if (packageName.isNullOrBlank()) return null
        
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(appInfo).toString()
            
            AppSourceInfo(
                packageName = packageName,
                readableName = appName,
                category = getCategoryName(appInfo.category),
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
            
        } catch (e: Exception) {
            Timber.w(e, "Failed to get app info for package: $packageName")
            AppSourceInfo(
                packageName = packageName,
                readableName = packageName,
                category = null,
                isSystemApp = false
            )
        }
    }
    
    private fun getCategoryName(category: Int): String? {
        return when (category) {
            ApplicationInfo.CATEGORY_GAME -> "GAME"
            ApplicationInfo.CATEGORY_AUDIO -> "AUDIO"
            ApplicationInfo.CATEGORY_VIDEO -> "VIDEO"
            ApplicationInfo.CATEGORY_IMAGE -> "IMAGE"
            ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL"
            ApplicationInfo.CATEGORY_NEWS -> "NEWS_AND_MAGAZINES"
            ApplicationInfo.CATEGORY_MAPS -> "MAPS"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRODUCTIVITY"
            else -> null
        }
    }

    /**
     * Extract text content from accessibility tree
     */
    fun extractTextFromNode(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        
        val textContent = mutableListOf<String>()
        
        try {
            // Add current node's text if available
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { text ->
                textContent.add(text)
            }
            
            // Recursively extract from child nodes
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    textContent.addAll(extractTextFromNode(child))
                    child.recycle()
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error extracting text from accessibility node")
        }
        
        return textContent
    }

    /**
     * Find nodes with clickable content
     */
    fun findClickableNodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        
        val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
        
        try {
            if (node.isClickable && !node.text.isNullOrBlank()) {
                clickableNodes.add(node)
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    clickableNodes.addAll(findClickableNodes(child))
                    // Don't recycle here as we're returning references
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error finding clickable nodes")
        }
        
        return clickableNodes
    }
}
