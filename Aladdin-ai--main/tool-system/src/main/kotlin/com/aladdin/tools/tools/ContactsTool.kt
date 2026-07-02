package com.aladdin.tools.tools
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 9 fix item 9.10 — ContactsTool
 *
 * Provides contact lookup, search, and display name resolution.
 * Uses ContentResolver to query Android Contacts provider.
 * Requires READ_CONTACTS permission.
 */
@Singleton
class ContactsTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool() {

    companion object {
        private const val TAG = "ContactsTool"
    }

    override val name: String = "contacts"
    override val description: String = "Look up, search, and manage contacts from the phone's address book"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        return try {
            when (val action = params["action"] as? String ?: "search") {
                "search"  -> searchContacts(params["query"] as? String ?: "")
                "lookup"  -> lookupContact(params["name"] as? String ?: "")
                "phone"   -> getPhoneNumber(params["name"] as? String ?: "")
                "email"   -> getEmail(params["name"] as? String ?: "")
                "list"    -> listContacts(params["limit"] as? Int ?: 10)
                else      -> ToolResult.Error("Unknown contacts action: $action")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CONTACTS permission denied: ${e.message}")
            ToolResult.Error("Contacts permission not granted. Please allow contacts access.")
        } catch (e: Exception) {
            Log.e(TAG, "ContactsTool error: ${e.message}", e)
            ToolResult.Error("Contacts lookup failed: ${e.message}")
        }
    }

    private fun searchContacts(query: String): ToolResult {
        if (query.isBlank()) return ToolResult.Error("Search query is required")

        val resolver: ContentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        val results = JSONArray()
        resolver.query(uri, projection, selection, selectionArgs, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("name", cursor.getString(0) ?: "")
                    obj.put("phone", cursor.getString(1) ?: "")
                    obj.put("type", phoneTypeLabel(cursor.getInt(2)))
                    results.put(obj)
                }
            }

        Log.d(TAG, "Contact search '$query': ${results.length()} results")
        return if (results.length() > 0) {
            ToolResult.Success(results.toString())
        } else {
            ToolResult.Success("No contacts found matching '$query'")
        }
    }

    private fun lookupContact(name: String): ToolResult = searchContacts(name)

    private fun getPhoneNumber(name: String): ToolResult {
        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        resolver.query(uri, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(0)
                    val phone = cursor.getString(1)
                    return ToolResult.Success("$displayName: $phone")
                }
            }

        return ToolResult.Success("No phone number found for '$name'")
    }

    private fun getEmail(name: String): ToolResult {
        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Email.ADDRESS
        )
        val selection = "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        resolver.query(uri, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(0)
                    val email = cursor.getString(1)
                    return ToolResult.Success("$displayName: $email")
                }
            }

        return ToolResult.Success("No email found for '$name'")
    }

    private fun listContacts(limit: Int): ToolResult {
        val resolver = context.contentResolver
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Contacts.DISPLAY_NAME
        )

        val results = JSONArray()
        resolver.query(uri, projection, null, null, "${ContactsContract.Contacts.DISPLAY_NAME} ASC")
            ?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) {
                        results.put(name)
                        count++
                    }
                }
            }

        return ToolResult.Success("Contacts (first $limit): $results")
    }

    private fun phoneTypeLabel(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
        else -> "Other"
    }
}
