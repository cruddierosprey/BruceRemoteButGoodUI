package io.bruceremote.app.firmware

import java.net.URI

internal class FirmwareManifestParser(
    private val limits: FirmwareLimits,
) {
    fun parse(rawManifestBytes: ByteArray): FirmwareManifest {
        val root = StrictJson.parseObject(rawManifestBytes, limits.maximumManifestBytes)
        root.requireExactKeys(
            "$",
            setOf(
                "schema",
                "package_id",
                "catalog_version",
                "key_id",
                "issued_at",
                "expires_at",
                "firmware",
                "target",
                "input",
                "patch",
                "output",
                "flash_segments",
                "agent",
                "app",
            ),
        )

        val manifest = FirmwareManifest(
            schemaVersion = root.requiredInt("$.schema", "schema", 1, 1),
            packageId = root.requiredString("$.package_id", "package_id")
                .requireIdentifier("$.package_id", maximumLength = 128),
            catalogVersion = root.requiredLong(
                "$.catalog_version",
                "catalog_version",
                minimum = 1,
            ),
            keyId = root.requiredString("$.key_id", "key_id")
                .requireIdentifier("$.key_id", maximumLength = 64),
            issuedAtEpochSeconds = root.requiredLong(
                "$.issued_at",
                "issued_at",
                minimum = 1,
                maximum = MAXIMUM_EPOCH_SECONDS,
            ),
            expiresAtEpochSeconds = root.requiredLong(
                "$.expires_at",
                "expires_at",
                minimum = 1,
                maximum = MAXIMUM_EPOCH_SECONDS,
            ),
            firmware = parseFirmware(root.requiredObject("$.firmware", "firmware")),
            target = parseTarget(root.requiredObject("$.target", "target")),
            input = parseArtifact(root.requiredObject("$.input", "input"), "$.input"),
            patch = parsePatch(root.requiredObject("$.patch", "patch")),
            output = parseArtifact(root.requiredObject("$.output", "output"), "$.output"),
            flashSegments = parseSegments(root.requiredArray("$.flash_segments", "flash_segments")),
            agent = parseAgent(root.requiredObject("$.agent", "agent")),
            app = parseApp(root.requiredObject("$.app", "app")),
        )
        validateRelationships(manifest)
        return manifest
    }

    private fun parseFirmware(value: JsonObject): FirmwareOrigin {
        value.requireExactKeys(
            "$.firmware",
            setOf("project", "version", "commit", "source_url", "license"),
        )
        val project = value.requiredString("$.firmware.project", "project")
        if (project != "bruce") {
            schema("$.firmware.project", "only the 'bruce' firmware project is accepted")
        }
        val version = value.requiredString("$.firmware.version", "version")
            .requirePrintable("$.firmware.version", 1, 128)
        val commit = value.requiredString("$.firmware.commit", "commit")
        if (commit.length !in 7..64 || !commit.all { it in '0'..'9' || it in 'a'..'f' }) {
            schema("$.firmware.commit", "must be a 7-64 character lowercase hexadecimal revision")
        }
        val sourceUrl = value.requiredString("$.firmware.source_url", "source_url")
        validateHttpsUrl("$.firmware.source_url", sourceUrl)
        val license = value.requiredString("$.firmware.license", "license")
            .requirePrintable("$.firmware.license", 1, 64)
        if (license != "AGPL-3.0" && license != "AGPL-3.0-or-later") {
            schema(
                "$.firmware.license",
                "Bruce packages must declare AGPL-3.0 or AGPL-3.0-or-later",
            )
        }
        return FirmwareOrigin(
            project = project,
            version = version,
            commit = commit,
            sourceUrl = sourceUrl,
            license = license,
        )
    }

    private fun parseTarget(value: JsonObject): FirmwareTarget {
        value.requireExactKeys("$.target", setOf("board_id", "chip", "flash_size"))
        val boardId = value.requiredString("$.target.board_id", "board_id")
            .requireIdentifier("$.target.board_id", maximumLength = 64)
        val chipWireName = value.requiredString("$.target.chip", "chip")
        val chip = EspChip.fromWireName(chipWireName)
            ?: schema("$.target.chip", "unsupported chip '$chipWireName'")
        val target = FirmwareTarget(
            boardId = boardId,
            chip = chip,
            flashSizeBytes = value.requiredLong(
                "$.target.flash_size",
                "flash_size",
                minimum = 1,
                maximum = limits.maximumArtifactBytes,
            ),
        )
        DeviceProfiles.validate(target)
        return target
    }

    private fun parseArtifact(value: JsonObject, path: String): ArtifactDescriptor {
        value.requireExactKeys(path, setOf("size", "sha256"))
        return ArtifactDescriptor(
            sizeBytes = value.requiredLong(
                "$path.size",
                "size",
                minimum = 1,
                maximum = limits.maximumArtifactBytes,
            ),
            sha256 = value.requiredDigest("$path.sha256", "sha256"),
        )
    }

    private fun parsePatch(value: JsonObject): PatchDescriptor {
        value.requireExactKeys("$.patch", setOf("algorithm", "file", "size", "sha256"))
        val wireName = value.requiredString("$.patch.algorithm", "algorithm")
        val algorithm = PatchAlgorithm.fromWireName(wireName)
            ?: throw FirmwarePackageException.UnsupportedPatchAlgorithm(wireName)
        val fileName = value.requiredString("$.patch.file", "file")
        if (!SAFE_FILE_NAME.matches(fileName) || fileName == "." || fileName == "..") {
            schema(
                "$.patch.file",
                "must be a simple ASCII filename without directories",
            )
        }
        return PatchDescriptor(
            algorithm = algorithm,
            fileName = fileName,
            sizeBytes = value.requiredLong(
                "$.patch.size",
                "size",
                minimum = 1,
                maximum = limits.maximumPatchBytes,
            ),
            sha256 = value.requiredDigest("$.patch.sha256", "sha256"),
        )
    }

    private fun parseSegments(value: JsonArray): List<FlashSegment> {
        if (value.values.isEmpty()) {
            schema("$.flash_segments", "must contain at least one segment")
        }
        if (value.values.size > limits.maximumFlashSegments) {
            throw FirmwarePackageException.PatchLimitExceeded(
                "flash segment count",
                value.values.size.toLong(),
                limits.maximumFlashSegments.toLong(),
            )
        }
        return value.values.mapIndexed { index, entry ->
            val path = "$.flash_segments[$index]"
            val objectValue = entry as? JsonObject
                ?: schema(path, "must be an object")
            objectValue.requireExactKeys(
                path,
                setOf("flash_offset", "source_offset", "size", "sha256"),
            )
            FlashSegment(
                flashOffset = objectValue.requiredLong(
                    "$path.flash_offset",
                    "flash_offset",
                    minimum = 0,
                ),
                sourceOffset = objectValue.requiredLong(
                    "$path.source_offset",
                    "source_offset",
                    minimum = 0,
                ),
                sizeBytes = objectValue.requiredLong(
                    "$path.size",
                    "size",
                    minimum = 1,
                    maximum = limits.maximumArtifactBytes,
                ),
                sha256 = objectValue.requiredDigest("$path.sha256", "sha256"),
            )
        }
    }

    private fun parseAgent(value: JsonObject): AgentDescriptor {
        value.requireExactKeys(
            "$.agent",
            setOf("build_id", "protocol_min", "protocol_max"),
        )
        return AgentDescriptor(
            buildId = value.requiredString("$.agent.build_id", "build_id")
                .requireIdentifier("$.agent.build_id", maximumLength = 128),
            protocolMinimum = value.requiredInt(
                "$.agent.protocol_min",
                "protocol_min",
                minimum = 1,
                maximum = 65_535,
            ),
            protocolMaximum = value.requiredInt(
                "$.agent.protocol_max",
                "protocol_max",
                minimum = 1,
                maximum = 65_535,
            ),
        )
    }

    private fun parseApp(value: JsonObject): AppRequirement {
        value.requireExactKeys("$.app", setOf("min_version_code"))
        return AppRequirement(
            minimumVersionCode = value.requiredLong(
                "$.app.min_version_code",
                "min_version_code",
                minimum = 1,
                maximum = Int.MAX_VALUE.toLong(),
            ),
        )
    }

    private fun validateRelationships(manifest: FirmwareManifest) {
        if (manifest.expiresAtEpochSeconds <= manifest.issuedAtEpochSeconds) {
            schema("$.expires_at", "must be later than issued_at")
        }
        if (manifest.agent.protocolMaximum < manifest.agent.protocolMinimum) {
            schema("$.agent.protocol_max", "must be greater than or equal to protocol_min")
        }

        var expectedSourceOffset = 0L
        var previousFlashEnd = 0L
        manifest.flashSegments.forEachIndexed { index, segment ->
            val path = "$.flash_segments[$index]"
            if (segment.sourceOffset != expectedSourceOffset) {
                schema(
                    "$path.source_offset",
                    "segments must cover output contiguously; expected $expectedSourceOffset",
                )
            }
            if (segment.flashOffset % FLASH_ADDRESS_ALIGNMENT != 0L) {
                schema(
                    "$path.flash_offset",
                    "must be aligned to $FLASH_ADDRESS_ALIGNMENT bytes",
                )
            }
            if (index > 0 && segment.flashOffset < previousFlashEnd) {
                schema("$path.flash_offset", "flash segments must be sorted and non-overlapping")
            }
            if (segment.sizeBytes > manifest.output.sizeBytes - segment.sourceOffset) {
                schema("$path.size", "source range extends past output artifact")
            }
            if (segment.sizeBytes > manifest.target.flashSizeBytes - segment.flashOffset) {
                schema("$path.size", "flash range extends past target flash")
            }
            expectedSourceOffset = checkedAdd(
                segment.sourceOffset,
                segment.sizeBytes,
                "$path.size",
            )
            previousFlashEnd = checkedAdd(
                segment.flashOffset,
                segment.sizeBytes,
                "$path.size",
            )
        }
        if (expectedSourceOffset != manifest.output.sizeBytes) {
            schema(
                "$.flash_segments",
                "segments cover $expectedSourceOffset bytes but output has ${manifest.output.sizeBytes}",
            )
        }
    }

    private fun checkedAdd(left: Long, right: Long, path: String): Long {
        if (right > Long.MAX_VALUE - left) {
            schema(path, "range overflows signed 64-bit integer")
        }
        return left + right
    }

    private fun validateHttpsUrl(path: String, value: String) {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            schema(path, "must be a valid HTTPS URL")
        }
        if (uri.scheme != "https" ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null ||
            uri.fragment != null
        ) {
            schema(path, "must be an HTTPS URL with a host and no user-info or fragment")
        }
    }

    private fun JsonObject.requireExactKeys(path: String, required: Set<String>) {
        val missing = required - fields.keys
        if (missing.isNotEmpty()) {
            schema(path, "missing field(s): ${missing.sorted().joinToString()}")
        }
        val unknown = fields.keys - required
        if (unknown.isNotEmpty()) {
            schema(path, "unknown field(s): ${unknown.sorted().joinToString()}")
        }
    }

    private fun JsonObject.requiredString(path: String, key: String): String =
        (fields[key] as? JsonString)?.value
            ?: schema(path, "must be a string")

    private fun JsonObject.requiredObject(path: String, key: String): JsonObject =
        fields[key] as? JsonObject
            ?: schema(path, "must be an object")

    private fun JsonObject.requiredArray(path: String, key: String): JsonArray =
        fields[key] as? JsonArray
            ?: schema(path, "must be an array")

    private fun JsonObject.requiredLong(
        path: String,
        key: String,
        minimum: Long,
        maximum: Long = Long.MAX_VALUE,
    ): Long {
        val value = (fields[key] as? JsonInteger)?.value
            ?: schema(path, "must be an integer")
        if (value !in minimum..maximum) {
            schema(path, "must be in range $minimum..$maximum")
        }
        return value
    }

    private fun JsonObject.requiredInt(
        path: String,
        key: String,
        minimum: Int,
        maximum: Int,
    ): Int = requiredLong(path, key, minimum.toLong(), maximum.toLong()).toInt()

    private fun JsonObject.requiredDigest(path: String, key: String): Sha256Digest {
        val value = requiredString(path, key)
        return try {
            Sha256Digest.parse(value)
        } catch (error: IllegalArgumentException) {
            schema(path, error.message ?: "invalid SHA-256")
        }
    }

    private fun String.requireIdentifier(path: String, maximumLength: Int): String {
        if (length !in 1..maximumLength ||
            firstOrNull()?.let { !(it.isAsciiLetterOrDigit()) } != false ||
            any { !(it.isAsciiLetterOrDigit() || it == '.' || it == '_' || it == '-') }
        ) {
            schema(
                path,
                "must start with an ASCII letter/digit and contain only letters, digits, '.', '_' or '-'",
            )
        }
        return this
    }

    private fun String.requirePrintable(
        path: String,
        minimumLength: Int,
        maximumLength: Int,
    ): String {
        if (length !in minimumLength..maximumLength ||
            any { it.code < 0x20 || it == '\u007f' }
        ) {
            schema(path, "must contain $minimumLength-$maximumLength printable characters")
        }
        return this
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun schema(path: String, detail: String): Nothing =
        throw FirmwarePackageException.ManifestSchema(path, detail)

    private companion object {
        const val MAXIMUM_EPOCH_SECONDS = 253_402_300_799L
        const val FLASH_ADDRESS_ALIGNMENT = 4L
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
