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
class AccessibilityHelper private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: AccessibilityHelper? = null
        
        fun getInstance(context: Context): AccessibilityHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AccessibilityHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Get the root accessibility node from the accessibility service
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return try {
            com.checkmate.app.services.CheckmateAccessibilityService.instance?.rootInActiveWindow
        } catch (e: Exception) {
            Timber.e(e, "Error getting root accessibility node")
            null
        }
    }

    /**
     * Get current app information
     */
    fun getCurrentApp(): AppSourceInfo? {
        return try {
            val rootNode = getRootNode()
            val packageName = rootNode?.packageName?.toString()
            getAppInfo(context, packageName)
        } catch (e: Exception) {
            Timber.e(e, "Error getting current app")
            null
        }
    }

    /**
     * Get app information for specific package name
     */
    fun getCurrentApp(context: Context, packageName: String?): AppSourceInfo? {
        return getAppInfo(context, packageName)
    }

    private var rootNodeProvider: (() -> AccessibilityNodeInfo?)? = null
    private var currentAppProvider: (() -> AppSourceInfo?)? = null

    /**
     * Set the root node provider (typically called from accessibility service)
     */
    fun setRootNodeProvider(provider: () -> AccessibilityNodeInfo?) {
        rootNodeProvider = provider
    }

    /**
     * Set the current app provider (typically called from accessibility service)
     */
    fun setCurrentAppProvider(provider: () -> AppSourceInfo?) {
        currentAppProvider = provider
    }

    /**
     * Get the current root accessibility node
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootNodeProvider?.invoke()
    }

    /**
     * Get current app information
     */
    fun getCurrentApp(): AppSourceInfo? {
        return currentAppProvider?.invoke()
    }

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

    /**
     * Check if a node represents important content
     */
    fun isImportantContent(node: AccessibilityNodeInfo): Boolean {
        return try {
            val text = node.text?.toString() ?: ""
            val contentDescription = node.contentDescription?.toString() ?: ""
            val className = node.className?.toString() ?: ""
            
            when {
                // Has meaningful text content
                text.length > 10 -> true
                
                // Is a heading
                node.isHeading -> true
                
                // Is clickable with description
                node.isClickable && contentDescription.isNotBlank() -> true
                
                // Is an important UI element
                className.contains("Button") && (text.isNotBlank() || contentDescription.isNotBlank()) -> true
                className.contains("TextView") && text.length > 5 -> true
                className.contains("EditText") -> true
                
                else -> false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking if content is important")
            false
        }
    }
}
