/*
 * Copyright (c) 2024 Fraunhofer-Gesellschaft zur Förderung der angewandten Forschung e.V.
 *
 * This work is licensed under the Fraunhofer License (on the basis of the MIT license)
 * that can be found in the LICENSE file.
 */
package de.fraunhofer.isst.trend.watermarker.watermarks

import de.fraunhofer.isst.trend.watermarker.helper.CRC32
import de.fraunhofer.isst.trend.watermarker.helper.Compression
import de.fraunhofer.isst.trend.watermarker.helper.fromBytesLittleEndian
import de.fraunhofer.isst.trend.watermarker.helper.toBytesLittleEndian
import de.fraunhofer.isst.trend.watermarker.helper.toHexString
import de.fraunhofer.isst.trend.watermarker.returnTypes.Event
import de.fraunhofer.isst.trend.watermarker.returnTypes.Result
import de.fraunhofer.isst.trend.watermarker.returnTypes.Status
import de.fraunhofer.isst.trend.watermarker.watermarks.Trendmark.FailedTrendmarkExtractionsWarning
import de.fraunhofer.isst.trend.watermarker.watermarks.Trendmark.IncompleteTagError
import de.fraunhofer.isst.trend.watermarker.watermarks.Trendmark.InvalidTagError
import de.fraunhofer.isst.trend.watermarker.watermarks.TrendmarkInterface.Companion.TAG_SIZE
import org.kotlincrypto.hash.sha3.SHA3_256
import kotlin.collections.ArrayList
import kotlin.js.JsExport

sealed interface TrendmarkInterface {
    companion object {
        /** Number of bytes used as tag representing the type of the watermark */
        const val TAG_SIZE: Int = 1
    }

    /** Constant function that returns the tag used to encode this Trendmark class */
    fun getTag(): UByte

    /** Constant function that returns the name of the specific Trendmark */
    fun getSource(): String

    /** Extracts the encoded tag from the bytes of the Trendmark */
    fun extractTag(): UByte {
        check(TAG_SIZE == 1)
        val content = getRawContent()
        require(content.size >= TAG_SIZE)
        return content.first().toUByte()
    }

    /** Returns the decoded information stored in the Trendmark */
    fun getContent(): Result<List<Byte>>

    /** Returns the raw bytes of the watermark */
    fun getRawContent(): List<Byte>

    /** Updates the raw bytes of the watermark */
    fun setRawContent(content: List<Byte>)

    /** Checks if the bytes represent a valid Trendmark of the given class */
    fun validate(): Status {
        val content = getRawContent()
        if (content.size < TAG_SIZE) {
            return IncompleteTagError.into()
        }
        val status = Status.success()
        val extractedTag = extractTag()
        val expectedTag = getTag()

        if (extractedTag != expectedTag) {
            status.addEvent(InvalidTagError(getSource(), expectedTag, extractedTag))
        }

        if (this is Trendmark.Sized) {
            status.appendStatus(validateSize())
        }
        if (this is Trendmark.Checksum) {
            status.appendStatus(validateChecksum())
        }
        if (this is Trendmark.Hash) {
            status.appendStatus(validateHash())
        }
        if (this is Trendmark.Compressed) {
            status.appendStatus(validateCompression())
        }

        return status
    }
}

/**
 * Trendmark defines a list of Watermarks with specific format. The format is encoded in the first
 * byte of the watermark, which allows to parse unknown types of Trendmarks. The implemented
 * variants of Trendmark allow to encode addition information like the size or a hash into the
 * watermark. For detailed information about the different formats see
 * [Trendmark.md](https://github.com/FraunhoferISST/TREND/blob/main/docs/Trendmark.md)
 *
 * The constructor expects bytes that represent the given type.
 * To create a new watermark with arbitrary content the companion function `new` of that type must
 * be used.
 *
 * @param content: expects bytes that represent a Trendmark.
 */
