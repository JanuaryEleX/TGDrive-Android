package com.yg.tgdrive.download

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking

/**
 * 以 FaststartRemuxer.Plan 呈现的虚拟文件数据源。
 *
 * 虚拟布局： [prefix] [moov'] [mdat 顺移]
 * - [0, prefixLen)：原文件同偏移
 * - [prefixLen, prefixLen+moovLen)：内存里的已重写 moov
 * - 之后：原文件偏移 = 虚拟偏移 - moovLen
 */
class TdVirtualDataSource(
    private val mgr: StreamingDownloadManager,
    private val plan: FaststartRemuxer.Plan,
) : BaseDataSource(true) {

    companion object {
        private const val PREHEAT = 512L * 1024
    }

    private var uri: Uri? = null
    private var position = 0L
    private var endPosition = 0L
    private var opened = false

    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null

    override fun getUri(): Uri? = uri

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        position = dataSpec.position
        endPosition = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.position + dataSpec.length
        } else {
            plan.virtualSize
        }
        if (endPosition > plan.virtualSize) endPosition = plan.virtualSize
        transferStarted(dataSpec)
        opened = true
        return endPosition - position
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw IOException("read before open")
        if (length == 0) return 0
        if (position >= endPosition) return C.RESULT_END_OF_INPUT

        var bufOff = offset
        var remaining = minOf(length.toLong(), endPosition - position).toInt()
        var total = 0

        while (remaining > 0) {
            if (position >= plan.moovVirtualStart && position < plan.moovVirtualEnd) {
                // moov 区：内存直出
                val moovOff = (position - plan.moovVirtualStart).toInt()
                val n = minOf(remaining, (plan.moovLen - moovOff).toInt())
                System.arraycopy(plan.moov, moovOff, buffer, bufOff, n)
                position += n
                bufOff += n
                remaining -= n
                total += n
                continue
            }
            val real = plan.toReal(position)
            val limitReal = if (position < plan.prefixLen) plan.prefixLen else plan.size
            val chunkLen = minOf(remaining.toLong(), limitReal - real).coerceAtLeast(1L)
            val ref = ensure(real, maxOf(chunkLen, PREHEAT))
            val handle = handleFor(ref)
            handle.seek(real - ref.globalOffset)
            val n = handle.read(buffer, bufOff, chunkLen.toInt())
            if (n <= 0) {
                if (total == 0) throw IOException("虚拟读取 EOF file=${plan.fileId} vpos=$position real=$real")
                break
            }
            position += n
            bufOff += n
            remaining -= n
            total += n
        }
        if (total > 0) bytesTransferred(total)
        return if (total == 0) C.RESULT_END_OF_INPUT else total
    }

    @Throws(IOException::class)
    override fun close() {
        opened = false
        runCatching { raf?.close() }
        raf = null
        rafPath = null
        uri = null
        transferEnded()
    }

    private fun ensure(real: Long, len: Long): PartialRef = try {
        runBlocking { mgr.ensureRange(plan.fileId, real, len, tag = "stream") }
    } catch (ie: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("读取被取消 file=${plan.fileId}", ie)
    }

    private fun handleFor(ref: PartialRef): RandomAccessFile {
        val existing = raf
        if (existing != null && rafPath == ref.partialPath) return existing
        runCatching { existing?.close() }
        val f = RandomAccessFile(ref.partialPath, "r")
        raf = f
        rafPath = ref.partialPath
        return f
    }
}

class TdVirtualDataSourceFactory(
    private val mgr: StreamingDownloadManager,
    private val plan: FaststartRemuxer.Plan,
) : DataSource.Factory {
    override fun createDataSource(): TdVirtualDataSource = TdVirtualDataSource(mgr, plan)
}
