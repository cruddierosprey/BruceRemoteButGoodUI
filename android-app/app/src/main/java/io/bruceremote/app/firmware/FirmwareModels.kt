package io.bruceremote.app.firmware

import java.util.Locale

data class FirmwareLimits(
    val maximumManifestBytes: Int = 64 * 1024,
    val maximumSignatureBytes: Int = 80,
    val maximumArtifactBytes: Long = 16L * 1024L * 1024L,
    val maximumPatchBytes: Long = 16L * 1024L * 1024L,
    val maximumInstructions: Int = 65_536,
    val maximumChunkBytes: Int = 1024 * 1024,
    val maximumFlashSegments: Int = 32,
    val maximumTrustedKeys: Int = 8,
) {
    init {
        require(maximumManifestBytes in 1..(1024 * 1024))
        require(maximumSignatureBytes in 8..1024)
        require(maximumArtifactBytes in 1..(256L * 1024L * 1024L))
        require(maximumPatchBytes in 1..(256L * 1024L * 1024L))
        require(maximumInstructions in 1..1_000_000)
        require(maximumChunkBytes in 1..(16 * 1024 * 1024))
        require(maximumFlashSegments in 1..1024)
        require(maximumTrustedKeys in 1..64)
    }
}

@JvmInline
value class Sha256Digest private constructor(val hex: String) {
    override fun toString(): String = hex

    internal fun bytes(): ByteArray =
        ByteArray(BYTE_LENGTH) { index ->
            val start = index * 2
            hex.substring(start, start + 2).toInt(16).toByte()
        }

    companion object {
        const val BYTE_LENGTH = 32
        const val HEX_LENGTH = BYTE_LENGTH * 2

        fun parse(value: String): Sha256Digest {
            require(value.length == HEX_LENGTH) {
                "SHA-256 must contain exactly $HEX_LENGTH lowercase hexadecimal characters."
            }
            require(value.all { it in '0'..'9' || it in 'a'..'f' }) {
                "SHA-256 must use lowercase hexadecimal characters only."
            }
            return Sha256Digest(value)
        }

        internal fun fromBytes(bytes: ByteArray): Sha256Digest {
            require(bytes.size == BYTE_LENGTH)
            val hex = buildString(HEX_LENGTH) {
                bytes.forEach { byte ->
                    append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff))
                }
            }
            return Sha256Digest(hex)
        }
    }
}

enum class EspChip(val wireName: String) {
    ESP32("esp32"),
    ESP32_S3("esp32s3");

    companion object {
        internal fun fromWireName(value: String): EspChip? =
            entries.firstOrNull { it.wireName == value }
    }
}

enum class PatchAlgorithm(val wireName: String) {
    BRP1_CHUNK_V1("brp1-chunk-v1");

    companion object {
        internal fun fromWireName(value: String): PatchAlgorithm? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class FirmwareOrigin(
    val project: String,
    val version: String,
    val commit: String,
    val sourceUrl: String,
    val license: String,
)

data class FirmwareTarget(
    val boardId: String,
    val chip: EspChip,
    val flashSizeBytes: Long,
)

data class ArtifactDescriptor(
    val sizeBytes: Long,
    val sha256: Sha256Digest,
)

data class PatchDescriptor(
    val algorithm: PatchAlgorithm,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: Sha256Digest,
)

data class FlashSegment(
    val flashOffset: Long,
    val sourceOffset: Long,
    val sizeBytes: Long,
    val sha256: Sha256Digest,
)

data class AgentDescriptor(
    val buildId: String,
    val protocolMinimum: Int,
    val protocolMaximum: Int,
)

data class AppRequirement(
    val minimumVersionCode: Long,
)

data class FirmwareManifest(
    val schemaVersion: Int,
    val packageId: String,
    val catalogVersion: Long,
    val keyId: String,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val firmware: FirmwareOrigin,
    val target: FirmwareTarget,
    val input: ArtifactDescriptor,
    val patch: PatchDescriptor,
    val output: ArtifactDescriptor,
    val flashSegments: List<FlashSegment>,
    val agent: AgentDescriptor,
    val app: AppRequirement,
)

/**
 * Can only be produced by [FirmwareManifestVerifier] after signature, schema,
 * time and rollback checks pass.
 */
class VerifiedFirmwareManifest internal constructor(
    val manifest: FirmwareManifest,
    val signingKeyId: String,
    val rawManifestSha256: Sha256Digest,
)

data class ManifestVerificationPolicy(
    val nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    val allowedClockSkewSeconds: Long = 300L,
    val minimumCatalogVersion: Long = 0L,
) {
    init {
        require(nowEpochSeconds >= 0)
        require(allowedClockSkewSeconds in 0..86_400L)
        require(minimumCatalogVersion >= 0)
    }
}

data class AppliedFirmware(
    val outputFile: java.io.File,
    val sha256: Sha256Digest,
    val profile: DeviceProfile,
    val flashSegments: List<FlashSegment>,
)
