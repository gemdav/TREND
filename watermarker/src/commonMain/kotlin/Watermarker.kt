/*
 * Copyright (c) 2023-2024 Fraunhofer-Gesellschaft zur Förderung der angewandten Forschung e.V.
 *
 * This work is licensed under the Fraunhofer License (on the basis of the MIT license)
 * that can be found in the LICENSE file.
 */
package de.fraunhofer.isst.trend.watermarker

import de.fraunhofer.isst.trend.watermarker.fileWatermarker.FileWatermarker
import de.fraunhofer.isst.trend.watermarker.fileWatermarker.TextWatermark
import de.fraunhofer.isst.trend.watermarker.fileWatermarker.TextWatermarker
import de.fraunhofer.isst.trend.watermarker.fileWatermarker.ZipWatermarker
import de.fraunhofer.isst.trend.watermarker.files.TextFile
import de.fraunhofer.isst.trend.watermarker.returnTypes.Event
import de.fraunhofer.isst.trend.watermarker.returnTypes.Result
import de.fraunhofer.isst.trend.watermarker.watermarks.Textmark
import de.fraunhofer.isst.trend.watermarker.watermarks.Trendmark
import de.fraunhofer.isst.trend.watermarker.watermarks.TrendmarkBuilder
import de.fraunhofer.isst.trend.watermarker.watermarks.Watermark
import kotlin.js.JsExport
import kotlin.js.JsName

@JsExport
sealed class SupportedFileType {
    abstract val watermarker: FileWatermarker<*, *>

    object Text : SupportedFileType() {
        override var watermarker: TextWatermarker = TextWatermarker.default()
    }

    object Zip : SupportedFileType() {
        override var watermarker: ZipWatermarker = ZipWatermarker
    }

    companion object {
        private val extensionMap =
            mutableMapOf(
                "zip" to Zip,
                "jar" to Zip,
                "txt" to Text,
                "md" to Text,
            )

        /*
         * TODO: Write test when builder pattern is implemented for
         *  TextWatermarker / ZipWatermarker
         */

        /** Returns a variant of SupportedFileType if [extension] is supported */
        fun fromExtension(extension: String): Result<SupportedFileType> {
            val fileType = extensionMap[extension]
            return if (fileType == null) {
                UnsupportedTypeError(extension).into<_>()
            } else {
                Result.success(fileType)
            }
        }

        /** Registers an [extension] to a variant of SupportedFileType */
        fun registerExtension(
            extension: String,
            fileType: SupportedFileType,
        ) {
            extensionMap[extension] = fileType
        }

        /** Registers [watermarker] for zip files */
        fun registerZipWatermarker(watermarker: ZipWatermarker) {
            Zip.watermarker = watermarker
        }

        /** Registers [watermarker] for TextWatermarker */
        fun registerTextWatermarker(watermarker: TextWatermarker) {
            Text.watermarker = watermarker
        }

        const val SOURCE: String = "SupportedFileType"
    }

    class NoFileTypeError(val path: String) : Event.Error(SOURCE) {
        /** Returns a String explaining the event */
        override fun getMessage(): String = "Could not determine file type of $path!"
    }

    class UnsupportedTypeError(val type: String) : Event.Error(SOURCE) {
        /** Returns a String explaining the event */
        override fun getMessage(): String = "Unsupported file type: $type!"
    }
}

@JsExport
open class Watermarker {
    companion object {
        const val SOURCE = "Watermarker"
    }

    /** Watermarks string [text] with [watermark] */
    @JsName("textAddWatermarkBytes")
    fun textAddWatermark(
        text: String,
        watermark: List<Byte>,
    ): Result<String> {
        val watermarker = SupportedFileType.Text.watermarker

        val textFile = TextFile.fromString(text)

        val parsedWatermark =
            TextWatermark.fromUncompressedBytes(watermark, watermarker.compression)
        val status = watermarker.addWatermark(textFile, parsedWatermark)

        return if (status.isError) {
            status.into()
        } else {
            status.into(textFile.content)
        }
    }

    /** Watermarks string [text] with [watermark] */
    fun textAddWatermark(
        text: String,
        watermark: Watermark,
    ): Result<String> {
        val watermarkBytes = watermark.watermarkContent
        return textAddWatermark(text, watermarkBytes)
    }

