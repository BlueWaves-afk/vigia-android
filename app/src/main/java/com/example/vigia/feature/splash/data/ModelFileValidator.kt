package com.example.vigia.feature.splash.data

import java.io.File

class ModelFileValidator {

    /**
     * Validates required model files:
     * - Deletes any ".temp" leftover file
     * - Deletes corrupt/too-small files so we don't skip them next run
     */
    fun validateOrCleanup(dir: File, requiredFiles: List<String>): ValidationResult {
        if (!dir.exists()) return ValidationResult(allValid = false, perFileValid = requiredFiles.map { false })

        val perFileValid = requiredFiles.map { fileName ->
            val file = File(dir, fileName)
            val tempFile = File(dir, "$fileName.temp")

            if (tempFile.exists()) tempFile.delete()

            val existsAndNonEmpty = file.exists() && file.length() > 0

            val isDataFile = fileName.endsWith(".data")
            val tooSmallData = isDataFile && file.length() < ModelManifest.MIN_DATA_BYTES

            val isOnnx = fileName.endsWith(".onnx")
            val fakeOnnx = isOnnx && file.length() < ModelManifest.MIN_ONNX_BYTES

            val ok = existsAndNonEmpty && !tooSmallData && !fakeOnnx
            if (!ok && file.exists()) file.delete()

            ok
        }

        return ValidationResult(allValid = perFileValid.all { it }, perFileValid = perFileValid)
    }

    data class ValidationResult(
        val allValid: Boolean,
        val perFileValid: List<Boolean>
    )
}