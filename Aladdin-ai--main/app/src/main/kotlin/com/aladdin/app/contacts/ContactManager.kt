package com.aladdin.app.contacts

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ContactManager — Item 66: CRUD contacts, search, sync, permission handling.
 */
@Singleton
class ContactManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object { private const val TAG = "ContactManager" }

    data class Contact(
        val id: Long = 0, val name: String, val phone: String = "",
        val email: String = "", val photoUri: String = ""
    )

    fun hasPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun getAllContacts(): List<Contact> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val contacts = mutableListOf<Contact>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    contacts.add(Contact(
                        id    = cursor.getLong(0),
                        name  = cursor.getString(1) ?: "",
                        phone = cursor.getString(2) ?: ""
                    ))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Failed to read contacts: ${e.message}") }
        contacts
    }

    suspend fun searchContacts(query: String): List<Contact> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )?.use { cursor ->
                val results = mutableListOf<Contact>()
                while (cursor.moveToNext()) {
                    results.add(Contact(cursor.getLong(0), cursor.getString(1) ?: "", cursor.getString(2) ?: ""))
                }
                results
            } ?: emptyList()
        } catch (e: Exception) { Log.e(TAG, "Search error: ${e.message}"); emptyList() }
    }

    suspend fun addContact(name: String, phone: String, email: String = ""): Boolean = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext false
        try {
            val ops = ArrayList<android.content.ContentProviderOperation>()
            val rawIdx = ops.size
            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build())
            if (phone.isNotBlank()) ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone).build())
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.i(TAG, "Contact added: $name")
            true
        } catch (e: Exception) { Log.e(TAG, "Add contact error: ${e.message}"); false }
    }

    suspend fun deleteContact(contactId: Long): Boolean = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext false
        try {
            val uri = android.net.Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString())
            context.contentResolver.delete(uri, null, null)
            Log.i(TAG, "Contact deleted: $contactId"); true
        } catch (e: Exception) { Log.e(TAG, "Delete error: ${e.message}"); false }
    }
}
