package io.bruceremote.app.firmware

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECFieldFp
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

object FirmwareHashing {
    private const val BUFFER_SIZE = 64 * 1024

    fun sha256(bytes: ByteArray): Sha256Digest =
        Sha256Digest.fromBytes(MessageDigest.getInstance("SHA-256").digest(bytes))

    internal fun sha256(file: File, role: ArtifactRole): Sha256Digest {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
        } catch (error: IOException) {
            throw FirmwarePackageException.IoFailure(
                "hashing ${role.label.lowercase()}",
                file,
                error,
            )
        }
        return Sha256Digest.fromBytes(digest.digest())
    }

    internal fun matches(expected: Sha256Digest, actual: Sha256Digest): Boolean =
        MessageDigest.isEqual(expected.bytes(), actual.bytes())
}

class TrustedKeyRing private constructor(
    private val keys: List<TrustedKey>,
    private val limits: FirmwareLimits,
) {
    internal fun verifyExact(
        rawManifestBytes: ByteArray,
        signatureBytes: ByteArray,
    ): String {
        if (rawManifestBytes.size > limits.maximumManifestBytes) {
            throw FirmwarePackageException.ManifestTooLarge(
                rawManifestBytes.size.toLong(),
                limits.maximumManifestBytes.toLong(),
            )
        }
        validateCanonicalP256Signature(signatureBytes, limits.maximumSignatureBytes)

        for (trustedKey in keys) {
            val verified = try {
                Signature.getInstance(SIGNATURE_ALGORITHM).run {
                    initVerify(trustedKey.publicKey)
                    update(rawManifestBytes)
                    verify(signatureBytes)
                }
            } catch (error: Exception) {
                throw FirmwarePackageException.CryptoFailure(
                    "verifying manifest signature",
                    error,
                )
            }
            if (verified) return trustedKey.keyId
        }
        throw FirmwarePackageException.SignatureVerificationFailed()
    }

    companion object {
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private val P256_ORDER = BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16,
        )
        private val P256_PRIME = BigInteger(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
            16,
        )
        private val P256_A = BigInteger(
            "ffffffff00000001000000000000000000000000fffffffffffffffffffffffc",
            16,
        )
        private val P256_B = BigInteger(
            "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b",
            16,
        )
        private val P256_GENERATOR_X = BigInteger(
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
            16,
        )
        private val P256_GENERATOR_Y = BigInteger(
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
            16,
        )
        private val SAFE_KEY_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        fun fromPublicKeys(
            publicKeys: Map<String, PublicKey>,
            limits: FirmwareLimits = FirmwareLimits(),
        ): TrustedKeyRing {
            if (publicKeys.isEmpty()) {
                throw IllegalArgumentException("At least one trusted public key is required.")
            }
            if (publicKeys.size > limits.maximumTrustedKeys) {
                throw IllegalArgumentException(
                    "Trusted key count ${publicKeys.size} exceeds ${limits.maximumTrustedKeys}.",
                )
            }
            val trusted = publicKeys.entries
                .sortedBy { it.key }
                .map { (keyId, publicKey) ->
                    validateKeyId(keyId)
                    TrustedKey(keyId, validateP256Key(keyId, publicKey))
                }
            return TrustedKeyRing(trusted, limits)
        }

        fun fromX509SubjectPublicKeys(
            encodedKeys: Map<String, ByteArray>,
            limits: FirmwareLimits = FirmwareLimits(),
        ): TrustedKeyRing {
            val keyFactory = try {
                KeyFactory.getInstance("EC")
            } catch (error: Exception) {
                throw FirmwarePackageException.CryptoFailure(
                    "initializing EC key factory",
                    error,
                )
            }
            val decoded = encodedKeys.mapValues { (keyId, encoded) ->
                try {
                    keyFactory.generatePublic(X509EncodedKeySpec(encoded.copyOf()))
                } catch (error: Exception) {
                    throw FirmwarePackageException.InvalidTrustedKey(
                        keyId,
                        "not a valid X.509 SubjectPublicKeyInfo EC key",
                        error,
                    )
                }
            }
            return fromPublicKeys(decoded, limits)
        }

        private fun validateKeyId(keyId: String) {
            if (!SAFE_KEY_ID.matches(keyId)) {
                throw FirmwarePackageException.InvalidTrustedKey(
                    keyId,
                    "key id must be 1-64 safe ASCII identifier characters",
                )
            }
        }

        private fun validateP256Key(keyId: String, publicKey: PublicKey): ECPublicKey {
            val ecKey = publicKey as? ECPublicKey
                ?: throw FirmwarePackageException.InvalidTrustedKey(
                    keyId,
                    "key algorithm must be EC",
                )
            val parameters = ecKey.params
                ?: throw FirmwarePackageException.InvalidTrustedKey(
                    keyId,
                    "EC parameters are missing",
                )
            val primeField = parameters.curve.field as? ECFieldFp
            if (primeField?.p != P256_PRIME ||
                parameters.curve.a != P256_A ||
                parameters.curve.b != P256_B ||
                parameters.generator.affineX != P256_GENERATOR_X ||
                parameters.generator.affineY != P256_GENERATOR_Y ||
                parameters.order != P256_ORDER ||
                parameters.cofactor != 1
            ) {
                throw FirmwarePackageException.InvalidTrustedKey(
                    keyId,
                    "key must use the NIST P-256/secp256r1 curve",
                )
            }
            return ecKey
        }

        /**
         * JCA expects DER ECDSA signatures. Validate the tiny ASN.1 structure
         * ourselves first so providers cannot disagree about non-canonical
         * encodings.
         */
        private fun validateCanonicalP256Signature(signature: ByteArray, maximumBytes: Int) {
            if (signature.size !in 8..maximumBytes) {
                throw FirmwarePackageException.SignatureFormat(
                    "length ${signature.size} is outside 8..$maximumBytes",
                )
            }
            var index = 0

            fun readByte(): Int {
                if (index >= signature.size) {
                    throw FirmwarePackageException.SignatureFormat("unexpected end of data")
                }
                return signature[index++].toInt() and 0xff
            }

            if (readByte() != 0x30) {
                throw FirmwarePackageException.SignatureFormat("expected ASN.1 SEQUENCE")
            }
            val sequenceLength = readByte()
            if (sequenceLength and 0x80 != 0) {
                throw FirmwarePackageException.SignatureFormat(
                    "long-form ASN.1 lengths are not canonical for P-256",
                )
            }
            if (sequenceLength != signature.size - index) {
                throw FirmwarePackageException.SignatureFormat("SEQUENCE length mismatch")
            }

            fun readInteger(name: String): BigInteger {
                if (readByte() != 0x02) {
                    throw FirmwarePackageException.SignatureFormat("expected INTEGER $name")
                }
                val length = readByte()
                if (length !in 1..33 || index + length > signature.size) {
                    throw FirmwarePackageException.SignatureFormat(
                        "INTEGER $name has invalid length $length",
                    )
                }
                val first = signature[index].toInt() and 0xff
                if (first and 0x80 != 0) {
                    throw FirmwarePackageException.SignatureFormat("INTEGER $name is negative")
                }
                if (length > 1 &&
                    first == 0 &&
                    (signature[index + 1].toInt() and 0x80) == 0
                ) {
                    throw FirmwarePackageException.SignatureFormat(
                        "INTEGER $name has redundant leading zero",
                    )
                }
                val encoded = signature.copyOfRange(index, index + length)
                index += length
                val value = BigInteger(1, encoded)
                if (value.signum() <= 0 || value >= P256_ORDER) {
                    throw FirmwarePackageException.SignatureFormat(
                        "INTEGER $name is outside the P-256 scalar range",
                    )
                }
                return value
            }

            readInteger("r")
            readInteger("s")
            if (index != signature.size) {
                throw FirmwarePackageException.SignatureFormat("trailing ASN.1 data")
            }
        }
    }

    private data class TrustedKey(
        val keyId: String,
        val publicKey: ECPublicKey,
    )
}

