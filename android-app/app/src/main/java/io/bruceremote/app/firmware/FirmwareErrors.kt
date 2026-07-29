package io.bruceremote.app.firmware

import java.io.File

/**
 * Every expected package-validation failure has a stable type so callers never
 * need to branch on message text.
 */
sealed class FirmwarePackageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class ManifestTooLarge(
        val actualBytes: Long,
        val maximumBytes: Long,
    ) : FirmwarePackageException(
        "Manifest is $actualBytes bytes; maximum is $maximumBytes bytes.",
    )

    class InvalidUtf8(cause: Throwable) :
        FirmwarePackageException("Manifest is not strict UTF-8.", cause)

    class JsonSyntax(
        val byteOrCharacterOffset: Int,
        detail: String,
    ) : FirmwarePackageException(
        "Invalid manifest JSON at offset $byteOrCharacterOffset: $detail",
    )

    class ManifestSchema(
        val path: String,
        detail: String,
    ) : FirmwarePackageException(
        "Invalid manifest field $path: $detail",
    )

    class SignatureFormat(detail: String) :
        FirmwarePackageException("Invalid ECDSA signature encoding: $detail")

    class SignatureVerificationFailed :
        FirmwarePackageException("The manifest signature does not match any trusted key.")

    class SigningKeyMismatch(
        val manifestKeyId: String,
        val verifiedKeyId: String,
    ) : FirmwarePackageException(
        "Manifest key_id '$manifestKeyId' does not match verified key '$verifiedKeyId'.",
    )

    class InvalidTrustedKey(
        val keyId: String,
        detail: String,
        cause: Throwable? = null,
    ) : FirmwarePackageException(
        "Trusted key '$keyId' is invalid: $detail",
        cause,
    )

    class CryptoFailure(
        operation: String,
        cause: Throwable,
    ) : FirmwarePackageException("Cryptographic operation failed: $operation.", cause)

    class ManifestNotYetValid(
        val issuedAtEpochSeconds: Long,
        val nowEpochSeconds: Long,
    ) : FirmwarePackageException(
        "Manifest is not valid yet (issued_at=$issuedAtEpochSeconds, now=$nowEpochSeconds).",
    )

    class ManifestExpired(
        val expiresAtEpochSeconds: Long,
        val nowEpochSeconds: Long,
    ) : FirmwarePackageException(
        "Manifest expired (expires_at=$expiresAtEpochSeconds, now=$nowEpochSeconds).",
    )

    class CatalogRollback(
        val catalogVersion: Long,
        val minimumCatalogVersion: Long,
    ) : FirmwarePackageException(
        "Catalog version $catalogVersion is older than accepted version $minimumCatalogVersion.",
    )

    class UnknownDeviceProfile(val boardId: String) :
        FirmwarePackageException("Unknown target board '$boardId'.")

    class DeviceProfileMismatch(
        val boardId: String,
        detail: String,
    ) : FirmwarePackageException("Target '$boardId' does not match its device profile: $detail")

    class UnsupportedPatchAlgorithm(val wireName: String) :
        FirmwarePackageException("Unsupported patch algorithm '$wireName'.")

    class ArtifactMissing(
        val role: ArtifactRole,
        val file: File,
    ) : FirmwarePackageException("${role.label} does not exist or is not a regular file: $file")

    class ArtifactSizeMismatch(
        val role: ArtifactRole,
        val expectedBytes: Long,
        val actualBytes: Long,
    ) : FirmwarePackageException(
        "${role.label} size mismatch: expected $expectedBytes bytes, got $actualBytes bytes.",
    )

    class ArtifactDigestMismatch(
        val role: ArtifactRole,
        val expectedSha256: Sha256Digest,
        val actualSha256: Sha256Digest,
    ) : FirmwarePackageException(
        "${role.label} SHA-256 mismatch: expected $expectedSha256, got $actualSha256.",
    )

    class SegmentDigestMismatch(
        val segmentIndex: Int,
        val expectedSha256: Sha256Digest,
        val actualSha256: Sha256Digest,
    ) : FirmwarePackageException(
        "Flash segment $segmentIndex SHA-256 mismatch: expected $expectedSha256, got $actualSha256.",
    )

    class OutputAlreadyExists(val file: File) :
        FirmwarePackageException("Refusing to overwrite existing output: $file")

    class PathConflict(
        firstRole: String,
        secondRole: String,
        file: File,
    ) : FirmwarePackageException(
        "$firstRole and $secondRole resolve to the same path: $file",
    )

    class PatchFormat(
        val patchOffset: Long,
        detail: String,
        cause: Throwable? = null,
    ) : FirmwarePackageException(
        "Invalid BRP1 patch at byte $patchOffset: $detail",
        cause,
    )

    class PatchLimitExceeded(
        val limitName: String,
        val actual: Long,
        val maximum: Long,
    ) : FirmwarePackageException(
        "Patch exceeds $limitName: $actual > $maximum.",
    )

    class IoFailure(
        operation: String,
        val file: File,
        cause: Throwable,
    ) : FirmwarePackageException("I/O failed while $operation '$file'.", cause)

    class OutputCleanupFailed(
        val file: File,
        originalFailure: Throwable,
    ) : FirmwarePackageException(
        "Patch failed and the partial output could not be removed: $file",
        originalFailure,
    )
}

enum class ArtifactRole(val label: String) {
    INPUT_FIRMWARE("Input firmware"),
    PATCH_PAYLOAD("Patch payload"),
    OUTPUT_FIRMWARE("Output firmware"),
}