@JsExport
sealed class Trendmark(
    content: List<Byte>,
) : Watermark(content), TrendmarkInterface {
    companion object {
        const val SOURCE = "Trendmark"

        /**
         * Parses [input] as Trendmark.
         *
         * Returns an error if:
         *  - The first byte is not a valid Trendmark tag
         *  - The `validate()` function of the created Trendmark returns an error.
         */
        fun parse(input: List<Byte>): Result<Trendmark> {
            if (input.size < TAG_SIZE) {
                return NotEnoughDataError(SOURCE, TAG_SIZE).into<_>()
            }

            val watermark =
                when (val tag = extractTag(input)) {
                    RawWatermark.TYPE_TAG -> RawWatermark(input)
                    SizedWatermark.TYPE_TAG -> SizedWatermark(input)
                    CRC32Watermark.TYPE_TAG -> CRC32Watermark(input)
                    SizedCRC32Watermark.TYPE_TAG -> SizedCRC32Watermark(input)
                    SHA3256Watermark.TYPE_TAG -> SHA3256Watermark(input)
                    SizedSHA3256Watermark.TYPE_TAG -> SizedSHA3256Watermark(input)
                    CompressedRawWatermark.TYPE_TAG -> CompressedRawWatermark(input)
                    CompressedSizedWatermark.TYPE_TAG -> CompressedSizedWatermark(input)
                    CompressedCRC32Watermark.TYPE_TAG -> CompressedCRC32Watermark(input)
                    CompressedSizedCRC32Watermark.TYPE_TAG -> CompressedSizedCRC32Watermark(input)
                    CompressedSHA3256Watermark.TYPE_TAG -> CompressedSHA3256Watermark(input)
                    CompressedSizedSHA3256Watermark.TYPE_TAG ->
                        CompressedSizedSHA3256Watermark(input)
                    else -> return UnknownTagError(tag).into<_>()
                }
            val status = watermark.validate()
            return status.into(watermark)
        }

        /**
         * Parses [watermark] as Trendmark.
         *
         * Returns an error if:
         *  - The first byte is not a valid Trendmark tag
         *  - The `validate()` function of the created Trendmark returns an error.
         */
        fun fromWatermark(watermark: Watermark): Result<Trendmark> =
            parse(watermark.watermarkContent)

        private fun extractTag(content: List<Byte>): UByte {
            check(TAG_SIZE == 1)
            require(content.isNotEmpty()) { "Cannot extract tag from empty watermark." }
            return content[0].toUByte()
        }

        @OptIn(ExperimentalStdlibApi::class)
        internal val hexFormat =
            HexFormat {
                number {
                    prefix = "0x"
                }
            }
    }

    /** Returns the tag byte from the watermark content */
    override fun extractTag(): UByte = Companion.extractTag(watermarkContent)

    /** Returns the all bytes of the watermark */
    override fun getRawContent(): List<Byte> = watermarkContent

    /** Sets all bytes of the watermark to [content] */
    override fun setRawContent(content: List<Byte>) {
        watermarkContent = content
    }

    /** Checks if [this] and [other] are the same Trendmark instance and have the same content */
    override fun equals(other: Any?): Boolean {
        val equalClass =
            when (this) {
                is RawWatermark -> other is RawWatermark
                is SizedWatermark -> other is SizedWatermark
                is CRC32Watermark -> other is CRC32Watermark
                is SizedCRC32Watermark -> other is SizedCRC32Watermark
                is SHA3256Watermark -> other is SHA3256Watermark
                is SizedSHA3256Watermark -> other is SizedSHA3256Watermark
                is CompressedRawWatermark -> other is CompressedRawWatermark
                is CompressedSizedWatermark -> other is CompressedSizedWatermark
                is CompressedCRC32Watermark -> other is CompressedCRC32Watermark
                is CompressedSizedCRC32Watermark -> other is CompressedSizedCRC32Watermark
                is CompressedSHA3256Watermark -> other is CompressedSHA3256Watermark
                is CompressedSizedSHA3256Watermark -> other is CompressedSizedSHA3256Watermark
            }
        if (!equalClass) return false

        return super.equals(other)
    }

    /** Represents the Trendmark in a human-readable form */
    override fun toString(): String {
        return "${getSource()}(${super.toString()})"
    }

    sealed interface Sized : TrendmarkInterface {
        /**
         * Returns the range of bytes (inclusive) within the watermark bytes that represent the
         * size.
         *
         * Example:
         * If the size is stored in the first four bytes after the tag byte of the watermark, this
         * function returns `1..4`.
         */
        fun getSizeRange(): IntRange

        /**
         * Extracts the size bytes from the watermark content and parses them as UInt.
         *
         * Returns an error if the watermark content does not contain enough bytes (according to
         * the `getSizeRange()` function).
         */
        fun extractSize(): Result<UInt> {
            val content = getRawContent()
            val sizeRange = getSizeRange()
            if (content.size <= sizeRange.last) {
                return NotEnoughDataError(getSource(), sizeRange.last + 1).into<_>()
            }

            return Result.success(
                UInt.fromBytesLittleEndian(content.subList(sizeRange.first, sizeRange.last + 1)),
            )
        }

        /**
         * Validates that the size of the watermark content and the encoded size in the watermark
         * content match.
         *
         * Returns a warning if the size does not match.
         * Returns an error if the watermark content does not contain enough bytes (according to
         * the `getSizeRange()` function).
         */
        fun validateSize(): Status {
            val extractedSize =
                with(extractSize()) {
                    if (!isSuccess) return status
                    value!!
                }.toInt()
            val actualSize = getRawContent().size

            return if (extractedSize != actualSize) {
                MismatchedSizeWarning(getSource(), extractedSize, actualSize).into()
            } else {
                Status.success()
            }
        }
    }

    object ChecksumConstants {
        const val CHECKSUM_PLACEHOLDER: Byte = 0
    }

    sealed interface Checksum : TrendmarkInterface {
        /**
         * Returns the range of bytes (inclusive) within the watermark bytes that represent the
         * checksum.
         *
         * Example:
         * If the checksum is stored in the first four bytes after the tag byte of the watermark,
         * this function returns `1..4`.
         */
        fun getChecksumRange(): IntRange

        /**
         * Extracts the checksum bytes from the watermark content and parses them as UInt.
         *
         * Returns an error if the watermark content does not contain enough bytes (according to
         * the `getChecksumRange()` function).
         */
        fun extractChecksum(): Result<UInt> {
            val checksumRange = getChecksumRange()
            val checksumSize = checksumRange.last - checksumRange.first + 1
            // Ensure checksum fits into an Int, otherwise impl must be changed
            check(checksumSize <= 4)

            val content = getRawContent()
            if (content.size <= checksumRange.last) {
                return NotEnoughDataError(getSource(), checksumRange.last + 1).into<_>()
            }

            val checksumBytes = content.subList(checksumRange.first, checksumRange.last + 1)
            val checksum = UInt.fromBytesLittleEndian(checksumBytes)

            return Result.success(checksum)
        }

        /**
         * Returns checksum placeholder bytes that are used as checksum during the calculation of
         * the checksum.
         */
        fun getChecksumPlaceholder(): List<Byte> {
            val checksumRange = getChecksumRange()
            val size = checksumRange.last - checksumRange.first + 1
            return List(size) { ChecksumConstants.CHECKSUM_PLACEHOLDER }
        }

        /**
         * Extracts the bytes that are used as input to calculate the checksum.
         *
         * This function must be overridden if more than the checksum itself is excluded from the
         * checksum input. E.g., when a placeholder for a hash is required.
         */
        fun getChecksumInput(): Result<List<Byte>> {
            val rawContent = getRawContent()
            val checksumRange = getChecksumRange()
            if (rawContent.size <= checksumRange.last) {
                return NotEnoughDataError(getSource(), checksumRange.last + 1).into<_>()
            }

            val checksumInput = ArrayList<Byte>(rawContent.size)
            checksumInput.addAll(rawContent.subList(0, checksumRange.first))
            checksumInput.addAll(getChecksumPlaceholder())
            checksumInput.addAll(rawContent.subList(checksumRange.last + 1, rawContent.size))

            return Result.success(checksumInput)
        }

        /**
         * Calculates the checksum of the watermark content.
         *
         * The bytes of `getChecksumPlaceholder()` replace the actual checksum in the watermark
         * content.
         */
        fun calculateChecksum(): Result<UInt>

        /**
         * Uses the `calculateChecksum()` function to update the checksum store in the watermark
         * content.
         *
         * Returns and error if `calculateChecksum()` returns an error.
         * Returns an error if the content does not contain enough bytes (according to
         * `getChecksumRange()`).
         */
        fun updateChecksum(): Status {
            val checksum =
                with(calculateChecksum()) {
                    if (!isSuccess) return status
                    value!!
                }.toBytesLittleEndian().iterator()
            val checksumRange = getChecksumRange()

            val content = getRawContent().toMutableList()
            if (content.size <= checksumRange.last) {
                return NotEnoughDataError(SOURCE, checksumRange.last + 1).into()
            }

            for (i in checksumRange) {
                content[i] = checksum.next()
            }
            check(!checksum.hasNext())
            setRawContent(content)

            return Status.success()
        }

        /**
         * Validates that the checksum of the watermark content and the encoded checksum in the
         * watermark content match.
         *
         * Returns a warning if the checksum does not match.
         * Returns an error if the watermark content does not contain enough bytes (according to
         * the `getChecksumRange()` function).
         */
        fun validateChecksum(): Status {
            val extractedChecksum =
                with(extractChecksum()) {
                    if (!isSuccess) return status
                    value!!
                }
            val calculatedChecksum =
                with(calculateChecksum()) {
                    if (!isSuccess) return status
                    value!!
                }

            return if (extractedChecksum != calculatedChecksum) {
                InvalidChecksumWarning(getSource(), extractedChecksum, calculatedChecksum).into()
            } else {
                Status.success()
            }
        }
    }

    object HashConstants {
        const val HASH_PLACEHOLDER: Byte = 0
    }

    sealed interface Hash : TrendmarkInterface {
        /**
         * Returns the range of bytes (inclusive) within the watermark bytes that represent the
         * hash.
         *
         * Example:
         * If the hash is stored in the first 32 bytes after the tag byte of the watermark, this
         * function returns `1..32`.
         */
        fun getHashRange(): IntRange

        /**
         * Extracts the hash bytes from the watermark content.
         *
         * Returns an error if the watermark content does not contain enough bytes (according to
         * the `getHashRange()` function).
         */
        fun extractHash(): Result<List<Byte>> {
            val content = getRawContent()
            val hashRange = getHashRange()

            if (content.size <= hashRange.last) {
                return NotEnoughDataError(getSource(), hashRange.last + 1).into<_>()
            }

            return Result.success(content.subList(hashRange.first, hashRange.last + 1))
        }

        /**
         * Returns hash placeholder bytes that are used as checksum during the calculation of the
         * checksum.
         */
        fun getHashPlaceholder(): List<Byte> {
            val hashRange = getHashRange()
            val size = hashRange.last - hashRange.first + 1
            return List(size) { HashConstants.HASH_PLACEHOLDER }
        }

        /**
         * Extracts the bytes that are used as input to calculate the hash.
         *
         * This function must be overridden if more than the hash itself is excluded from the
         * hash input. E.g., when a placeholder for a checksum is required.
         */
        fun getHashInput(): Result<List<Byte>> {
            val rawContent = getRawContent()
            val hashRange = getHashRange()
            if (rawContent.size <= hashRange.last) {
                return NotEnoughDataError(getSource(), hashRange.last + 1).into<_>()
            }

            val hashInput = ArrayList<Byte>(rawContent.size)
            hashInput.addAll(rawContent.subList(0, hashRange.first))
            hashInput.addAll(getHashPlaceholder())
            hashInput.addAll(rawContent.subList(hashRange.last + 1, rawContent.size))

            return Result.success(hashInput)
        }

        /**
         * Calculates the hash of the watermark content.
         *
         * The bytes of `getHashPlaceholder()` replace the actual checksum in the watermark
         * content.
         */
        fun calculateHash(): Result<List<Byte>>

        /**
         * Uses the `calculateHash()` function to update the hash store in the watermark content.
         *
         * Returns and error if `calculateHash()` returns an error.
         * Returns an error if the content does not contain enough bytes (according to
         * `getHashRange()`).
         */
        fun updateHash(): Status {
            val hash =
                with(calculateHash()) {
                    if (!isSuccess) return status
                    value!!
                }.iterator()
            val content = getRawContent().toMutableList()
            for (i in getHashRange()) {
                content[i] = hash.next()
            }
            check(!hash.hasNext())
            setRawContent(content)

            return Status.success()
        }

        /**
         * Validates that the hash of the watermark content and the encoded hash in the watermark
         * content match.
         *
         * Returns a warning if the hash does not match.
         * Returns an error if the watermark content does not contain enough bytes (according to
         * the `getHashRange()` function).
         */
        fun validateHash(): Status {
            val extractedHash =
                with(extractHash()) {
                    if (!isSuccess) return status
                    value!!
                }
            val calculatedHash =
                with(calculateHash()) {
                    if (!isSuccess) return status
                    value!!
                }

            return if (extractedHash != calculatedHash) {
                InvalidHashWarning(getSource(), extractedHash, calculatedHash).into()
            } else {
                Status.success()
            }
        }
    }

    sealed interface Compressed : TrendmarkInterface {
        /** Returns if the decompression algorithm fails */
        fun validateCompression(): Status = getContent().status
    }

    object IncompleteTagError : Event.Error(SOURCE) {
        override fun getMessage(): String =
            "Cannot validate a watermark without a complete tag ($TAG_SIZE byte(s))."
    }

    class NotEnoughDataError(source: String, val minimumBytesRequired: Int) : Event.Error(source) {
        override fun getMessage(): String = "At least $minimumBytesRequired bytes are required."
    }

    class UnknownTagError(val tag: UByte) : Event.Error(SOURCE) {
        override fun getMessage(): String = "Unknown watermark tag: $tag."
    }

    class InvalidTagError(source: String, val expectedTag: UByte, val actualTag: UByte) :
        Event.Error(source) {
        override fun getMessage(): String = "Expected tag: $expectedTag, but was: $actualTag."
    }

    class MismatchedSizeWarning(source: String, val expectedSize: Int, val actualSize: Int) :
        Event.Warning(source) {
        override fun getMessage(): String =
            "Expected $expectedSize bytes, but extracted $actualSize bytes."
    }

    class InvalidChecksumWarning(
        source: String,
        val expectedChecksum: UInt,
        val actualChecksum: UInt,
    ) : Event.Warning(source) {
        @OptIn(ExperimentalStdlibApi::class)
        override fun getMessage(): String {
            val expectedHex = expectedChecksum.toHexString(hexFormat)
            val actualHex = actualChecksum.toHexString(hexFormat)
            return "Expected checksum: $expectedHex, but was: $actualHex."
        }
    }

    class InvalidHashWarning(
        source: String,
        val expectedHash: List<Byte>,
        val actualHash: List<Byte>,
    ) : Event.Warning(source) {
        override fun getMessage(): String {
            val expectedHex = expectedHash.toHexString()
            val actualHex = actualHash.toHexString()
            return "Expected hash: $expectedHex, but was: $actualHex."
        }
    }

    class FailedTrendmarkExtractionsWarning(source: String) : Event.Warning(source) {
        override fun getMessage(): String =
            "Could not extract and convert all watermarks to Trendmarks"
    }
}

