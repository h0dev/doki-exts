package org.koitharu.kotatsu.parsers.site.tachiyomi.vi.moetruyen

import java.security.MessageDigest
import java.util.Base64

internal object ImageDecryptor {

	private const val IMGX_HEADER_BYTE_0: Byte = 0x49
	private const val IMGX_HEADER_BYTE_1: Byte = 0x4D
	private const val IMGX_HEADER_BYTE_2: Byte = 0x47
	private const val IMGX_HEADER_BYTE_3: Byte = 0x58

	private const val HEADER_SIZE = 4
	private const val IV_SIZE = 16

	fun decrypt(data: ByteArray, grant: String, storageKey: String): ByteArray {
		if (data.size <= HEADER_SIZE + IV_SIZE ||
			data[0] != IMGX_HEADER_BYTE_0 ||
			data[1] != IMGX_HEADER_BYTE_1 ||
			data[2] != IMGX_HEADER_BYTE_2 ||
			data[3] != IMGX_HEADER_BYTE_3
		) {
			return data
		}

		val fileHeader = data.sliceArray(0 until HEADER_SIZE)
		val iv = data.sliceArray(HEADER_SIZE until HEADER_SIZE + IV_SIZE)
		val payload = data.sliceArray(HEADER_SIZE + IV_SIZE until data.size)

		val key = deriveKey(grant, storageKey)

		val decrypted = ByteArray(payload.size)
		for (i in payload.indices) {
			decrypted[i] = (payload[i].toInt() xor key[i % key.size].toInt()).toByte()
		}

		return fileHeader + iv + decrypted
	}

	private fun deriveKey(grant: String, storageKey: String): ByteArray {
		val combined = "$grant:$storageKey"
		val md = MessageDigest.getInstance("SHA-256")
		return md.digest(combined.toByteArray(Charsets.UTF_8))
	}
}
