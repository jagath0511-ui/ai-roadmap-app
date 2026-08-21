package com.jai.agent

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract

object ContactResolver {

    /**
     * Resolves a contact name or raw digits into a dialable phone number.
     */
    fun resolvePhoneNumber(context: Context, query: String): String? {
        val cleanQuery = query.trim()
        
        // If it's already a numeric phone number
        if (cleanQuery.replace("+", "").all { it.isDigit() } && cleanQuery.length >= 3) {
            return cleanQuery
        }

        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$cleanQuery%")

            val cursor: Cursor? = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                null
            )

            var matchedNumber: String? = null
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIndex >= 0) {
                        matchedNumber = it.getString(numberIndex).replace(" ", "").replace("-", "")
                    }
                }
            }
            matchedNumber
        } catch (e: Exception) {
            FailureLogger.log(context, "ContactResolver", "Query failed: ${e.localizedMessage}")
            null
        }
    }
}
