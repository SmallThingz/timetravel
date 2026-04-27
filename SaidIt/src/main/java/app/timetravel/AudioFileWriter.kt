package app.timetravel

import java.io.Closeable

internal interface AudioFileWriter : Closeable {
    val totalSampleBytesWritten: Int
    val target: RecordingOutputTarget

    fun write(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    )
}
