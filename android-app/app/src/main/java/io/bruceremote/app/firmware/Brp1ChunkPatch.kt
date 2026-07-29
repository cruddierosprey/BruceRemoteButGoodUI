package io.bruceremote.app.firmware

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.RandomAccessFile

internal object Brp1ChunkPatch {
    const val VERSION = 1
    const val COPY_OPCODE = 0
    const val DATA_OPCODE = 1
    val MAGIC = byteArrayOf('B'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())

    fun apply(
        inputFirmware: File,
        patchPayload: File,
        outputFirmware: File,
        expectedInputBytes: Long,
        expectedOutputBytes: Long,
        limits: FirmwareLimits,
    ) {
        var outputCreated = false
        try {
            RandomAccessFile(inputFirmware, "r").use { input ->
                CountingInputStream(
                    BufferedInputStream(FileInputStream(patchPayload), IO_BUFFER_SIZE),
                ).use { countingInput ->
                    val patch = DataInputStream(countingInput)
                    readAndValidateHeader(
                        patch = patch,
                        position = { countingInput.count },
                        expectedInputBytes = expectedInputBytes,
                        expectedOutputBytes = expectedOutputBytes,
                        limits = limits,
                    ).also { instructionCount ->
                        createOutput(outputFirmware)
                        outputCreated = true
                        FileOutputStream(outputFirmware, false).use { fileOutput ->
                            BufferedOutputStream(fileOutput, IO_BUFFER_SIZE).use { output ->
                                applyInstructions(
                                    input = input,
                                    patch = patch,
                                    output = output,
                                    position = { countingInput.count },
                                    instructionCount = instructionCount,
                                    inputSize = expectedInputBytes,
                                    outputSize = expectedOutputBytes,
                                    limits = limits,
                                )
                                output.flush()
                                fileOutput.fd.sync()
                            }
                        }
                    }
                }
            }
        } catch (error: FirmwarePackageException) {
            cleanupAndRethrow(outputFirmware, outputCreated, error)
        } catch (error: EOFException) {
            val failure = FirmwarePackageException.PatchFormat(
                patchOffset = patchPayload.length(),
                detail = "patch is truncated",
                cause = error,
            )
            cleanupAndRethrow(outputFirmware, outputCreated, failure)
        } catch (error: IOException) {
            val failure = FirmwarePackageException.IoFailure(
                "applying patch using",
                patchPayload,
                error,
            )
            cleanupAndRethrow(outputFirmware, outputCreated, failure)
        }
    }

    private fun readAndValidateHeader(
        patch: DataInputStream,
        position: () -> Long,
        expectedInputBytes: Long,
        expectedOutputBytes: Long,
        limits: FirmwareLimits,
    ): Int {
        val magic = ByteArray(MAGIC.size)
        patch.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            format(position(), "magic must be BRP1")
        }
        val version = patch.readUnsignedByte()
        if (version != VERSION) {
            format(position(), "unsupported version $version")
        }
        val flags = patch.readUnsignedByte()
        val reserved = patch.readUnsignedShort()
        if (flags != 0 || reserved != 0) {
            format(position(), "flags and reserved bytes must be zero")
        }

