package app.timetravel

import java.io.Closeable
import java.io.File

internal interface AudioFileWriter : Closeable {
    val totalSampleBytesWritten: Int
    val file: File

    fun write(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    )
}
