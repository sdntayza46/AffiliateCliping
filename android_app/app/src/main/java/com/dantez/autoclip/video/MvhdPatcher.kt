package com.dantez.autoclip.video

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Port murni dari d:\Download\buat_mvhd_max.py ke Kotlin.
 * Memodifikasi atom MP4 agar durasi pada atom `mvhd` bernilai maksimum (0xFFFFFFFFFFFFFFFF),
 * menaikkan versi mvhd dari versi 0 ke versi 1 (64-bit), menyesuaikan ukuran atom `moov`,
 * dan mem-patch seluruh chunk offset (stco / co64) yang bergeser.
 */
object MvhdPatcher {

    private const val TAG = "MvhdPatcher"
    private const val MAX_DURATION = -1L // 0xFFFFFFFFFFFFFFFF in signed 64-bit

    private val CONTAINERS = setOf(
        "moov", "trak", "mdia", "minf", "stbl",
        "edts", "dinf", "mvex", "moof", "traf",
        "udta", "meta", "ilst"
    )

    data class AtomInfo(
        val type: String,
        val pos: Long,
        val size: Long,
        val headerSize: Int
    )

    private fun readUint32(data: ByteArray, offset: Int): Long {
        val b0 = data[offset].toLong() and 0xFF
        val b1 = data[offset + 1].toLong() and 0xFF
        val b2 = data[offset + 2].toLong() and 0xFF
        val b3 = data[offset + 3].toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun writeUint32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readUint64(data: ByteArray, offset: Int): Long {
        val buf = ByteBuffer.wrap(data, offset, 8).order(ByteOrder.BIG_ENDIAN)
        return buf.long
    }

    private fun writeUint64(data: ByteArray, offset: Int, value: Long) {
        val buf = ByteBuffer.wrap(data, offset, 8).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(value)
    }

    private fun getAtoms(data: ByteArray, start: Int, end: Int): List<AtomInfo> {
        val list = mutableListOf<AtomInfo>()
        var pos = start
        while (pos + 8 <= end) {
            var size = readUint32(data, pos)
            val typ = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8

            if (size == 1L) {
                if (pos + 16 > end) {
                    throw IllegalArgumentException("Atom extended tidak lengkap: $typ di offset $pos")
                }
                size = readUint64(data, pos + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }

            if (size < header || pos + size > end) {
                throw IllegalArgumentException("Struktur atom tidak valid: $typ di offset $pos, size: $size")
            }

            list.add(AtomInfo(typ, pos.toLong(), size, header))
            pos += size.toInt()
        }
        return list
    }

    private fun findTopAtom(data: ByteArray, wanted: String): AtomInfo {
        for (atom in getAtoms(data, 0, data.size)) {
            if (atom.type == wanted) return atom
        }
        throw IllegalArgumentException("Atom '$wanted' tidak ditemukan")
    }

    data class MvhdLocation(
        val moovPos: Int,
        val moovSize: Int,
        val mvhdPos: Int,
        val mvhdSize: Int,
        val headerSize: Int
    )

    private fun findMvhd(data: ByteArray): MvhdLocation {
        val moov = findTopAtom(data, "moov")
        val moovPos = moov.pos.toInt()
        val moovSize = moov.size.toInt()
        val moovHeader = moov.headerSize

        val children = getAtoms(data, moovPos + moovHeader, moovPos + moovSize)
        for (atom in children) {
            if (atom.type == "mvhd") {
                return MvhdLocation(
                    moovPos = moovPos,
                    moovSize = moovSize,
                    mvhdPos = atom.pos.toInt(),
                    mvhdSize = atom.size.toInt(),
                    headerSize = atom.headerSize
                )
            }
        }
        throw IllegalArgumentException("Atom moov/mvhd tidak ditemukan")
    }

    private fun patchChunkOffsets(
        data: ByteArray,
        start: Int,
        end: Int,
        insertionPos: Int,
        delta: Int
    ) {
        val atoms = getAtoms(data, start, end)
        for (atom in atoms) {
            val pos = atom.pos.toInt()
            val header = atom.headerSize
            val size = atom.size.toInt()
            val payload = pos + header

            when (atom.type) {
                "stco" -> {
                    if (payload + 8 > pos + size) throw IllegalArgumentException("Atom stco tidak valid")
                    val count = readUint32(data, payload + 4).toInt()
                    val entry = payload + 8
                    if (entry + count * 4 > pos + size) throw IllegalArgumentException("Tabel stco terpotong")

                    for (i in 0 until count) {
                        val offpos = entry + i * 4
                        val oldOff = readUint32(data, offpos)
                        if (oldOff >= insertionPos) {
                            val newOff = oldOff + delta
                            if (newOff > 0xFFFFFFFFL) {
                                throw IllegalStateException("Offset stco overflow; perlu konversi ke co64")
                            }
                            writeUint32(data, offpos, newOff)
                        }
                    }
                }
                "co64" -> {
                    if (payload + 8 > pos + size) throw IllegalArgumentException("Atom co64 tidak valid")
                    val count = readUint32(data, payload + 4).toInt()
                    val entry = payload + 8
                    if (entry + count * 8 > pos + size) throw IllegalArgumentException("Tabel co64 terpotong")

                    for (i in 0 until count) {
                        val offpos = entry + i * 8
                        val oldOff = readUint64(data, offpos)
                        if (oldOff >= insertionPos.toLong()) {
                            val newOff = oldOff + delta
                            writeUint64(data, offpos, newOff)
                        }
                    }
                }
                in CONTAINERS -> {
                    var childStart = payload
                    if (atom.type == "meta") {
                        childStart += 4 // meta adalah FullBox: 4 byte version + flags
                    }
                    if (childStart <= pos + size) {
                        patchChunkOffsets(data, childStart, pos + size, insertionPos, delta)
                    }
                }
            }
        }
    }

    private fun upgradeMvhdV0(
        data: ByteArray,
        loc: MvhdLocation
    ): ByteArray {
        val payload = loc.mvhdPos + loc.headerSize
        val version = data[payload].toInt() and 0xFF
        if (version != 0) {
            throw IllegalArgumentException("Bukan mvhd versi 0 (versi: $version)")
        }

        val versionFlags = data.copyOfRange(payload, payload + 4)
        val creation = readUint32(data, payload + 4)
        val modification = readUint32(data, payload + 8)
        val timescale = readUint32(data, payload + 12)
        // duration v0 berada di payload + 16 .. payload + 19, kita ganti dengan MAX_DURATION
        val tail = data.copyOfRange(payload + 20, loc.mvhdPos + loc.mvhdSize)

        // Susun body mvhd versi 1
        // body = 1 byte (0x01) + 3 byte flags + 8 byte creation + 8 byte modification + 4 byte timescale + 8 byte MAX_DURATION + tail
        val bodyBuf = ByteBuffer.allocate(1 + 3 + 8 + 8 + 4 + 8 + tail.size).order(ByteOrder.BIG_ENDIAN)
        bodyBuf.put(1.toByte()) // Version 1
        bodyBuf.put(versionFlags, 1, 3)
        bodyBuf.putLong(creation)
        bodyBuf.putLong(modification)
        bodyBuf.putInt(timescale.toInt())
        bodyBuf.putLong(MAX_DURATION)
        bodyBuf.put(tail)
        val body = bodyBuf.array()

        // Atom baru: 4 byte size + 4 byte 'mvhd' + body
        val newAtomSize = body.size + 8
        val newAtomBuf = ByteBuffer.allocate(newAtomSize).order(ByteOrder.BIG_ENDIAN)
        newAtomBuf.putInt(newAtomSize)
        newAtomBuf.put("mvhd".toByteArray(Charsets.ISO_8859_1))
        newAtomBuf.put(body)
        val newAtom = newAtomBuf.array()

        val delta = newAtom.size - loc.mvhdSize
        val insertionPos = loc.mvhdPos + loc.mvhdSize

        // Sisipkan atom baru ke data
        val newData = ByteArray(data.size + delta)
        System.arraycopy(data, 0, newData, 0, loc.mvhdPos)
        System.arraycopy(newAtom, 0, newData, loc.mvhdPos, newAtom.size)
        System.arraycopy(data, insertionPos, newData, loc.mvhdPos + newAtom.size, data.size - insertionPos)

        // Perbarui ukuran moov
        val newMoovSize = loc.moovSize.toLong() + delta
        if (newMoovSize > 0xFFFFFFFFL) {
            throw IllegalStateException("Ukuran moov melebihi batas 32-bit")
        }
        writeUint32(newData, loc.moovPos, newMoovSize)

        // Patch chunk offsets
        patchChunkOffsets(
            newData,
            loc.moovPos + 8,
            (loc.moovPos + newMoovSize).toInt(),
            insertionPos,
            delta
        )

        return newData
    }

    /**
     * Memproses file input MP4 dan menghasilkan file output MP4 yang telah di-patch mvhd max.
     */
    fun process(srcFile: File, dstFile: File) {
        if (!srcFile.exists()) {
            throw IllegalArgumentException("File sumber tidak ditemukan: ${srcFile.absolutePath}")
        }
        if (srcFile.absolutePath == dstFile.absolutePath) {
            throw IllegalArgumentException("File tujuan tidak boleh sama dengan sumber")
        }

        var data = srcFile.readBytes()
        val loc = findMvhd(data)
        val payload = loc.mvhdPos + loc.headerSize
        val version = data[payload].toInt() and 0xFF

        data = if (version == 0) {
            upgradeMvhdV0(data, loc)
        } else if (version == 1) {
            writeUint64(data, payload + 24, MAX_DURATION)
            data
        } else {
            throw IllegalArgumentException("Versi mvhd tidak dikenal: $version")
        }

        if (dstFile.exists()) {
            dstFile.delete()
        }
        dstFile.writeBytes(data)

        verify(dstFile)
        Log.i(TAG, "Mvhd max patch berhasil: ${dstFile.name} (${dstFile.length()} bytes)")
    }

    fun verify(file: File) {
        val data = file.readBytes()
        val loc = findMvhd(data)
        val payload = loc.mvhdPos + loc.headerSize
        val version = data[payload].toInt() and 0xFF

        if (version != 1) {
            throw IllegalStateException("Verifikasi gagal: mvhd versi $version, bukan 1")
        }

        val timescale = readUint32(data, payload + 20)
        val duration = readUint64(data, payload + 24)

        if (duration != MAX_DURATION) {
            throw IllegalStateException("Durasi tidak cocok dengan MAX_DURATION: $duration")
        }
        Log.d(TAG, "Verifikasi Sukses -> Version: $version, Timescale: $timescale, Duration: 0x${java.lang.Long.toHexString(duration).uppercase()}")
    }
}
