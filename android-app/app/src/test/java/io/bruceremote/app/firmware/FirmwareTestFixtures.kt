package io.bruceremote.app.firmware

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

internal object FirmwareTestFixtures {
    const val KEY_ID = "release-2026"
    const val VALID_NOW = 1_800_000_000L

    sealed interface Instruction {
        data class Copy(val inputOffset: Long, val length: Int) : Instruction
        data class Data(val bytes: ByteArray) : Instruction
    }

    fun generateP256KeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    fun buildPatch(
        inputSize: Long,
        outputSize: Long,
        instructions: List<Instruction>,
        appendGarbage: ByteArray = byteArrayOf(),
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(byteArrayOf('B'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte()))
            output.writeByte(1)
            output.writeByte(0)
            output.writeShort(0)
            output.writeLong(inputSize)
            output.writeLong(outputSize)
            output.writeInt(instructions.size)
            instructions.forEach { instruction ->
                when (instruction) {
                    is Instruction.Copy -> {
                        output.writeByte(0)
                        output.writeLong(instruction.inputOffset)
                        output.writeInt(instruction.length)
                    }
                    is Instruction.Data -> {
                        output.writeByte(1)
                        output.writeInt(instruction.bytes.size)
                        output.write(instruction.bytes)
                    }
                }
            }
            output.write(appendGarbage)
        }
        bytes.toByteArray()
    }

    fun manifestBytes(
        input: ByteArray,
        patch: ByteArray,
        output: ByteArray,
        boardId: String = "m5stack-cplus2",
        chip: String = "esp32",
        keyId: String = KEY_ID,
        outputDigest: Sha256Digest = FirmwareHashing.sha256(output),
        segmentDigest: Sha256Digest = FirmwareHashing.sha256(output),
        issuedAt: Long = 1_700_000_000L,
        expiresAt: Long = 1_900_000_000L,
        catalogVersion: Long = 7L,
        patchFileName: String = "payload.brp",
        extraTopLevelField: String = "",
    ): ByteArray {
        val inputDigest = FirmwareHashing.sha256(input)
        val patchDigest = FirmwareHashing.sha256(patch)
        val flashSize = 8L * 1024L * 1024L
        return """
            {
              "schema":1,
              "package_id":"bruce-test-package",
              "catalog_version":$catalogVersion,
              "key_id":"$keyId",
              "issued_at":$issuedAt,
              "expires_at":$expiresAt,
              $extraTopLevelField
              "firmware":{
                "project":"bruce",
                "version":"test-1",
                "commit":"0123456789abcdef0123456789abcdef01234567",
                "source_url":"https://github.com/BruceDevices/firmware",
                "license":"AGPL-3.0"
              },
              "target":{
                "board_id":"$boardId",
                "chip":"$chip",
                "flash_size":$flashSize
              },
              "input":{"size":${input.size},"sha256":"$inputDigest"},
              "patch":{
                "algorithm":"brp1-chunk-v1",
                "file":"$patchFileName",
                "size":${patch.size},
                "sha256":"$patchDigest"
              },
              "output":{"size":${output.size},"sha256":"$outputDigest"},
              "flash_segments":[{
                "flash_offset":0,
                "source_offset":0,
                "size":${output.size},
                "sha256":"$segmentDigest"
              }],
              "agent":{"build_id":"agent-test","protocol_min":1,"protocol_max":1},
              "app":{"min_version_code":1}
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
    }

    fun sign(rawManifest: ByteArray, keyPair: KeyPair): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(rawManifest)
            sign()
        }

    fun verifier(
        keyPair: KeyPair,
        limits: FirmwareLimits = FirmwareLimits(),
        keyId: String = KEY_ID,
    ): FirmwareManifestVerifier {
        val ring = TrustedKeyRing.fromPublicKeys(
            mapOf(keyId to keyPair.public),
            limits,
        )
        return FirmwareManifestVerifier(ring, limits)
    }

    fun verify(
        rawManifest: ByteArray,
        keyPair: KeyPair,
        limits: FirmwareLimits = FirmwareLimits(),
        keyId: String = KEY_ID,
        minimumCatalogVersion: Long = 0,
    ): VerifiedFirmwareManifest =
        verifier(keyPair, limits, keyId).verify(
            rawManifest,
            sign(rawManifest, keyPair),
            ManifestVerificationPolicy(
                nowEpochSeconds = VALID_NOW,
                allowedClockSkewSeconds = 0,
                minimumCatalogVersion = minimumCatalogVersion,
            ),
        )
}
