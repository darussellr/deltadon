package com.smartwake.phone.data

import android.content.Context
import com.smartwake.shared.db.SmartWakeDatabase

/** Simple service locator for the phone app. */
object PhoneServices {

    @Volatile
    private var dbInstance: SmartWakeDatabase? = null

    fun db(context: Context): SmartWakeDatabase =
        dbInstance ?: synchronized(this) {
            dbInstance ?: SmartWakeDatabase.build(context.applicationContext).also { dbInstance = it }
        }
}
