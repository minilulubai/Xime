package com.kingzcheung.kime.plugin.funasr

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

class ApiKeyContentProvider : ContentProvider() {
    
    companion object {
        private const val TAG = "ApiKeyProvider"
        private const val AUTHORITY = "com.kingzcheung.kime.plugin.funasr.config"
        private const val API_KEY_PATH = "api_key"
        
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$API_KEY_PATH")
        
        private const val COLUMN_API_KEY = "api_key"
    }
    
    override fun onCreate(): Boolean {
        Log.d(TAG, "ApiKeyContentProvider created")
        return true
    }
    
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        Log.d(TAG, "query called: uri=$uri")
        
        if (context == null) {
            Log.e(TAG, "Context is null")
            return null
        }
        
        val prefs = FunAsrPreferences(context!!)
        val apiKey = prefs.getApiKey()
        
        Log.d(TAG, "Returning API key: length=${apiKey.length}")
        
        val cursor = MatrixCursor(arrayOf(COLUMN_API_KEY))
        cursor.addRow(arrayOf(apiKey))
        return cursor
    }
    
    override fun getType(uri: Uri): String {
        return "vnd.android.cursor.item/$AUTHORITY.$API_KEY_PATH"
    }
    
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }
    
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }
    
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }
}