    /** Watermarks string [text] with [trendmarkBuilder] */
    @JsName("textAddTrendmarkBuilder")
    fun textAddWatermark(
        text: String,
        trendmarkBuilder: TrendmarkBuilder,
    ): Result<String> {
        return textAddWatermark(text, trendmarkBuilder.finish())
    }

    /** Checks if [text] contains a watermark */
    fun textContainsWatermark(text: String): Boolean {
        val watermarker = SupportedFileType.Text.watermarker

        val textFile = TextFile.fromString(text)

        return watermarker.containsWatermark(textFile)
    }

    /**
     * Returns all watermarks in [text].
     *
     * When [squash] is true: watermarks with the same content are merged.
     */
    fun textGetWatermarks(
        text: String,
        squash: Boolean = true,
    ): Result<List<TextWatermark>> {
        val watermarker = SupportedFileType.Text.watermarker

        val textFile = TextFile.fromString(text)
        val result = watermarker.getWatermarks(textFile)

        if (squash && result.hasValue) {
            return result.into(squashWatermarks(result.value!!))
        }

        return result
    }

    /**
     * Returns all Trendmarks in [text].
     *
     * When [squash] is true: watermarks with the same content are merged.
     *
     * Returns a warning if some watermarks could not be converted to Trendmarks.
     * Returns an error if no watermark could be converted to a Trendmark.
     */
    fun textGetTrendmarks(
        text: String,
        squash: Boolean = true,
    ): Result<List<Trendmark>> {
        val (watermarks, status) =
            with(textGetWatermarks(text, squash)) {
                if (!hasValue) {
                    return this.status.into()
                }
                value!! to status
            }

        val trendmarks =
            watermarks.mapNotNull { watermark ->
                val trendmark = Trendmark.fromWatermark(watermark)
                status.appendStatus(trendmark.status)
                trendmark.value
            }

        if (status.isError && trendmarks.isNotEmpty()) {
            status.addEvent(
                FailedTrendmarkExtractionsWarning("$SOURCE.textGetTrendmarks"),
                overrideSeverity = true,
            )
        }

        return if (status.isError) {
            status.into()
        } else {
            status.into(trendmarks)
        }
    }

    /**
     * Returns all Textmarks in [text]
     *
     * When [squash] is true: watermarks with the same content are merged.
     * When [errorOnInvalidUTF8] is true: invalid bytes sequences cause an error.
     *                           is false: invalid bytes sequences are replace with the char �.
     *
     * Returns a warning if some watermarks could not be converted to Trendmarks.
     * Returns an error if no watermark could be converted to a Trendmark.
     *
     * Returns a warning if some Trendmarks could not be converted to Textmarks.
     * Returns an error if no Trendmark could be converted to a Textmark.
     */
    fun textGetTextmarks(
        text: String,
        squash: Boolean = true,
        errorOnInvalidUTF8: Boolean = false,
    ): Result<List<Textmark>> {
        val (trendmarks, status) =
            with(textGetTrendmarks(text, squash)) {
                if (!hasValue) {
                    return this.status.into()
                }
                value!! to status
            }

        val textmarks =
            trendmarks.mapNotNull { trendmark ->
                val textmark = Textmark.fromTrendmark(trendmark, errorOnInvalidUTF8)
                status.appendStatus(textmark.status)
                textmark.value
            }

        if (status.isError && textmarks.isNotEmpty()) {
            status.addEvent(
                FailedTextmarkExtractionsWarning("$SOURCE.textGetTextmarks"),
                overrideSeverity = true,
            )
        }

        return if (status.isError) {
            status.into()
        } else {
            status.into(textmarks)
        }
    }

    /** Returns [text] without watermarks */
    fun textRemoveWatermarks(text: String): Result<String> {
        val watermarker = SupportedFileType.Text.watermarker

        val textFile = TextFile.fromString(text)

        val status = watermarker.removeWatermarks(textFile).status

        return status.into(textFile.content)
    }

    class FailedTrendmarkExtractionsWarning(source: String) : Event.Warning(source) {
        /** Returns a String explaining the event */
        override fun getMessage(): String =
            "Could not extract and convert all watermarks to Trendmarks"
    }

    class FailedTextmarkExtractionsWarning(source: String) : Event.Warning(source) {
        /** Returns a String explaining the event */
        override fun getMessage(): String =
            "Could not extract and convert all watermarks to Textmarks"
    }
}

/** Returns [watermarks] without duplicates */
fun <T : Watermark> squashWatermarks(watermarks: List<T>): List<T> {
    return watermarks.toSet().toList()
}