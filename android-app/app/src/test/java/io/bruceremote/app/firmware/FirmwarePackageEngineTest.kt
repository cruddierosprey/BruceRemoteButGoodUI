package io.bruceremote.app.firmware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FirmwarePackageEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val base = "0123456789abcdef".toByteArray()
    private val expected = "0123XYZcdef".toByteArray()

    @Test
    fun appliesCopyAndDataChunksWithoutChangingInput() {
        val patch = validPatch()
        val fixture = writeFixture(base, patch)
        val outputFile = File(temporaryFolder.root, "patched.bin")
        val result = FirmwarePackageEngine().apply(
            verifiedManifest = fixture.verified,
            inputFirmware = fixture.inputFile,
            patchPayload = fixture.patchFile,
            outputFirmware = outputFile,
            expectedBoardId = "m5stack-cplus2",
        )

        assertArrayEquals(expected, outputFile.readBytes())
        assertArrayEquals(base, fixture.inputFile.readBytes())
        assertEquals(FirmwareHashing.sha256(expected), result.sha256)
        assertEquals(DeviceProfiles.M5STACK_CPLUS2, result.profile)
    }

    @Test
    fun refusesWrongInputHashBeforeCreatingOutput() {
        val patch = validPatch()
        val fixture = writeFixture(base, patch)
        fixture.inputFile.writeBytes("x123456789abcdef".toByteArray())
        val outputFile = File(temporaryFolder.root, "must-not-exist.bin")

        assertThrows(FirmwarePackageException.ArtifactDigestMismatch::class.java) {
            FirmwarePackageEngine().apply(
                fixture.verified,
                fixture.inputFile,
                fixture.patchFile,
                outputFile,
            )
        }
        assertFalse(outputFile.exists())
    }

    @Test
    fun neverOverwritesAnExistingOutput() {
        val patch = validPatch()
        val fixture = writeFixture(base, patch)
        val outputFile = temporaryFolder.newFile("existing.bin")
        val sentinel = "keep me".toByteArray()
        outputFile.writeBytes(sentinel)

        assertThrows(FirmwarePackageException.OutputAlreadyExists::class.java) {
            FirmwarePackageEngine().apply(
                fixture.verified,
                fixture.inputFile,
                fixture.patchFile,
                outputFile,
            )
        }
        assertArrayEquals(sentinel, outputFile.readBytes())
    }

    @Test
    fun outOfBoundsCopyDeletesPartialOutput() {
        val invalidPatch = FirmwareTestFixtures.buildPatch(
            inputSize = base.size.toLong(),
            outputSize = expected.size.toLong(),
            instructions = listOf(
                FirmwareTestFixtures.Instruction.Copy(
                    inputOffset = base.size.toLong() - 1,
                    length = expected.size,
                ),
            ),
        )
        val fixture = writeFixture(base, invalidPatch)
        val outputFile = File(temporaryFolder.root, "partial.bin")

        assertThrows(FirmwarePackageException.PatchFormat::class.java) {
            FirmwarePackageEngine().apply(
                fixture.verified,
                fixture.inputFile,
                fixture.patchFile,
                outputFile,
            )
        }
        assertFalse(outputFile.exists())
    }

    @Test
    fun trailingPatchBytesAreRejectedAndOutputIsRemoved() {
        val patch = FirmwareTestFixtures.buildPatch(
            inputSize = base.size.toLong(),
            outputSize = expected.size.toLong(),
            instructions = listOf(FirmwareTestFixtures.Instruction.Data(expected)),
            appendGarbage = byteArrayOf(0x55),
        )
        val fixture = writeFixture(base, patch)
        val outputFile = File(temporaryFolder.root, "trailing.bin")

        assertThrows(FirmwarePackageException.PatchFormat::class.java) {
            FirmwarePackageEngine().apply(
                fixture.verified,
                fixture.inputFile,
                fixture.patchFile,
                outputFile,
            )
        }
        assertFalse(outputFile.exists())
    }

    @Test
    fun outputHashMismatchDeletesCompletedOutput() {
        val patch = validPatch()
        val wrongDigest = Sha256Digest.parse("0".repeat(64))
        val fixture = writeFixture(
            inputBytes = base,
            patchBytes = patch,
            outputDigest = wrongDigest,
            segmentDigest = wrongDigest,
        )
        val outputFile = File(temporaryFolder.root, "wrong-hash.bin")

        assertThrows(FirmwarePackageException.ArtifactDigestMismatch::class.java) {
            FirmwarePackageEngine().apply(
                fixture.verified,
                fixture.inputFile,
                fixture.patchFile,
                outputFile,
            )
        }
        assertFalse(outputFile.exists())
    }

    @Test
    fun localInstructionLimitIsEnforced() {
        val patch = validPatch()
        val fixture = writeFixture(base, patch)
        val outputFile = File(temporaryFolder.root, "limited.bin")
        val tightLimits = FirmwareLimits(maximumInstructions = 2)

        assertThrows(FirmwarePackageException.PatchLimitExceeded::class.java) {
            FirmwarePackageEngine(tightLimits).apply(
                fixture.verified,
                fixture.inputFile,
                fixture.patchFile,
                outputFile,
            )
        }
        assertFalse(outputFile.exists())
    }

    @Test
    fun outputMayNeverAliasInputOrPatch() {
        val fixture = writeFixture(base, validPatch())

        assertThrows(FirmwarePackageException.PathConflict::class.java) {
            FirmwarePackageEngine().apply(
                fixture.verified,
                fixture.inputFile,
                fixture.patchFile,
                fixture.inputFile,
            )
        }
    }

    private fun validPatch(): ByteArray =
        FirmwareTestFixtures.buildPatch(
            inputSize = base.size.toLong(),
            outputSize = expected.size.toLong(),
            instructions = listOf(
                FirmwareTestFixtures.Instruction.Copy(0, 4),
                FirmwareTestFixtures.Instruction.Data("XYZ".toByteArray()),
                FirmwareTestFixtures.Instruction.Copy(12, 4),
            ),
        )

    private fun writeFixture(
        inputBytes: ByteArray,
        patchBytes: ByteArray,
        outputDigest: Sha256Digest = FirmwareHashing.sha256(expected),
        segmentDigest: Sha256Digest = FirmwareHashing.sha256(expected),
    ): Fixture {
        val inputFile = temporaryFolder.newFile("input-${temporaryFolder.root.list().orEmpty().size}.bin")
        val patchFile = temporaryFolder.newFile("patch-${temporaryFolder.root.list().orEmpty().size}.brp")
        inputFile.writeBytes(inputBytes)
        patchFile.writeBytes(patchBytes)
        val keyPair = FirmwareTestFixtures.generateP256KeyPair()
        val manifest = FirmwareTestFixtures.manifestBytes(
            input = inputBytes,
            patch = patchBytes,
            output = expected,
            outputDigest = outputDigest,
            segmentDigest = segmentDigest,
        )
        return Fixture(
            inputFile,
            patchFile,
            FirmwareTestFixtures.verify(manifest, keyPair),
        )
    }

    private data class Fixture(
        val inputFile: File,
        val patchFile: File,
        val verified: VerifiedFirmwareManifest,
    )
}
