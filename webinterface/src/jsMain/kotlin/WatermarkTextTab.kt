/*
 * Copyright (c) 2023-2024 Fraunhofer-Gesellschaft zur Förderung der angewandten Forschung e.V.
 *
 * This work is licensed under the Fraunhofer License (on the basis of the MIT license)
 * that can be found in the LICENSE file.
 */

import de.fraunhofer.isst.trend.watermarker.Watermarker
import de.fraunhofer.isst.trend.watermarker.fileWatermarker.TextWatermarker
import de.fraunhofer.isst.trend.watermarker.watermarks.Watermark
import io.kvision.core.onInput
import io.kvision.form.FormMethod
import io.kvision.form.formPanel
import io.kvision.form.text.Text
import io.kvision.form.text.TextArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.span
import io.kvision.modal.Alert
import io.kvision.modal.Confirm
import io.kvision.panel.HPanel
import io.kvision.panel.SimplePanel
import io.kvision.progress.Progress
import io.kvision.progress.progressNumeric
import io.kvision.state.ObservableValue
import io.kvision.state.bind
import io.kvision.utils.em
import io.kvision.utils.px
import kotlinx.serialization.Serializable
import kotlin.math.round

@Serializable
data class WatermarkerTextForm(
    val watermark: String,
    val text: String,
)

class WatermarkTextTab : SimplePanel() {
    private val textWatermarker = TextWatermarker.default()

    // Input fields
    private val watermarkerInput =
        Text(label = "Watermark") {
            placeholder = "Enter words that should be hidden in the text"
        }
    private val coverTextInput =
        TextArea(label = "Cover Text") {
            placeholder = "Text that should be watermarked"
        }

    // Progress bar
    private var min: Int = -1
    private val capacityObservable = ObservableValue(-1)
    private val progressBar =
        Progress(-1, 0) {
            marginBottom = 0.px
            width = 300.px
            progressNumeric(this.bounds.value.min) {
                striped = true
            }
        }

    // Submit button
    private val submitButton =
        Button(
            "Add Watermark",
            "fas fa-tarp",
            style = ButtonStyle.PRIMARY,
            disabled = true,
        )

    init {
        marginTop = 1.em
        span("Embed a custom watermark into a text.")

        // Form to watermark a text/string
        val textFormPanel =
            formPanel<WatermarkerTextForm> {
                marginTop = 1.em

                method = FormMethod.GET

                add(
                    WatermarkerTextForm::watermark,
                    watermarkerInput,
                    required = true,
                )

                add(
                    WatermarkerTextForm::text,
                    coverTextInput,
                    required = true,
                )

                add(progressBar)
                span().bind(capacityObservable) {
                    val percentage =
                        round(progressBar.getFirstProgressBar()?.width?.first?.toDouble() ?: 0.0)

                    +"$percentage %"
                }
            }

        // Check if watermark fits into the cover text
        watermarkerInput.onInput { updateCapacity() }
        coverTextInput.onInput { updateCapacity() }

        textFormPanel.add(
            HPanel {
                // Submit button
                add(submitButton)
                submitButton.onClick {
                    if (textFormPanel.validate()) {
                        println("Starting text watermark process ...")
                        val watermarkedText =
                            addWatermarkToText(
                                textFormPanel.getData().watermark,
                                textFormPanel.getData().text,
                            )
                        Alert.show("Successful", watermarkedText)
                    }
                }

                // Clear data button
                button("Clear data", "fas fa-trash", ButtonStyle.DANGER).onClick {
                    Confirm.show(
                        "Are you sure?",
                        "Do you want to clear your data?",
                        yesTitle = "Yes",
                        noTitle = "No",
                        cancelTitle = "Cancel",
                    ) {
                        textFormPanel.clearData()
                        capacityObservable.value = -1
                        min = -1
                        progressBar.getFirstProgressBar()?.value = -1
                        progressBar.setBounds(-1, 0)
                    }
                }
            },
        )
    }

    /**
     * Calculates on every user input if the watermark fits into the cover text
     */
    private fun updateCapacity() {
        capacityObservable.value =
            watermarkFitsInText(
                watermarkerInput.value.toString(),
                coverTextInput.value.toString(),
            )

        // Enable or disable submit form button
        submitButton.disabled = capacityObservable.value < 0

        // Update progress bar
        min = if (capacityObservable.value < min) capacityObservable.value else min
        progressBar.setBounds(min, 0)
        progressBar.getFirstProgressBar()?.value =
            (if (watermarkerInput.value == null) min else capacityObservable.value)
    }

    /** Adds a [watermark] string to [text] and returns the watermarked text */
    private fun addWatermarkToText(
        watermark: String,
        text: String,
    ): String {
        val watermarker = Watermarker()
        val result = watermarker.textAddWatermark(text, watermark.encodeToByteArray().asList())

        return if (result.isSuccess) {
            result.value ?: ""
        } else {
            // TODO: Proper error handling
            result.toString()
        }
    }

    /**
     * Calculates if the [watermark] fits into the [text]
     *
     * Returns:
     *  - a negative value with the number of missing insert positions ([watermark] doesn't fit)
     *  - a zero if the [watermark] perfectly fits in the [text] one time
     *  - a positive value with the number of remaining insert positions ([watermark] fits)
     */
    private fun watermarkFitsInText(
        watermark: String,
        text: String,
    ): Int {
        val parsedWatermark = Watermark.fromString(watermark)

        val numberOfInsertPositions = textWatermarker.placement(text).count()
        val numberOfNeededPositions = textWatermarker.getMinimumInsertPositions(parsedWatermark)

        return numberOfInsertPositions - numberOfNeededPositions
    }
}