        val inputSize = patch.readLong()
        val outputSize = patch.readLong()
        val instructionCount = patch.readInt()
        if (inputSize != expectedInputBytes) {
            format(
                position(),
                "base size $inputSize does not match manifest size $expectedInputBytes",
            )
        }
        if (outputSize != expectedOutputBytes) {
            format(
                position(),
                "output size $outputSize does not match manifest size $expectedOutputBytes",
            )
        }
        if (inputSize < 1 || inputSize > limits.maximumArtifactBytes) {
            throw FirmwarePackageException.PatchLimitExceeded(
                "input bytes",
                inputSize,
                limits.maximumArtifactBytes,
            )
        }
        if (outputSize < 1 || outputSize > limits.maximumArtifactBytes) {
            throw FirmwarePackageException.PatchLimitExceeded(
                "output bytes",
                outputSize,
                limits.maximumArtifactBytes,
            )
        }
        if (instructionCount < 1 || instructionCount > limits.maximumInstructions) {
            throw FirmwarePackageException.PatchLimitExceeded(
                "instruction count",
                instructionCount.toLong(),
                limits.maximumInstructions.toLong(),
            )
        }
        return instructionCount
    }

    private fun applyInstructions(
        input: RandomAccessFile,
        patch: DataInputStream,
        output: BufferedOutputStream,
        position: () -> Long,
        instructionCount: Int,
        inputSize: Long,
        outputSize: Long,
        limits: FirmwareLimits,
    ) {
        val buffer = ByteArray(minOf(IO_BUFFER_SIZE, limits.maximumChunkBytes))
        var emitted = 0L

        repeat(instructionCount) { instructionIndex ->
            val opcode = patch.readUnsignedByte()
            when (opcode) {
                COPY_OPCODE -> {
                    val inputOffset = patch.readLong()
                    val length = patch.readInt()
                    validateChunkLength(length, limits)
                    if (inputOffset < 0 ||
                        inputOffset > inputSize ||
                        length.toLong() > inputSize - inputOffset
                    ) {
                        format(
                            position(),
                            "COPY $instructionIndex range is outside input firmware",
                        )
                    }
                    ensureOutputCapacity(
                        emitted,
                        length,
                        outputSize,
                        instructionIndex,
                        position(),
                    )
                    input.seek(inputOffset)
                    copyExactly(input, output, length, buffer)
                    emitted += length.toLong()
                }

                DATA_OPCODE -> {
                    val length = patch.readInt()
                    validateChunkLength(length, limits)
                    ensureOutputCapacity(
                        emitted,
                        length,
                        outputSize,
                        instructionIndex,
                        position(),
                    )
                    var remaining = length
                    while (remaining > 0) {
                        val count = minOf(remaining, buffer.size)
                        patch.readFully(buffer, 0, count)
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    emitted += length.toLong()
                }

                else -> format(
                    position(),
                    "instruction $instructionIndex has unknown opcode $opcode",
                )
            }
        }

        if (emitted != outputSize) {
            format(
                position(),
                "instructions emit $emitted bytes; manifest requires $outputSize",
            )
        }
        if (patch.read() != -1) {
            format(position(), "trailing patch bytes are not allowed")
        }
    }

    private fun validateChunkLength(length: Int, limits: FirmwareLimits) {
        if (length < 1 || length > limits.maximumChunkBytes) {
            throw FirmwarePackageException.PatchLimitExceeded(
                "instruction chunk bytes",
                length.toLong(),
                limits.maximumChunkBytes.toLong(),
            )
        }
    }

    private fun ensureOutputCapacity(
        emitted: Long,
        length: Int,
        outputSize: Long,
        instructionIndex: Int,
        patchOffset: Long,
    ) {
        if (emitted > outputSize || length.toLong() > outputSize - emitted) {
            format(
                patchOffset,
                "instruction $instructionIndex emits past declared output size",
            )
        }
    }

    private fun copyExactly(
        input: RandomAccessFile,
        output: BufferedOutputStream,
        length: Int,
        buffer: ByteArray,
    ) {
        var remaining = length
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size)
            input.readFully(buffer, 0, count)
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun createOutput(output: File) {
        if (output.exists()) {
            throw FirmwarePackageException.OutputAlreadyExists(output)
        }
        val parent = output.parentFile
        if (parent == null || !parent.isDirectory) {
            throw FirmwarePackageException.IoFailure(
                "creating output in missing parent directory for",
                output,
                IOException("Output parent directory must already exist."),
            )
        }
        val created = try {
            output.createNewFile()
        } catch (error: IOException) {
            throw FirmwarePackageException.IoFailure("creating output", output, error)
        }
        if (!created) {
            throw FirmwarePackageException.OutputAlreadyExists(output)
        }
    }

    private fun cleanupAndRethrow(
        output: File,
        outputCreated: Boolean,
        failure: FirmwarePackageException,
    ): Nothing {
        if (outputCreated && output.exists() && !output.delete()) {
            throw FirmwarePackageException.OutputCleanupFailed(output, failure)
        }
        throw failure
    }

    private fun format(offset: Long, detail: String): Nothing =
        throw FirmwarePackageException.PatchFormat(offset, detail)

    private class CountingInputStream(input: java.io.InputStream) : FilterInputStream(input) {
        var count: Long = 0
            private set

        override fun read(): Int {
            val result = super.read()
            if (result >= 0) count++
            return result
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val result = super.read(buffer, offset, length)
            if (result > 0) count += result.toLong()
            return result
        }
    }

    private const val IO_BUFFER_SIZE = 64 * 1024
}
