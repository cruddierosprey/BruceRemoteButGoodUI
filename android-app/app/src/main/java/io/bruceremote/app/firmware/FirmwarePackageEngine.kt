package io.bruceremote.app.firmware

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

class FirmwarePackageEngine(
    private val limits: FirmwareLimits = FirmwareLimits(),
) {
    /**
     * Applies an authenticated package to an exact base image.
     *
     * The output path must not exist. All input and output hashes, every flash
     * segment hash, device identity and bounds are checked before success is
     * returned. Any failed apply removes the newly-created partial output.
     */
    fun apply(
        verifiedManifest: VerifiedFirmwareManifest,
        inputFirmware: File,
        patchPayload: File,
        outputFirmware: File,
        expectedBoardId: String? = null,
    ): AppliedFirmware {
        val manifest = verifiedManifest.manifest
        val profile = DeviceProfiles.validate(manifest.target)
        if (expectedBoardId != null && expectedBoardId != profile.boardId) {
            throw FirmwarePackageException.DeviceProfileMismatch(
                profile.boardId,
                "caller selected '$expectedBoardId'",
            )
        }
        enforceLocalLimits(manifest)
        validateDistinctPaths(inputFirmware, patchPayload, outputFirmware)

        verifyArtifact(
            inputFirmware,
            manifest.input,
            ArtifactRole.INPUT_FIRMWARE,
        )
        verifyArtifact(
            patchPayload,
            ArtifactDescriptor(manifest.patch.sizeBytes, manifest.patch.sha256),
            ArtifactRole.PATCH_PAYLOAD,
        )

        when (manifest.patch.algorithm) {
            PatchAlgorithm.BRP1_CHUNK_V1 -> Brp1ChunkPatch.apply(
                inputFirmware = inputFirmware,
                patchPayload = patchPayload,
                outputFirmware = outputFirmware,
                expectedInputBytes = manifest.input.sizeBytes,
                expectedOutputBytes = manifest.output.sizeBytes,
                limits = limits,
            )
        }

        try {
            verifyArtifact(
                outputFirmware,
                manifest.output,
                ArtifactRole.OUTPUT_FIRMWARE,
            )
            verifySegments(outputFirmware, manifest.flashSegments)
        } catch (error: FirmwarePackageException) {
            if (outputFirmware.exists() && !outputFirmware.delete()) {
                throw FirmwarePackageException.OutputCleanupFailed(outputFirmware, error)
            }
            throw error
        }

        return AppliedFirmware(
            outputFile = outputFirmware,
            sha256 = manifest.output.sha256,
            profile = profile,
            flashSegments = manifest.flashSegments,
        )
    }

    private fun enforceLocalLimits(manifest: FirmwareManifest) {
        if (manifest.input.sizeBytes > limits.maximumArtifactBytes) {
            throw FirmwarePackageException.PatchLimitExceeded(
                "input bytes",
                manifest.input.sizeBytes,
                limits.maximumArtifactBytes,
            )
        }
        if (manifest.output.sizeBytes > limits.maximumArtifactBytes) {
            throw FirmwarePackageException.PatchLimitExceeded(
                "output bytes",
                manifest.output.sizeBytes,
                limits.maximumArtifactBytes,
            )
        }
        if (manifest.patch.sizeBytes > limits.maximumPatchBytes) {
            throw FirmwarePackageException.PatchLimitExceeded(
                "patch bytes",
                manifest.patch.sizeBytes,
                limits.maximumPatchBytes,
            )
        }
        if (manifest.flashSegments.size > limits.maximumFlashSegments) {
            throw FirmwarePackageException.PatchLimitExceeded(
                "flash segment count",
                manifest.flashSegments.size.toLong(),
                limits.maximumFlashSegments.toLong(),
            )
        }
    }

    private fun validateDistinctPaths(input: File, patch: File, output: File) {
        val canonicalInput = canonical(input, "resolving input path")
        val canonicalPatch = canonical(patch, "resolving patch path")
        val canonicalOutput = canonical(output, "resolving output path")
        if (canonicalInput == canonicalPatch) {
            throw FirmwarePackageException.PathConflict(
                "input firmware",
                "patch payload",
                canonicalInput,
            )
        }
        if (canonicalInput == canonicalOutput) {
            throw FirmwarePackageException.PathConflict(
                "input firmware",
                "output firmware",
                canonicalInput,
            )
        }
        if (canonicalPatch == canonicalOutput) {
            throw FirmwarePackageException.PathConflict(
                "patch payload",
                "output firmware",
                canonicalPatch,
            )
        }
        if (output.exists()) {
            throw FirmwarePackageException.OutputAlreadyExists(output)
        }
    }

    private fun canonical(file: File, operation: String): File =
        try {
            file.canonicalFile
        } catch (error: IOException) {
            throw FirmwarePackageException.IoFailure(operation, file, error)
        }

    private fun verifyArtifact(
        file: File,
        descriptor: ArtifactDescriptor,
        role: ArtifactRole,
    ) {
        if (!file.isFile) {
            throw FirmwarePackageException.ArtifactMissing(role, file)
        }
        val actualSize = file.length()
        if (actualSize != descriptor.sizeBytes) {
            throw FirmwarePackageException.ArtifactSizeMismatch(
                role,
                descriptor.sizeBytes,
                actualSize,
            )
        }
        val actualDigest = FirmwareHashing.sha256(file, role)
        if (!FirmwareHashing.matches(descriptor.sha256, actualDigest)) {
            throw FirmwarePackageException.ArtifactDigestMismatch(
                role,
                descriptor.sha256,
                actualDigest,
            )
        }
    }

    private fun verifySegments(output: File, segments: List<FlashSegment>) {
        try {
            RandomAccessFile(output, "r").use { file ->
                segments.forEachIndexed { index, segment ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.seek(segment.sourceOffset)
                    var remaining = segment.sizeBytes
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val requested = minOf(remaining, buffer.size.toLong()).toInt()
                        file.readFully(buffer, 0, requested)
                        digest.update(buffer, 0, requested)
                        remaining -= requested.toLong()
                    }
                    val actual = Sha256Digest.fromBytes(digest.digest())
                    if (!FirmwareHashing.matches(segment.sha256, actual)) {
                        throw FirmwarePackageException.SegmentDigestMismatch(
                            index,
                            segment.sha256,
                            actual,
                        )
                    }
                }
            }
        } catch (error: FirmwarePackageException) {
            throw error
        } catch (error: IOException) {
            throw FirmwarePackageException.IoFailure(
                "verifying flash segments in",
                output,
                error,
            )
        }
    }
}