fun Result<List<Watermark>>.toTrendmarks(source: String = "Trendmark"): Result<List<Trendmark>> {
    val (watermarks, status) =
        with(this) {
            if (value == null) {
                return status.into()
            }
            value to status
        }

    val trendmarks =
        watermarks.mapNotNull { watermark ->
            val trendmark = Trendmark.fromWatermark(watermark)
            status.appendStatus(trendmark.status)
            trendmark.value
        }

    if (status.isError && trendmarks.isNotEmpty()) {
        status.addEvent(
            FailedTrendmarkExtractionsWarning(source),
            overrideSeverity = true,
        )
    }

    return if (status.isError) {
        status.into()
    } else {
        status.into(trendmarks)
    }
}

@JsExport
class RawWatermark(content: List<Byte>) : Trendmark(content) {
    companion object {
        const val SOURCE = "Trendmark.RawWatermark"
        const val TYPE_TAG: UByte = 0u

        /** Creates a new `RawWatermark` with containing [content] */
        fun new(content: List<Byte>): RawWatermark = RawWatermark(createRaw(TYPE_TAG, content))

        /** Creates a new `RawWatermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())

        internal fun createRaw(
            tag: UByte,
            content: List<Byte>,
        ): List<Byte> = listOf(tag.toByte()) + content
    }

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getSource(): String = SOURCE

    /** Constant function that returns the name of the specific Trendmark */
    override fun getTag(): UByte = TYPE_TAG

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent() = Result.success(watermarkContent.drop(TAG_SIZE))
}

@JsExport
class SizedWatermark(content: List<Byte>) : Trendmark(content), Trendmark.Sized {
    companion object {
        const val SOURCE = "Trendmark.SizedWatermark"
        const val TYPE_TAG: UByte = 1u

        /**
         *  Number of bytes used to specify the length of the watermark. The decision to use 4 bytes
         * (i.e. 32-bit unsigned integer) was made to find a balance between maximum text size and
         * minimal additional watermark size. This decision might be reevaluated in the future.
         */
        const val SIZE_SIZE: Int = 4
        const val SIZE_START_INDEX = TAG_SIZE
        const val SIZE_END_INDEX = SIZE_START_INDEX + SIZE_SIZE - 1

        /** Creates a new `SizedWatermark` containing [content] */
        fun new(content: List<Byte>): SizedWatermark {
            return SizedWatermark(createRaw(TYPE_TAG, content))
        }

        /** Creates a new `SizedWatermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())

        internal fun createRaw(
            tag: UByte,
            content: List<Byte>,
        ): List<Byte> {
            val size = TAG_SIZE + SIZE_SIZE + content.size
            val watermark = ArrayList<Byte>(size)
            val encodedSize = size.toUInt().toBytesLittleEndian()

            watermark.add(tag.toByte())
            watermark.addAll(encodedSize)
            watermark.addAll(content)

            return watermark
        }
    }

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getSource(): String = SOURCE

    /** Constant function that returns the name of the specific Trendmark */
    override fun getTag(): UByte = TYPE_TAG

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent() = Result.success(watermarkContent.drop(TAG_SIZE + SIZE_SIZE))

    /** Returns the range of bytes (inclusive) within the watermark bytes that represent the size */
    override fun getSizeRange(): IntRange = SIZE_START_INDEX..SIZE_END_INDEX
}

@JsExport
class CRC32Watermark(content: List<Byte>) : Trendmark(content), Trendmark.Checksum {
    companion object {
        const val SOURCE = "Trendmark.CRC32Watermark"
        const val TYPE_TAG: UByte = 2u
        const val CHECKSUM_SIZE = 4
        const val CHECKSUM_START_INDEX = TAG_SIZE
        const val CHECKSUM_END_INDEX = CHECKSUM_START_INDEX + CHECKSUM_SIZE - 1

        /** Creates a new `CRC32Watermark` containing [content] */
        fun new(content: List<Byte>): CRC32Watermark {
            val watermark = CRC32Watermark(createRaw(TYPE_TAG, content))
            watermark.updateChecksum()
            return watermark
        }

        /** Creates a new `CRC32Watermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())

        internal fun createRaw(
            tag: UByte,
            content: List<Byte>,
        ): List<Byte> {
            val watermark = ArrayList<Byte>(TAG_SIZE + CHECKSUM_SIZE + content.size)

            watermark.add(tag.toByte())
            repeat(CHECKSUM_SIZE) {
                watermark.add(ChecksumConstants.CHECKSUM_PLACEHOLDER)
            }
            watermark.addAll(content)

            return watermark
        }

        /** Calculates the CRC32 checksum of [input] */
        fun calculateChecksum(input: List<Byte>): UInt = CRC32.checksum(input)
    }