class FirmwareManifestVerifier(
    private val trustedKeyRing: TrustedKeyRing,
    private val limits: FirmwareLimits = FirmwareLimits(),
) {
    private val parser = FirmwareManifestParser(limits)

    /**
     * Signature verification intentionally happens before JSON parsing. The
     * signing key is selected by trying the small pinned key ring, then the
     * authenticated key_id field is required to name that exact key.
     */
    fun verify(
        rawManifestBytes: ByteArray,
        signatureBytes: ByteArray,
        policy: ManifestVerificationPolicy = ManifestVerificationPolicy(),
    ): VerifiedFirmwareManifest {
        val verifiedKeyId = trustedKeyRing.verifyExact(rawManifestBytes, signatureBytes)
        val manifest = parser.parse(rawManifestBytes)
        if (manifest.keyId != verifiedKeyId) {
            throw FirmwarePackageException.SigningKeyMismatch(
                manifest.keyId,
                verifiedKeyId,
            )
        }

        val latestAllowedIssue = saturatingAdd(
            policy.nowEpochSeconds,
            policy.allowedClockSkewSeconds,
        )
        if (manifest.issuedAtEpochSeconds > latestAllowedIssue) {
            throw FirmwarePackageException.ManifestNotYetValid(
                manifest.issuedAtEpochSeconds,
                policy.nowEpochSeconds,
            )
        }
        val earliestAllowedExpiry =
            (policy.nowEpochSeconds - policy.allowedClockSkewSeconds).coerceAtLeast(0L)
        if (manifest.expiresAtEpochSeconds < earliestAllowedExpiry) {
            throw FirmwarePackageException.ManifestExpired(
                manifest.expiresAtEpochSeconds,
                policy.nowEpochSeconds,
            )
        }
        if (manifest.catalogVersion < policy.minimumCatalogVersion) {
            throw FirmwarePackageException.CatalogRollback(
                manifest.catalogVersion,
                policy.minimumCatalogVersion,
            )
        }

        return VerifiedFirmwareManifest(
            manifest = manifest,
            signingKeyId = verifiedKeyId,
            rawManifestSha256 = FirmwareHashing.sha256(rawManifestBytes),
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}
