/*
 * Copyright (C) 2026 mGBA Android contributors.
 * This program is free software under the GNU General Public License v3.
 */
package org.mgba_emu.mgba.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import org.mgba_emu.mgba.core.Core
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Five independently persisted emulator snapshots per ROM. These are not .sav
 * cartridge save files. Filenames are hashes of the stable game document URI,
 * so a user-controlled ROM filename cannot escape the save directory.
 */
class SaveStateStore(context: Context, romUri: Uri) {
    companion object {
        const val SLOT_COUNT = 5
        private const val MAX_STATE_BYTES = 32 * 1024 * 1024
        private const val TAG = "SaveStateStore"

        /** Convert little-endian RGBA texels used by GLES into Android ARGB. */
        fun rgbaToArgb(pixel: Int): Int =
            (pixel and 0x0000FF00) or
                ((pixel and 0x000000FF) shl 16) or
                ((pixel ushr 16) and 0x000000FF) or
                (0xFF000000.toInt())
    }

    data class Slot(val index: Int, val occupied: Boolean, val modifiedAt: Long, val thumbnail: File?)

    private val directory: File

    init {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(romUri.toString().toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        directory = File(File(root, "savestates"), hash)
    }

    private fun checkSlot(slot: Int) { require(slot in 1..SLOT_COUNT) }
    private fun stateFile(slot: Int): File {
        checkSlot(slot)
        return File(directory, "slot$slot.state")
    }
    private fun imageFile(slot: Int): File {
        checkSlot(slot)
        return File(directory, "slot$slot.png")
    }
    private fun exists(file: File): Boolean = file.exists() || File(file.path + ".bak").exists()

    fun list(): List<Slot> = (1..SLOT_COUNT).map { index ->
        val state = stateFile(index)
        val image = imageFile(index)
        Slot(index, exists(state), state.lastModified(),
            image.takeIf { exists(it) })
    }

    /** Read the save atomically, including recovery of interrupted prior writes. */
    fun load(slot: Int): ByteArray? {
        val file = stateFile(slot)
        if (!exists(file)) return null
        return try {
            val data = AtomicFile(file).readFully()
            if (data.isEmpty() || data.size > MAX_STATE_BYTES) null else data
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read slot $slot", e)
            null
        }
    }

    fun save(slot: Int, state: ByteArray, thumbnail: ByteArray?): Boolean {
        if (state.isEmpty() || state.size > MAX_STATE_BYTES) return false
        if (!directory.exists() && !directory.mkdirs()) return false
        if (!writeAtomic(stateFile(slot), state)) return false

        // State is already durable. Thumbnail failure must never corrupt it.
        if (thumbnail != null && thumbnail.isNotEmpty() && !writeAtomic(imageFile(slot), thumbnail)) {
            Log.w(TAG, "Slot $slot saved without a new thumbnail")
        }
        return true
    }

    private fun writeAtomic(file: File, bytes: ByteArray): Boolean {
        val atomic = AtomicFile(file)
        var output: java.io.FileOutputStream? = null
        return try {
            output = atomic.startWrite()
            output.write(bytes)
            atomic.finishWrite(output)
            true
        } catch (e: Exception) {
            if (output != null) atomic.failWrite(output)
            Log.e(TAG, "Atomic snapshot write failed", e)
            false
        }
    }

    fun thumbnail(slot: Int): Bitmap? {
        val file = imageFile(slot)
        return if (exists(file)) try {
            // Images are capped at native GBA/GB dimensions at capture.
            BitmapFactory.decodeFile(file.path)
        } catch (_: Exception) {
            null
        } else null
    }

    fun delete(slot: Int): Boolean {
        val file = stateFile(slot)
        if (!exists(file)) return false
        AtomicFile(file).delete()
        AtomicFile(imageFile(slot)).delete()
        return !exists(file)
    }

    /**
     * Screenshot comes from the emulated framebuffer, before shaders or touch
     * overlays. Caller should run off the UI thread after state serialization.
     */
    fun captureThumbnail(): ByteArray? {
        val w = Core.width
        val h = Core.height
        if (w !in 1..1024 || h !in 1..1024) return null
        val rgba = Core.getVideoBuffer()
        if (rgba.size < w * h) return null
        val argb = IntArray(w * h) { rgbaToArgb(rgba[it]) }
        val image = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { out ->
                if (image.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray()
                else null
            }
        } finally {
            image.recycle()
        }
    }
}
