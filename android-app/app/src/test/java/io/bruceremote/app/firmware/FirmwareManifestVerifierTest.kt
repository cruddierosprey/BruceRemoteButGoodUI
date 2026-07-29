package io.bruceremote.app.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirmwareManifestVerifierTest {
    private val input = "base firmware".toByteArray()
    private val output = "patched firmware".toByteArray()
    private val patch = FirmwareTestFixtures.buildPatch(
        inputSize = input.size.toLong(),
        outputSize = output.size.toLong(),
        instructions = listOf(FirmwareTestFixtures.Instruction.Data(output)),
    )

    @Test
    fun verifiesExactRawBytesBeforeParsing() {
        val keyPair = FirmwareTestFixtures.generateP256KeyPair()
        val raw = FirmwareTestFixtures.manifestBytes(input, patch, output)
        val verified = FirmwareTestFixtures.verify(raw, keyPair)

        assertEquals(FirmwareTestFixtures.KEY_ID, verified.signingKeyId)
        assertEquals("m5stack-cplus2", verified.manifest.target.boardId)
        assertEquals(EspChip.ESP32, verified.manifest.target.chip)

        val signature = FirmwareTestFixtures.sign(raw, keyPair)
        val changed = raw + '\n'.code.toByte()
        assertThrows(FirmwarePackageException.SignatureVerificationFailed::class.java) {
            FirmwareTestFixtures.verifier(keyPair).verify(
                changed,
                signature,
                ManifestVerificationPolicy(
                    nowEpochSeconds = FirmwareTestFixtures.VALID_NOW,
                    allowedClockSkewSeconds = 0,
                ),
            )
        }
    }

    @Test
    fun authenticatedKeyIdMustMatchTheKeyThatVerified() {
        val keyPair = FirmwareTestFixtures.generateP256KeyPair()
        val raw = FirmwareTestFixtures.manifestBytes(
            input,
            patch,
            output,
            keyId = "different-key",
        )
        val signature = FirmwareTestFixtures.sign(raw, keyPair)

        assertThrows(FirmwarePackageException.SigningKeyMismatch::class.java) {
            FirmwareTestFixtures.verifier(keyPair).verify(
                raw,
                signature,
                ManifestVerificationPolicy(
                    nowEpochSeconds = FirmwareTestFixtures.VALID_NOW,
                    allowedClockSkewSeconds = 0,
                ),
            )
        }
    }

    @Test
    fun malformedDerIsRejectedBeforeProviderVerification() {
        val keyPair = FirmwareTestFixtures.generateP256KeyPair()
        val raw = FirmwareTestFixtures.manifestBytes(input, patch, output)

        assertThrows(FirmwarePackageException.SignatureFormat::class.java) {
            FirmwareTestFixtures.verifier(keyPair).verify(
                raw,
                byteArrayOf(0x30, 0x00),
                ManifestVerificationPolicy(nowEpochSeconds = FirmwareTestFixtures.VALID_NOW),
            )
        }
    }

    @Test
    fun signedDuplicateAndUnknownFieldsAreRejected() {
        val keyPair = FirmwareTestFixtures.generateP256KeyPair()
        val normal = FirmwareTestFixtures.manifestBytes(input, patch, output)
            .toString(Charsets.UTF_8)
        val duplicate = normal.replace(
            "\"schema\":1,",
            "\"schema\":1,\"schema\":1,",
        ).toByteArray()
        assertThrows(FirmwarePackageException.JsonSyntax::class.java) {
            FirmwareTestFixtures.verify(duplicate, keyPair)
        }

        val unknown = FirmwareTestFixtures.manifestBytes(
            input,
            patch,
            output,
            extraTopLevelField = "\"unexpected\":true,",
        )
        assertThrows(FirmwarePackageException.ManifestSchema::class.java) {
            FirmwareTestFixtures.verify(unknown, keyPair)
        }
    }

    @Test
    fun timeAndRollbackPoliciesAreEnforced() {
        val keyPair = FirmwareTestFixtures.generateP256KeyPair()
        val expired = FirmwareTestFixtures.manifestBytes(
            input,
            patch,
            output,
            issuedAt = 1_600_000_000L,
            expiresAt = 1_700_000_000L,
        )
        assertThrows(FirmwarePackageException.ManifestExpired::class.java) {
            FirmwareTestFixtures.verify(expired, keyPair)
        }

        val current = FirmwareTestFixtures.manifestBytes(input, patch, output)
        assertThrows(FirmwarePackageException.CatalogRollback::class.java) {
            FirmwareTestFixtures.verify(
                current,
                keyPair,
                minimumCatalogVersion = 8,
            )
        }
    }

    @Test
    fun onlySupportedExactDeviceProfilesAreAccepted() {
        val keyPair = FirmwareTestFixtures.generateP256KeyPair()
        val cardputer = FirmwareTestFixtures.manifestBytes(
            input,
            patch,
            output,
            boardId = "cardputer-adv",
            chip = "esp32s3",
        )
        val verified = FirmwareTestFixtures.verify(cardputer, keyPair)
        assertEquals(
            DeviceProfiles.CARDPUTER_ADV,
            DeviceProfiles.validate(verified.manifest.target),
        )

        val wrongChip = FirmwareTestFixtures.manifestBytes(
            input,
            patch,
            output,
            boardId = "cardputer-adv",
            chip = "esp32",
        )
        assertThrows(FirmwarePackageException.DeviceProfileMismatch::class.java) {
            FirmwareTestFixtures.verify(wrongChip, keyPair)
        }
    }

    @Test
    fun patchFilenameCannotEscapePackage() {
        val keyPair = FirmwareTestFixtures.generateP256KeyPair()
        val raw = FirmwareTestFixtures.manifestBytes(
            input,
            patch,
            output,
            patchFileName = "../payload.brp",
        )

        assertThrows(FirmwarePackageException.ManifestSchema::class.java) {
            FirmwareTestFixtures.verify(raw, keyPair)
        }
    }
}