    /** Constant function that returns the name of the specific Trendmark */
    override fun getSource(): String = SOURCE

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getTag(): UByte = TYPE_TAG

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent() = Result.success(watermarkContent.drop(TAG_SIZE + CHECKSUM_SIZE))

    /**
     * Returns the range of bytes (inclusive) within the watermark bytes that represent the checksum
     */
    override fun getChecksumRange(): IntRange = CHECKSUM_START_INDEX..CHECKSUM_END_INDEX

    /**
     * Calculates the CRC32 checksum of the watermark content.
     *
     * The bytes of `getChecksumPlaceholder()` replace the actual checksum in the watermark content.
     */
    override fun calculateChecksum(): Result<UInt> {
        val checksumContent =
            with(getChecksumInput()) {
                if (!isSuccess) return status.into<_>()
                value!!
            }

        return Result.success(Companion.calculateChecksum(checksumContent))
    }
}

@JsExport
class SizedCRC32Watermark(content: List<Byte>) :
    Trendmark(content), Trendmark.Sized, Trendmark.Checksum {
    companion object {
        const val SOURCE = "Trendmark.SizedCRC32Watermark"
        const val TYPE_TAG: UByte = 3u
        const val SIZE_SIZE: Int = 4
        const val SIZE_START_INDEX = TAG_SIZE
        const val SIZE_END_INDEX = SIZE_START_INDEX + SIZE_SIZE - 1
        const val CHECKSUM_SIZE = 4
        const val CHECKSUM_START_INDEX = SIZE_END_INDEX + 1
        const val CHECKSUM_END_INDEX = CHECKSUM_START_INDEX + CHECKSUM_SIZE - 1

        /** Creates a new `SizedCRC32Watermark` containing [content] */
        fun new(content: List<Byte>): SizedCRC32Watermark {
            val watermark = SizedCRC32Watermark(createRaw(TYPE_TAG, content))
            watermark.updateChecksum()
            return watermark
        }

        /** Creates a new `SizedCRC32Watermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())

        internal fun createRaw(
            tag: UByte,
            content: List<Byte>,
        ): List<Byte> {
            val size = TAG_SIZE + SIZE_SIZE + CHECKSUM_SIZE + content.size
            val encodedSize = size.toUInt().toBytesLittleEndian()
            val watermark = ArrayList<Byte>(size)

            watermark.add(tag.toByte())
            watermark.addAll(encodedSize)
            repeat(CHECKSUM_SIZE) {
                watermark.add(ChecksumConstants.CHECKSUM_PLACEHOLDER)
            }
            watermark.addAll(content)

            return watermark
        }
    }

    /** Constant function that returns the name of the specific Trendmark */
    override fun getSource(): String = SOURCE

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getTag(): UByte = TYPE_TAG

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent() =
        Result.success(watermarkContent.drop(TAG_SIZE + SIZE_SIZE + CHECKSUM_SIZE))

    override fun getSizeRange(): IntRange = SIZE_START_INDEX..SIZE_END_INDEX

    /**
     * Returns the range of bytes (inclusive) within the watermark bytes that represent the checksum
     */
    override fun getChecksumRange(): IntRange = CHECKSUM_START_INDEX..CHECKSUM_END_INDEX

    /**
     * Calculates the CRC32 checksum of the watermark content.
     *
     * The bytes of `getChecksumPlaceholder()` replace the actual checksum in the watermark content.
     */
    override fun calculateChecksum(): Result<UInt> {
        val checksumContent =
            with(getChecksumInput()) {
                if (!isSuccess) return status.into<_>()
                value!!
            }

        return Result.success(CRC32Watermark.calculateChecksum(checksumContent))
    }
}

@JsExport
class SHA3256Watermark(content: List<Byte>) : Trendmark(content), Trendmark.Hash {
    companion object {
        const val SOURCE = "Trendmark.SHA3256Watermark"
        const val TYPE_TAG: UByte = 4u
        const val HASH_SIZE = 32
        const val HASH_START_INDEX = TAG_SIZE
        const val HASH_END_INDEX = HASH_START_INDEX + HASH_SIZE - 1

        /** Creates a new `SHA3256Watermark` containing [content] */
        fun new(content: List<Byte>): SHA3256Watermark {
            val watermark = SHA3256Watermark(createRaw(TYPE_TAG, content))
            watermark.updateHash()
            return watermark
        }

        /** Creates a new `SHA3256Watermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())

        internal fun createRaw(
            tag: UByte,
            content: List<Byte>,
        ): List<Byte> {
            val size = TAG_SIZE + HASH_SIZE + content.size

            val watermark = ArrayList<Byte>(size)
            watermark.add(tag.toByte())
            repeat(HASH_SIZE) {
                watermark.add(HashConstants.HASH_PLACEHOLDER)
            }
            watermark.addAll(content)

            return watermark
        }

        /** Calculates the SHA3-256 hash of [input] */
        fun calculateHash(input: List<Byte>): List<Byte> {
            val hashAlgorithm = SHA3_256()
            hashAlgorithm.update(input.toByteArray())
            return hashAlgorithm.digest().asList()
        }
    }

    /** Constant function that returns the name of the specific Trendmark */
    override fun getSource(): String = SOURCE

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getTag(): UByte = TYPE_TAG

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent() = Result.success(watermarkContent.drop(TAG_SIZE + HASH_SIZE))

    /** Returns the range of bytes (inclusive) within the watermark bytes that represent the hash */
    override fun getHashRange(): IntRange = HASH_START_INDEX..HASH_END_INDEX

    /**
     * Calculates the SHA3-256 hash of the watermark content.
     *
     * The bytes of `getHashPlaceholder()` replace the actual checksum in the watermark
     * content.
     */
    override fun calculateHash(): Result<List<Byte>> {
        val hashInput =
            with(getHashInput()) {
                if (!isSuccess) return status.into<_>()
                value!!
            }
        return Result.success(Companion.calculateHash(hashInput))
    }
}

@JsExport
class SizedSHA3256Watermark(content: List<Byte>) :
    Trendmark(content), Trendmark.Sized, Trendmark.Hash {
    companion object {
        const val SOURCE = "Trendmark.SizedSHA3256Watermark"
        const val TYPE_TAG: UByte = 5u
        const val SIZE_SIZE = 4
        const val SIZE_START_INDEX = TAG_SIZE
        const val SIZE_END_INDEX = SIZE_START_INDEX + SIZE_SIZE - 1
        const val HASH_SIZE = 32
        const val HASH_START_INDEX = SIZE_END_INDEX + 1
        const val HASH_END_INDEX = HASH_START_INDEX + HASH_SIZE - 1

        /** Creates a new `SizedSHA3256Watermark` containing [content] */
        fun new(content: List<Byte>): SizedSHA3256Watermark {
            val watermark = SizedSHA3256Watermark(createRaw(TYPE_TAG, content))
            watermark.updateHash()
            return watermark
        }

        /** Creates a new `SizedSHA3256Watermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())

        internal fun createRaw(
            tag: UByte,
            content: List<Byte>,
        ): List<Byte> {
            val size = TAG_SIZE + SIZE_SIZE + HASH_SIZE + content.size
            val encodedSize = size.toUInt().toBytesLittleEndian()

            val watermark = ArrayList<Byte>(size)
            watermark.add(tag.toByte())
            watermark.addAll(encodedSize)
            repeat(HASH_SIZE) {
                watermark.add(HashConstants.HASH_PLACEHOLDER)
            }
            watermark.addAll(content)

            return watermark
        }
    }

    /** Constant function that returns the name of the specific Trendmark */
    override fun getSource(): String = SOURCE

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getTag(): UByte = TYPE_TAG

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent() =
        Result.success(
            watermarkContent.drop(TAG_SIZE + SIZE_SIZE + HASH_SIZE),
        )

    /** Returns the range of bytes (inclusive) within the watermark bytes that represent the size */
    override fun getSizeRange(): IntRange = SIZE_START_INDEX..SIZE_END_INDEX

    /** Returns the range of bytes (inclusive) within the watermark bytes that represent the hash */
    override fun getHashRange(): IntRange = HASH_START_INDEX..HASH_END_INDEX

    /**
     * Calculates the SHA3-256 hash of the watermark content.
     *
     * The bytes of `getHashPlaceholder()` replace the actual checksum in the watermark
     * content.
     */
    override fun calculateHash(): Result<List<Byte>> {
        val hashInput =
            with(getHashInput()) {
                if (!isSuccess) return status.into<_>()
                value!!
            }

        return Result.success(SHA3256Watermark.calculateHash(hashInput))
    }
}

@JsExport
class CompressedRawWatermark(content: List<Byte>) : Trendmark(content), Trendmark.Compressed {
    companion object {
        const val SOURCE = "Trendmark.CompressedRawWatermark"
        const val TYPE_TAG: UByte = 254u

        /** Creates a new `CompressedRawWatermark` containing [content] */
        fun new(content: List<Byte>): CompressedRawWatermark {
            val compressedContent = Compression.deflate(content)
            return CompressedRawWatermark(RawWatermark.createRaw(TYPE_TAG, compressedContent))
        }

        /** Creates a new `CompressedRawWatermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())
    }

    /** Constant function that returns the name of the specific Trendmark */
    override fun getSource(): String = SOURCE

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getTag(): UByte = TYPE_TAG

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent(): Result<List<Byte>> {
        val compressedContent = watermarkContent.drop(TAG_SIZE)
        return Compression.inflate(compressedContent)
    }
}

@JsExport
class CompressedSizedWatermark(content: List<Byte>) :
    Trendmark(content), Trendmark.Sized, Trendmark.Compressed {
    companion object {
        const val SOURCE = "Trendmark.CompressedSizedWatermark"
        const val TYPE_TAG: UByte = 253u

        /** Creates a new `CompressedSizedWatermark` containing [content] */
        fun new(content: List<Byte>): CompressedSizedWatermark {
            val compressedContent = Compression.deflate(content)
            return CompressedSizedWatermark(SizedWatermark.createRaw(TYPE_TAG, compressedContent))
        }

        /** Creates a new `CompressedSizedWatermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())
    }

    /** Constant function that returns the name of the specific Trendmark */
    override fun getSource(): String = SOURCE

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getTag(): UByte = TYPE_TAG

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent(): Result<List<Byte>> {
        val compressedContent = watermarkContent.drop(TAG_SIZE + SizedWatermark.SIZE_SIZE)
        return Compression.inflate(compressedContent)
    }

    /** Returns the range of bytes (inclusive) within the watermark bytes that represent the size */
    override fun getSizeRange(): IntRange =
        SizedWatermark.SIZE_START_INDEX..SizedWatermark.SIZE_END_INDEX
}

@JsExport
class CompressedCRC32Watermark(content: List<Byte>) :
    Trendmark(content), Trendmark.Compressed, Trendmark.Checksum {
    companion object {
        const val SOURCE = "Trendmark.CompressedCRC32Watermark"
        const val TYPE_TAG: UByte = 252u

        /** Creates a new `CompressedCRC32Watermark` containing [content] */
        fun new(content: List<Byte>): CompressedCRC32Watermark {
            val compressedContent = Compression.deflate(content)
            val watermark =
                CompressedCRC32Watermark(CRC32Watermark.createRaw(TYPE_TAG, compressedContent))
            watermark.updateChecksum()
            return watermark
        }

        /** Creates a new `CompressedCRC32Watermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())
    }

    /** Constant function that returns the name of the specific Trendmark */
    override fun getSource(): String = SOURCE

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getTag(): UByte = TYPE_TAG

    /**
     * Returns the range of bytes (inclusive) within the watermark bytes that represent the checksum
     */
    override fun getChecksumRange(): IntRange =
        CRC32Watermark.CHECKSUM_START_INDEX..CRC32Watermark.CHECKSUM_END_INDEX

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent(): Result<List<Byte>> {
        val compressedContent = watermarkContent.drop(TAG_SIZE + CRC32Watermark.CHECKSUM_SIZE)
        return Compression.inflate(compressedContent)
    }

    /**
     * Returns the range of bytes (inclusive) within the watermark bytes that represent the checksum
     */
    override fun calculateChecksum(): Result<UInt> {
        val checksumContent =
            with(getChecksumInput()) {
                if (!isSuccess) return status.into<_>()
                value!!
            }

        return Result.success(CRC32Watermark.calculateChecksum(checksumContent))
    }
}

@JsExport
class CompressedSizedCRC32Watermark(content: List<Byte>) :
    Trendmark(content), Trendmark.Sized, Trendmark.Checksum {
    companion object {
        const val SOURCE = "Trendmark.CompressedSizedCRC32Watermark"
        const val TYPE_TAG: UByte = 251u

        /** Creates a new `CompressedSizedCRC32Watermark` containing [content] */
        fun new(content: List<Byte>): CompressedSizedCRC32Watermark {
            val compressedContent = Compression.deflate(content)
            val watermark =
                CompressedSizedCRC32Watermark(
                    SizedCRC32Watermark.createRaw(TYPE_TAG, compressedContent),
                )
            watermark.updateChecksum()
            return watermark
        }

        /** Creates a new `CompressedSizedCRC32Watermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())
    }

    /** Constant function that returns the name of the specific Trendmark */
    override fun getSource(): String = SOURCE

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getTag(): UByte = TYPE_TAG

    /** Returns the range of bytes (inclusive) within the watermark bytes that represent the size */
    override fun getSizeRange(): IntRange =
        SizedCRC32Watermark.SIZE_START_INDEX..SizedCRC32Watermark.SIZE_END_INDEX

    /**
     * Returns the range of bytes (inclusive) within the watermark bytes that represent the checksum
     */
    override fun getChecksumRange(): IntRange =
        SizedCRC32Watermark.CHECKSUM_START_INDEX..SizedCRC32Watermark.CHECKSUM_END_INDEX

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent(): Result<List<Byte>> {
        val contentOffset =
            TAG_SIZE + SizedCRC32Watermark.SIZE_SIZE + SizedCRC32Watermark.CHECKSUM_SIZE
        val compressedContent = watermarkContent.drop(contentOffset)
        return Compression.inflate(compressedContent)
    }

    /**
     * Calculates the CRC32 checksum of the watermark content.
     *
     * The bytes of `getChecksumPlaceholder()` replace the actual checksum in the watermark content.
     */
    override fun calculateChecksum(): Result<UInt> {
        val checksumContent =
            with(getChecksumInput()) {
                if (!isSuccess) return status.into<_>()
                value!!
            }

        return Result.success(CRC32Watermark.calculateChecksum(checksumContent))
    }
}

@JsExport
class CompressedSHA3256Watermark(content: List<Byte>) :
    Trendmark(content), Trendmark.Compressed, Trendmark.Hash {
    companion object {
        const val SOURCE = "Trendmark.CompressedSHA3256Watermark"
        const val TYPE_TAG: UByte = 250u

        /** Creates a new `CompressedSHA3256Watermark` containing [content] */
        fun new(content: List<Byte>): CompressedSHA3256Watermark {
            val compressedContent = Compression.deflate(content)
            val watermark =
                CompressedSHA3256Watermark(
                    SHA3256Watermark.createRaw(TYPE_TAG, compressedContent),
                )
            watermark.updateHash()
            return watermark
        }

        /** Creates a new `CompressedSHA3256Watermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())
    }

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getTag(): UByte = TYPE_TAG

    /** Constant function that returns the name of the specific Trendmark */
    override fun getSource(): String = SOURCE

    /** Returns the range of bytes (inclusive) within the watermark bytes that represent the hash */
    override fun getHashRange(): IntRange =
        SHA3256Watermark.HASH_START_INDEX..SHA3256Watermark.HASH_END_INDEX

    /**
     * Calculates the SHA3-256 hash of the watermark content.
     *
     * The bytes of `getHashPlaceholder()` replace the actual checksum in the watermark
     * content.
     */
    override fun calculateHash(): Result<List<Byte>> {
        val hashInput =
            with(getHashInput()) {
                if (!isSuccess) return status.into<_>()
                value!!
            }
        return Result.success(SHA3256Watermark.calculateHash(hashInput))
    }

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent(): Result<List<Byte>> {
        val contentOffset = TAG_SIZE + SHA3256Watermark.HASH_SIZE
        val compressedContent = watermarkContent.drop(contentOffset)
        return Compression.inflate(compressedContent)
    }
}

@JsExport
class CompressedSizedSHA3256Watermark(content: List<Byte>) :
    Trendmark(content), Trendmark.Compressed, Trendmark.Sized, Trendmark.Hash {
    companion object {
        const val SOURCE = "Trendmark.CompressedSizedSHA3256Watermark"
        const val TYPE_TAG: UByte = 249u

        /** Creates a new `CompressedSizedSHA3256Watermark` containing [content] */
        fun new(content: List<Byte>): CompressedSizedSHA3256Watermark {
            val compressedContent = Compression.deflate(content)
            val watermark =
                CompressedSizedSHA3256Watermark(
                    SizedSHA3256Watermark.createRaw(TYPE_TAG, compressedContent),
                )
            watermark.updateHash()
            return watermark
        }

        /** Creates a new `CompressedSizedSHA3256Watermark` with [text] as content */
        fun fromString(text: String) = new(text.encodeToByteArray().asList())
    }

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getTag(): UByte = TYPE_TAG

    /** Constant function that returns the tag used to encode this Trendmark class */
    override fun getSource(): String = SOURCE

    /** Returns the range of bytes (inclusive) within the watermark bytes that represent the size */
    override fun getSizeRange(): IntRange =
        SizedSHA3256Watermark.SIZE_START_INDEX..SizedSHA3256Watermark.SIZE_END_INDEX

    /** Returns the range of bytes (inclusive) within the watermark bytes that represent the hash */
    override fun getHashRange(): IntRange =
        SizedSHA3256Watermark.HASH_START_INDEX..SizedSHA3256Watermark.HASH_END_INDEX

    /**
     * Calculates the SHA3-256 hash of the watermark content.
     *
     * The bytes of `getHashPlaceholder()` replace the actual checksum in the watermark
     * content.
     */
    override fun calculateHash(): Result<List<Byte>> {
        val hashInput =
            with(getHashInput()) {
                if (!isSuccess) return status.into<_>()
                value!!
            }
        return Result.success(SHA3256Watermark.calculateHash(hashInput))
    }

    /** Returns the decoded information stored in the Trendmark */
    override fun getContent(): Result<List<Byte>> {
        val contentOffset =
            TAG_SIZE + SizedSHA3256Watermark.SIZE_SIZE + SizedSHA3256Watermark.HASH_SIZE
        val compressedContent = watermarkContent.drop(contentOffset)
        return Compression.inflate(compressedContent)
    }
}
