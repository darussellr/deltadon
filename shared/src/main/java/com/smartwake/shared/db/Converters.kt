package com.smartwake.shared.db

import androidx.room.TypeConverter
import java.nio.ByteBuffer

class Converters {

    @TypeConverter
    fun floatArrayToBlob(value: FloatArray?): ByteArray? = value?.let {
        val buffer = ByteBuffer.allocate(it.size * Float.SIZE_BYTES)
        for (f in it) buffer.putFloat(f)
        buffer.array()
    }

    @TypeConverter
    fun blobToFloatArray(blob: ByteArray?): FloatArray? = blob?.let {
        val buffer = ByteBuffer.wrap(it)
        FloatArray(it.size / Float.SIZE_BYTES) { buffer.float }
    }
}
