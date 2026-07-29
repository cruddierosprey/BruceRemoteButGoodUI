package io.bruceremote.app

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.bruceremote.app.firmware.AppliedFirmware
import io.bruceremote.app.firmware.DeviceProfile
import io.bruceremote.app.firmware.DeviceProfiles
import io.bruceremote.app.firmware.FirmwareLimits
import io.bruceremote.app.firmware.FirmwareManifestVerifier
import io.bruceremote.app.firmware.FirmwarePackageEngine
import io.bruceremote.app.firmware.FlasherUsbDevice
import io.bruceremote.app.firmware.GitHubReleaseClient
import io.bruceremote.app.firmware.ManifestVerificationPolicy
import io.bruceremote.app.firmware.TrustedKeyRing
import io.bruceremote.app.firmware.UsbSerialFlasherTransport
import io.bruceremote.flasher.DeviceInfo
import io.bruceremote.flasher.EspChip as NativeEspChip
import io.bruceremote.flasher.EspSerialFlasher
import io.bruceremote.flasher.FlashOptions
import io.bruceremote.flasher.FlashPhase
import io.bruceremote.flasher.FlashProgress
import io.bruceremote.flasher.FlashProgressListener
import io.bruceremote.flasher.FlashSegment as NativeFlashSegment
import io.bruceremote.flasher.FlasherCancellationToken
import io.bruceremote.flasher.FlasherError
import io.bruceremote.flasher.FlasherException
import io.bruceremote.flasher.IdentifyOptions
import io.bruceremote.flasher.ResetStrategy as NativeResetStrategy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FirmwareActivity : AppCompatActivity() {
    private lateinit var profileSpinner: Spinner
    private lateinit var deviceSpinner: Spinner
    private lateinit var scanButton: Button
    private lateinit var bootInstructionsText: TextView
    private lateinit var mergedFileText: TextView
    private lateinit var baseFileText: TextView
    private lateinit var manifestFileText: TextView
    private lateinit var signatureFileText: TextView
    private lateinit var patchFileText: TextView
    private lateinit var patchStatusText: TextView
    private lateinit var readyFirmwareText: TextView
    private lateinit var detectedTargetText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var statusText: TextView
    private lateinit var applyPatchButton: Button
    private lateinit var identifyButton: Button
    private lateinit var flashButton: Button
    private lateinit var cancelButton: Button
    private lateinit var backButton: Button
    private lateinit var checkGitHubButton: Button
    private lateinit var downloadFirmwareButton: Button
    private lateinit var githubProgressBar: ProgressBar
    private lateinit var githubStatusText: TextView

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val limits = FirmwareLimits()
    private val profiles = listOf(
        DeviceProfiles.M5STACK_CPLUS2,
        DeviceProfiles.CARDPUTER_ADV,
    )
    private val usbManager by lazy {
        applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    }
    private val selectionButtons = mutableListOf<View>()

    private var devices: List<FlasherUsbDevice> = emptyList()
    private var mergedDocument: SelectedDocument? = null
    private var baseDocument: SelectedDocument? = null
    private var manifestDocument: SelectedDocument? = null
    private var signatureDocument: SelectedDocument? = null
    private var patchDocument: SelectedDocument? = null
    private var readyFirmware: ReadyFirmware? = null
    private var identifiedTarget: IdentifiedTarget? = null
    private var pendingPermission: PendingPermission? = null
    private var runningOperation: RunningOperation? = null
    private var receiverRegistered = false
    private var latestGitHubRelease: GitHubReleaseClient.Release? = null
    private var downloadedFirmwareFile: java.io.File? = null

    @Volatile
    private var cancellationToken: FlasherCancellationToken? = null

    @Volatile
    private var activeTransport: UsbSerialFlasherTransport? = null

    private val mergedPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                acceptDocument(it, setOf(".bin")) { document ->
                    mergedDocument = document
                    mergedFileText.text = document.displayText()
                    replaceReadyFirmware(ReadyFirmware.ImportedMerged(document))
                    patchStatusText.text = getString(R.string.patch_waiting)
                    showStatus(
                        "Merged image selected. Identify the target before flashing offset 0.",
                        StatusTone.SUCCESS,
                    )
                }
            }
        }

    private val basePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                acceptDocument(it, setOf(".bin")) { document ->
                    baseDocument = document
                    baseFileText.text = document.displayText()
                    patchInputsChanged()
                }
            }
        }

    private val manifestPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                acceptDocument(it, setOf(".json")) { document ->
                    manifestDocument = document
                    manifestFileText.text = document.displayText()
                    patchInputsChanged()
                }
            }
        }

    private val signaturePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                acceptDocument(it, setOf(".der", ".sig")) { document ->
                    signatureDocument = document
                    signatureFileText.text = document.displayText()
                    patchInputsChanged()
                }
            }
        }

    private val patchPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                acceptDocument(it, setOf(".brp")) { document ->
                    patchDocument = document
                    patchFileText.text = document.displayText()
                    patchInputsChanged()
                }
            }
        }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_FLASH_USB_PERMISSION -> handlePermissionResult(intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scanDevices()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detachedId = intent.usbDeviceExtra()?.deviceId
                    if (detachedId != null &&
                        (activeTransport != null || pendingPermission?.deviceId == detachedId)
                    ) {
                        cancellationToken?.cancel()
                        pendingPermission = null
                        showStatus(
                            "The active USB device detached. The operation will stop.",
                            StatusTone.ERROR,
                        )
                    }
                    scanDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firmware)
        bindViews()
        configureProfileSpinner()
        configureActions()
        registerUsbReceiver()
        scanDevices()
        renderProfileInstructions(selectedProfile())
        updateActionState()
    }

    override fun onDestroy() {
        cancellationToken?.cancel()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (runningOperation != null || pendingPermission != null) {
            showStatus(
                "Wait for the operation to stop, or request cancellation first.",
                StatusTone.WORKING,
            )
        } else {
            super.onBackPressed()
        }
    }

    private fun bindViews() {
        profileSpinner = findViewById(R.id.firmwareProfileSpinner)
        deviceSpinner = findViewById(R.id.firmwareDeviceSpinner)
        scanButton = findViewById(R.id.firmwareScanButton)
        bootInstructionsText = findViewById(R.id.bootInstructionsText)
        mergedFileText = findViewById(R.id.mergedFileText)
        baseFileText = findViewById(R.id.baseFileText)
        manifestFileText = findViewById(R.id.manifestFileText)
        signatureFileText = findViewById(R.id.signatureFileText)
        patchFileText = findViewById(R.id.patchFileText)
        patchStatusText = findViewById(R.id.patchStatusText)
        readyFirmwareText = findViewById(R.id.readyFirmwareText)
        detectedTargetText = findViewById(R.id.detectedTargetText)
        progressBar = findViewById(R.id.firmwareProgress)
        progressText = findViewById(R.id.firmwareProgressText)
        statusText = findViewById(R.id.firmwareStatusText)
        applyPatchButton = findViewById(R.id.applyPatchButton)
        identifyButton = findViewById(R.id.identifyTargetButton)
        flashButton = findViewById(R.id.flashFirmwareButton)
        cancelButton = findViewById(R.id.cancelFirmwareButton)
        backButton = findViewById(R.id.firmwareBackButton)
        checkGitHubButton = findViewById(R.id.checkGitHubButton)
        downloadFirmwareButton = findViewById(R.id.downloadFirmwareButton)
        githubProgressBar = findViewById(R.id.githubProgressBar)
        githubStatusText = findViewById(R.id.githubStatusText)

        selectionButtons += listOf(
            findViewById(R.id.chooseMergedButton),
            findViewById(R.id.chooseBaseButton),
            findViewById(R.id.chooseManifestButton),
            findViewById(R.id.chooseSignatureButton),
            findViewById(R.id.choosePatchButton),
        )
    }

    private fun configureProfileSpinner() {
        profileSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            profiles.map { "${it.displayName} · ${it.chip.wireName}" },
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val profile = profiles.getOrNull(position) ?: return
                renderProfileInstructions(profile)
                if (identifiedTarget?.boardId != profile.boardId) {
                    invalidateIdentification()
                }
                updateReadyFirmwareText()
                updateActionState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun configureActions() {
        backButton.setOnClickListener { onBackPressed() }
        scanButton.setOnClickListener { scanDevices() }
        selectionButtons[0].setOnClickListener {
            mergedPicker.launch(arrayOf("application/octet-stream", "*/*"))
        }
        selectionButtons[1].setOnClickListener {
            basePicker.launch(arrayOf("application/octet-stream", "*/*"))
        }
        selectionButtons[2].setOnClickListener {
            manifestPicker.launch(arrayOf("application/json", "text/json", "*/*"))
        }
        selectionButtons[3].setOnClickListener {
            signaturePicker.launch(arrayOf("application/octet-stream", "*/*"))
        }
        selectionButtons[4].setOnClickListener {
            patchPicker.launch(arrayOf("application/octet-stream", "*/*"))
        }
        applyPatchButton.setOnClickListener { applySignedPatch() }
        identifyButton.setOnClickListener {
            requestUsbPermissionThen(UsbAction.IDENTIFY)
        }
        flashButton.setOnClickListener { showFlashConfirmation() }
        cancelButton.setOnClickListener {
            cancellationToken?.cancel()
            cancelButton.isEnabled = false
            showStatus(getString(R.string.firmware_cancel_requested), StatusTone.WORKING)
        }
        checkGitHubButton.setOnClickListener { checkGitHubForFirmware() }
        downloadFirmwareButton.setOnClickListener { downloadFirmwareFromGitHub() }
    }

    private fun registerUsbReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_FLASH_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun scanDevices() {
        val selectedId = selectedDevice()?.deviceId
        devices = UsbSerialFlasherTransport.enumerate(this)
        deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            devices,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val restoredIndex = devices.indexOfFirst { it.deviceId == selectedId }
        if (restoredIndex >= 0) deviceSpinner.setSelection(restoredIndex)

        if (identifiedTarget?.deviceId !in devices.map(FlasherUsbDevice::deviceId)) {
            invalidateIdentification()
        }
        if (devices.isEmpty()) {
            showStatus(getString(R.string.firmware_no_usb), StatusTone.MUTED)
        }
        updateActionState()
    }

    private fun selectedProfile(): DeviceProfile? =
        profiles.getOrNull(profileSpinner.selectedItemPosition)

    private fun selectedDevice(): FlasherUsbDevice? =
        devices.getOrNull(deviceSpinner.selectedItemPosition)

    private fun renderProfileInstructions(profile: DeviceProfile?) {
        if (profile == null) {
            bootInstructionsText.text = ""
            return
        }
        val heading = when (profile) {
            DeviceProfiles.M5STACK_CPLUS2 ->
                "ESP32 · CH9102 · automatic classic DTR/RTS reset"

            DeviceProfiles.CARDPUTER_ADV ->
                "ESP32-S3 · manual bootloader is the default. Enter it before Identify:"

            else -> profile.displayName
        }
        val steps = profile.manualBootloaderInstructions.mapIndexed { index, step ->
            "${index + 1}. $step"
        }
        bootInstructionsText.text = (listOf(heading) + steps).joinToString("\n")
    }

    private fun patchInputsChanged() {
        patchStatusText.text = getString(R.string.patch_waiting)
        updateActionState()
    }

    private fun acceptDocument(
        uri: Uri,
        extensions: Set<String>,
        onAccepted: (SelectedDocument) -> Unit,
    ) {
        val document = queryDocument(uri)
        val allowed = extensions.any { document.name.lowercase(Locale.ROOT).endsWith(it) }
        if (!allowed) {
            showStatus(
                "Expected ${extensions.joinToString(" or ")}; selected '${document.name}'.",
                StatusTone.ERROR,
            )
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        onAccepted(document)
        updateActionState()
    }

    private fun queryDocument(uri: Uri): SelectedDocument {
        var name: String? = null
        var size: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        return SelectedDocument(
            uri = uri,
            name = name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "document",
            sizeBytes = size?.takeIf { it >= 0L },
        )
    }

    private fun replaceReadyFirmware(replacement: ReadyFirmware) {
        val previous = readyFirmware
        readyFirmware = replacement
        if (previous is ReadyFirmware.SignedPatch &&
            previous.applied.outputFile != (replacement as? ReadyFirmware.SignedPatch)
                ?.applied
                ?.outputFile
        ) {
            runCatching { previous.applied.outputFile.delete() }
        }
        updateReadyFirmwareText()
        updateActionState()
    }

    private fun updateReadyFirmwareText() {
        val ready = readyFirmware
        val profile = selectedProfile()
        readyFirmwareText.text = when (ready) {
            null -> getString(R.string.no_firmware_ready)
            is ReadyFirmware.ImportedMerged ->
                "${ready.document.displayText()}\nRaw merged image → flash offset 0x00000000"

            is ReadyFirmware.SignedPatch -> {
                val compatibility = if (profile?.boardId == ready.applied.profile.boardId) {
                    "Profile matches."
                } else {
                    "Select ${ready.applied.profile.displayName} to flash this authenticated output."
                }
                "${ready.packageId}\n${ready.applied.outputFile.length()} bytes · " +
                    "SHA-256 ${ready.applied.sha256}\n$compatibility"
            }
        }
    }

    private fun invalidateIdentification() {
        identifiedTarget = null
        detectedTargetText.text = getString(R.string.target_not_identified)
        updateActionState()
    }

    private fun requestUsbPermissionThen(action: UsbAction) {
        if (runningOperation != null || pendingPermission != null) {
            showStatus(getString(R.string.firmware_busy), StatusTone.WORKING)
            return
        }
        val descriptor = selectedDevice()
        val profile = selectedProfile()
        if (descriptor == null || profile == null) {
            showStatus("Select a target profile and USB serial device.", StatusTone.ERROR)
            return
        }
        val device = findUsbDevice(descriptor.deviceId)
        if (device == null) {
            showStatus("The selected USB device is no longer attached.", StatusTone.ERROR)
            scanDevices()
            return
        }
        if (usbManager.hasPermission(device)) {
            startUsbAction(action, descriptor, profile)
            return
        }

        pendingPermission = PendingPermission(action, descriptor.deviceId, profile.boardId)
        showStatus(getString(R.string.firmware_permission_waiting), StatusTone.WORKING)
        updateActionState()
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            descriptor.deviceId,
            Intent(ACTION_FLASH_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            usbManager.requestPermission(device, permissionIntent)
        }.onFailure {
            pendingPermission = null
            showStatus(
                "Could not request USB permission: ${it.safeMessage()}",
                StatusTone.ERROR,
            )
            updateActionState()
        }
    }

    private fun handlePermissionResult(intent: Intent) {
        val device = intent.usbDeviceExtra() ?: return
        val pending = pendingPermission ?: return
        if (pending.deviceId != device.deviceId) return
        pendingPermission = null

        if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            showStatus(getString(R.string.firmware_permission_denied), StatusTone.ERROR)
            updateActionState()
            return
        }
        val descriptor = devices.firstOrNull { it.deviceId == pending.deviceId }
        val profile = profiles.firstOrNull { it.boardId == pending.boardId }
        if (descriptor == null || profile == null ||
            selectedDevice()?.deviceId != descriptor.deviceId ||
            selectedProfile()?.boardId != profile.boardId
        ) {
            showStatus(
                "USB device or target profile changed while permission was pending.",
                StatusTone.ERROR,
            )
            updateActionState()
            return
        }
        startUsbAction(pending.action, descriptor, profile)
    }

    private fun startUsbAction(
        action: UsbAction,
        descriptor: FlasherUsbDevice,
        profile: DeviceProfile,
    ) {
        when (action) {
            UsbAction.IDENTIFY -> identifyTarget(descriptor, profile)
            UsbAction.FLASH -> flashFirmware(descriptor, profile)
        }
    }

    private fun identifyTarget(
        descriptor: FlasherUsbDevice,
        profile: DeviceProfile,
    ) {
        setRunningOperation(RunningOperation.IDENTIFY)
        showStatus(
            "Opening ${descriptor.displayName} for exclusive ROM identification…",
            StatusTone.WORKING,
        )
        val token = FlasherCancellationToken()
        cancellationToken = token
        worker.execute {
            var transport: UsbSerialFlasherTransport? = null
            try {
                transport = UsbSerialFlasherTransport.open(this, descriptor.deviceId)
                activeTransport = transport
                val flasher = EspSerialFlasher(transport, nativeResetStrategy(profile))
                val info = flasher.identify(
                    expectedChip = nativeChip(profile),
                    options = IdentifyOptions(resetAfter = true),
                    progressListener = progressListener(),
                    cancellationSignal = token,
                )
                validateIdentifiedTarget(info, profile)
                postUi {
                    identifiedTarget = IdentifiedTarget(
                        deviceId = descriptor.deviceId,
                        boardId = profile.boardId,
                        deviceInfo = info,
                    )
                    detectedTargetText.text = describeDeviceInfo(info)
                    showStatus(
                        getString(R.string.firmware_identify_complete),
                        StatusTone.SUCCESS,
                    )
                }
            } catch (error: Throwable) {
                postOperationError("Identify failed", error)
            } finally {
                runCatching { transport?.close() }
                activeTransport = null
                cancellationToken = null
                postUi {
                    setRunningOperation(null)
                    updateActionState()
                }
            }
        }
    }

    private fun validateIdentifiedTarget(info: DeviceInfo, profile: DeviceProfile) {
        val expectedChip = nativeChip(profile)
        if (info.chip != expectedChip) {
            throw IllegalStateException(
                "Expected ${expectedChip.displayName}, but ROM reported ${info.chipName}.",
            )
        }
        val flashSize = info.flashSizeBytes
            ?: throw IllegalStateException(
                "ROM chip identification succeeded, but flash size could not be determined.",
            )
        if (flashSize != profile.flashSizeBytes) {
            throw IllegalStateException(
                "Expected ${formatBytes(profile.flashSizeBytes)} flash for ${profile.displayName}, " +
                    "but detected ${formatBytes(flashSize)}.",
            )
        }
        if (info.hasBlockingSecurityState()) {
            throw IllegalStateException(
                "The target reports Secure Boot, flash encryption, or secure-download mode. " +
                    "This app will not bypass those protections.",
            )
        }
    }

    private fun applySignedPatch() {
        if (runningOperation != null || pendingPermission != null) {
            showStatus(getString(R.string.firmware_busy), StatusTone.WORKING)
            return
        }
        val base = baseDocument
        val manifest = manifestDocument
        val signature = signatureDocument
        val patch = patchDocument
        val profile = selectedProfile()
        if (base == null || manifest == null || signature == null || patch == null ||
            profile == null
        ) {
            showStatus("Select all four signed package inputs and a profile.", StatusTone.ERROR)
            return
        }

        setRunningOperation(RunningOperation.PATCH)
        progressBar.isIndeterminate = true
        progressText.text = "Verifying signed manifest and exact base image…"
        patchStatusText.text = "Verification in progress…"
        showStatus(
            "Applying only if the signature, target, hashes, sizes, and BRP1 bounds all match.",
            StatusTone.WORKING,
        )

        worker.execute {
            var baseFile: File? = null
            var patchFile: File? = null
            var outputFile: File? = null
            var appliedSuccessfully = false
            try {
                val keyBytes = readPinnedDevelopmentKey()
                val trustedKeys = TrustedKeyRing.fromX509SubjectPublicKeys(
                    mapOf(PINNED_DEVELOPMENT_KEY_ID to keyBytes),
                    limits,
                )
                val manifestBytes = readDocumentBounded(
                    manifest,
                    limits.maximumManifestBytes.toLong(),
                    "manifest",
                )
                val signatureBytes = readDocumentBounded(
                    signature,
                    limits.maximumSignatureBytes.toLong(),
                    "DER signature",
                )
                val minimumCatalog = getSharedPreferences(
                    FIRMWARE_PREFERENCES,
                    Context.MODE_PRIVATE,
                ).getLong(PREFERENCE_HIGHEST_CATALOG, 0L)
                val verified = FirmwareManifestVerifier(trustedKeys, limits).verify(
                    rawManifestBytes = manifestBytes,
                    signatureBytes = signatureBytes,
                    policy = ManifestVerificationPolicy(
                        minimumCatalogVersion = minimumCatalog,
                    ),
                )
                if (verified.manifest.app.minimumVersionCode > BuildConfig.VERSION_CODE) {
                    throw IllegalStateException(
                        "This package requires app version code " +
                            "${verified.manifest.app.minimumVersionCode}; installed code is " +
                            "${BuildConfig.VERSION_CODE}.",
                    )
                }

                baseFile = copyDocumentToCache(
                    base,
                    limits.maximumArtifactBytes,
                    "base",
                )
                patchFile = copyDocumentToCache(
                    patch,
                    limits.maximumPatchBytes,
                    "patch",
                )
                outputFile = uniqueCacheOutput()
                val applied = FirmwarePackageEngine(limits).apply(
                    verifiedManifest = verified,
                    inputFirmware = baseFile,
                    patchPayload = patchFile,
                    outputFirmware = outputFile,
                    expectedBoardId = profile.boardId,
                )
                appliedSuccessfully = true
                getSharedPreferences(FIRMWARE_PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(
                        PREFERENCE_HIGHEST_CATALOG,
                        maxOf(minimumCatalog, verified.manifest.catalogVersion),
                    )
                    .apply()

                postUi {
                    replaceReadyFirmware(
                        ReadyFirmware.SignedPatch(
                            applied = applied,
                            packageId = verified.manifest.packageId,
                        ),
                    )
                    patchStatusText.text =
                        "${verified.manifest.packageId} verified by key " +
                            "'${verified.signingKeyId}'. Exact input and output hashes passed."
                    showStatus(
                        getString(R.string.firmware_patch_complete),
                        StatusTone.SUCCESS,
                    )
                }
            } catch (error: Throwable) {
                postUi {
                    patchStatusText.text = "Patch rejected: ${error.safeMessage()}"
                }
                postOperationError("Patch rejected", error)
            } finally {
                runCatching { baseFile?.delete() }
                runCatching { patchFile?.delete() }
                if (!appliedSuccessfully) runCatching { outputFile?.delete() }
                postUi {
                    progressBar.isIndeterminate = false
                    setRunningOperation(null)
                    updateActionState()
                }
            }
        }
    }

    private fun showFlashConfirmation() {
        val ready = readyFirmware
        val descriptor = selectedDevice()
        val profile = selectedProfile()
        val identity = identifiedTarget
        if (ready == null || descriptor == null || profile == null ||
            identity == null ||
            identity.deviceId != descriptor.deviceId ||
            identity.boardId != profile.boardId ||
            !ready.matches(profile)
        ) {
            showStatus(
                "Prepare firmware and identify the currently selected target first.",
                StatusTone.ERROR,
            )
            updateActionState()
            return
        }

        val writePlan = when (ready) {
            is ReadyFirmware.ImportedMerged -> {
                val size = ready.document.sizeBytes?.let(::formatBytes) ?: "provider-reported size unknown"
                "Write the complete merged image ($size) at offset 0x00000000."
            }

            is ReadyFirmware.SignedPatch -> {
                val segments = ready.applied.flashSegments.joinToString("\n") {
                    "• ${formatBytes(it.sizeBytes)} at 0x${it.flashOffset.toString(16).padStart(8, '0')}"
                }
                "Write only these authenticated manifest segments:\n$segments"
            }
        }
        val message = buildString {
            appendLine("USB: ${descriptor.displayName} (${descriptor.vidPid})")
            appendLine("Profile: ${profile.displayName}")
            appendLine("Detected: ${identity.deviceInfo.chipName}, " +
                "${formatBytes(identity.deviceInfo.flashSizeBytes ?: 0L)} flash")
            appendLine("Image: ${ready.label}")
            appendLine()
            appendLine(writePlan)
            appendLine()
            append("Writing firmware can leave the device unbootable if interrupted. " +
                "Keep the cable connected. The ROM chip is checked again before writing.")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.firmware_confirmation_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.firmware_confirmation_action) { _, _ ->
                requestUsbPermissionThen(UsbAction.FLASH)
            }
            .show()
    }

    private fun flashFirmware(
        descriptor: FlasherUsbDevice,
        profile: DeviceProfile,
    ) {
        val ready = readyFirmware
        val identity = identifiedTarget
        if (ready == null || identity == null ||
            identity.deviceId != descriptor.deviceId ||
            identity.boardId != profile.boardId ||
            !ready.matches(profile)
        ) {
            showStatus(
                "Firmware, profile, USB device, or identification changed after confirmation.",
                StatusTone.ERROR,
            )
            updateActionState()
            return
        }

        setRunningOperation(RunningOperation.FLASH)
        showStatus(
            "Preparing bytes, then reconnecting to the ROM loader for a checked flash write…",
            StatusTone.WORKING,
        )
        val token = FlasherCancellationToken()
        cancellationToken = token
        worker.execute {
            var transport: UsbSerialFlasherTransport? = null
            var importedFile: File? = null
            try {
                val segments = when (ready) {
                    is ReadyFirmware.ImportedMerged -> {
                        importedFile = copyDocumentToCache(
                            ready.document,
                            limits.maximumArtifactBytes,
                            "merged",
                        )
                        listOf(
                            NativeFlashSegment(
                                offset = 0L,
                                data = importedFile.readBytes(),
                                name = ready.document.name,
                            ),
                        )
                    }

                    is ReadyFirmware.SignedPatch -> readAuthenticatedSegments(ready.applied)
                }
                transport = UsbSerialFlasherTransport.open(this, descriptor.deviceId)
                activeTransport = transport
                val flasher = EspSerialFlasher(transport, nativeResetStrategy(profile))
                val result = flasher.flash(
                    segments = segments,
                    options = FlashOptions(
                        expectedChip = nativeChip(profile),
                        useStub = true,
                        verify = true,
                        flashBaudRate =
                            profile.preferredFastBaudRate ?: profile.initialBaudRate,
                        allowSecurityRisks = false,
                    ),
                    progressListener = progressListener(),
                    cancellationSignal = token,
                )
                validateIdentifiedTarget(result, profile)
                postUi {
                    identifiedTarget = null
                    detectedTargetText.text =
                        "Flash verified for ${result.chipName}. Identify again before another write."
                    progressBar.isIndeterminate = false
                    progressBar.progress = 100
                    progressText.text = "Complete · 100%"
                    val restartNote = if (profile == DeviceProfiles.CARDPUTER_ADV) {
                        " Disconnect USB, set the side power switch OFF, then power it on normally."
                    } else {
                        ""
                    }
                    showStatus(
                        getString(R.string.firmware_flash_complete) + restartNote,
                        StatusTone.SUCCESS,
                    )
                }
            } catch (error: Throwable) {
                postOperationError("Flash failed", error)
            } finally {
                runCatching { transport?.close() }
                runCatching { importedFile?.delete() }
                activeTransport = null
                cancellationToken = null
                postUi {
                    setRunningOperation(null)
                    updateActionState()
                }
            }
        }
    }

    private fun readAuthenticatedSegments(applied: AppliedFirmware): List<NativeFlashSegment> =
        RandomAccessFile(applied.outputFile, "r").use { input ->
            applied.flashSegments.mapIndexed { index, segment ->
                if (segment.sizeBytes > Int.MAX_VALUE) {
                    throw IOException("Flash segment $index is too large for Android memory")
                }
                val data = ByteArray(segment.sizeBytes.toInt())
                input.seek(segment.sourceOffset)
                input.readFully(data)
                NativeFlashSegment(
                    offset = segment.flashOffset,
                    data = data,
                    name = "signed-segment-$index",
                )
            }
        }

    private fun nativeChip(profile: DeviceProfile): NativeEspChip =
        when (profile.chip) {
            io.bruceremote.app.firmware.EspChip.ESP32 -> NativeEspChip.ESP32
            io.bruceremote.app.firmware.EspChip.ESP32_S3 -> NativeEspChip.ESP32_S3
        }

    private fun nativeResetStrategy(profile: DeviceProfile): NativeResetStrategy =
        when (profile) {
            DeviceProfiles.M5STACK_CPLUS2 -> NativeResetStrategy.CLASSIC_DTR_RTS
            DeviceProfiles.CARDPUTER_ADV -> NativeResetStrategy.MANUAL_BOOTLOADER
            else -> NativeResetStrategy.MANUAL_BOOTLOADER
        }

    private fun progressListener(): FlashProgressListener {
        var lastPhase: FlashPhase? = null
        var lastPercent = -1
        return FlashProgressListener { progress ->
            val percent = progress.percent()
            if (progress.phase != lastPhase || percent != lastPercent) {
                lastPhase = progress.phase
                lastPercent = percent
                postUi { renderProgress(progress, percent) }
            }
        }
    }

    private fun renderProgress(progress: FlashProgress, percent: Int) {
        progressBar.isIndeterminate = progress.totalBytes <= 0L &&
            progress.phase != FlashPhase.COMPLETE
        if (!progressBar.isIndeterminate) progressBar.progress = percent
        val phase = progress.phase.name.lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replaceFirstChar(Char::uppercase)
        progressText.text = if (progress.totalBytes > 0L) {
            "$phase · $percent% · ${formatBytes(progress.bytesCompleted)} / " +
                formatBytes(progress.totalBytes)
        } else {
            phase
        }
    }

    private fun FlashProgress.percent(): Int =
        when {
            phase == FlashPhase.COMPLETE -> 100
            totalBytes <= 0L -> 0
            else -> ((bytesCompleted.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
        }

    private fun setRunningOperation(operation: RunningOperation?) {
        runningOperation = operation
        if (operation == null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (progressBar.isIndeterminate) progressBar.isIndeterminate = false
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            progressBar.progress = 0
            progressBar.isIndeterminate = operation != RunningOperation.FLASH
            progressText.text = when (operation) {
                RunningOperation.IDENTIFY -> "Connecting to ROM loader…"
                RunningOperation.PATCH -> "Verifying package…"
                RunningOperation.FLASH -> "Preparing flash operation…"
            }
        }
        updateActionState()
    }

    private fun updateActionState() {
        val locked = runningOperation != null || pendingPermission != null
        val profile = selectedProfile()
        val descriptor = selectedDevice()
        val ready = readyFirmware
        val identity = identifiedTarget
        val patchInputsReady = baseDocument != null &&
            manifestDocument != null &&
            signatureDocument != null &&
            patchDocument != null
        val identifiedForSelection = identity != null &&
            identity.deviceId == descriptor?.deviceId &&
            identity.boardId == profile?.boardId

        profileSpinner.isEnabled = !locked
        deviceSpinner.isEnabled = !locked && devices.isNotEmpty()
        scanButton.isEnabled = !locked
        selectionButtons.forEach { it.isEnabled = !locked }
        applyPatchButton.isEnabled = !locked && patchInputsReady && profile != null
        identifyButton.isEnabled = !locked && descriptor != null && profile != null
        flashButton.isEnabled = !locked &&
            ready != null &&
            profile != null &&
            ready.matches(profile) &&
            identifiedForSelection &&
            identity?.deviceInfo?.hasBlockingSecurityState() != true
        cancelButton.isEnabled = runningOperation == RunningOperation.IDENTIFY ||
            runningOperation == RunningOperation.FLASH
        backButton.isEnabled = !locked
    }

    private fun showStatus(message: String, tone: StatusTone) {
        statusText.text = message
        val color = when (tone) {
            StatusTone.MUTED -> R.color.bruce_text_muted
            StatusTone.WORKING -> R.color.bruce_secondary
            StatusTone.SUCCESS -> R.color.bruce_success
            StatusTone.ERROR -> R.color.bruce_error
        }
        statusText.setTextColor(ContextCompat.getColor(this, color))
        flashButton.backgroundTintList = if (tone == StatusTone.ERROR) {
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bruce_error))
        } else {
            null
        }
    }

    private fun postOperationError(prefix: String, error: Throwable) {
        postUi {
            val cancelled = error is FlasherException &&
                error.error == FlasherError.CANCELLED
            val message = if (cancelled) {
                "Operation cancelled. Re-enter bootloader mode and identify before retrying."
            } else {
                "$prefix: ${error.safeMessage()}"
            }
            progressBar.isIndeterminate = false
            progressText.text = if (cancelled) "Cancelled" else "Error"
            showStatus(message, if (cancelled) StatusTone.WORKING else StatusTone.ERROR)
        }
    }

    private fun describeDeviceInfo(info: DeviceInfo): String = buildString {
        append("${info.chipName} · ${formatBytes(info.flashSizeBytes ?: 0L)} flash")
        info.macAddress?.let { append("\nMAC $it") }
        if (!info.security.available) {
            append("\nSecurity-info command unavailable on this ROM")
        } else {
            append(
                "\nSecure Boot ${onOff(info.security.secureBootEnabled)} · " +
                    "flash encryption ${onOff(info.security.flashEncryptionEnabled)} · " +
                    "secure download ${onOff(info.security.secureDownloadModeEnabled)}",
            )
        }
    }

    private fun DeviceInfo.hasBlockingSecurityState(): Boolean =
        security.available &&
            (security.secureBootEnabled ||
                security.flashEncryptionEnabled ||
                security.secureDownloadModeEnabled)

    private fun onOff(enabled: Boolean): String = if (enabled) "ON" else "off"

    private fun readPinnedDevelopmentKey(): ByteArray =
        try {
            assets.open(PINNED_DEVELOPMENT_KEY_ASSET).use { input ->
                readBounded(input, MAXIMUM_PINNED_KEY_BYTES, "pinned development public key")
            }
        } catch (error: FileNotFoundException) {
            throw IllegalStateException(
                "Firmware signing is not configured: asset " +
                    "'$PINNED_DEVELOPMENT_KEY_ASSET' is missing. Add the pinned DER X.509 " +
                    "P-256 SubjectPublicKeyInfo for key_id '$PINNED_DEVELOPMENT_KEY_ID' at build " +
                    "time. A key selected alongside a package is never trusted.",
                error,
            )
        }

    private fun readDocumentBounded(
        document: SelectedDocument,
        maximumBytes: Long,
        role: String,
    ): ByteArray {
        rejectKnownOversize(document, maximumBytes, role)
        val input = contentResolver.openInputStream(document.uri)
            ?: throw IOException("Could not open $role '${document.name}'")
        return input.use { readBounded(it, maximumBytes, role) }
    }

    private fun copyDocumentToCache(
        document: SelectedDocument,
        maximumBytes: Long,
        prefix: String,
    ): File {
        rejectKnownOversize(document, maximumBytes, prefix)
        val destination = File.createTempFile("$prefix-", ".bin", cacheDir)
        try {
            val input = contentResolver.openInputStream(document.uri)
                ?: throw IOException("Could not open ${document.name}")
            input.use { source ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > maximumBytes) {
                            throw IOException(
                                "${document.name} exceeds the $maximumBytes-byte safety limit",
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (destination.length() == 0L) {
                throw IOException("${document.name} is empty")
            }
            return destination
        } catch (error: Throwable) {
            runCatching { destination.delete() }
            throw error
        }
    }

    private fun readBounded(
        input: InputStream,
        maximumBytes: Long,
        role: String,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maximumBytes) {
                throw IOException("$role exceeds the $maximumBytes-byte safety limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun rejectKnownOversize(
        document: SelectedDocument,
        maximumBytes: Long,
        role: String,
    ) {
        if (document.sizeBytes != null && document.sizeBytes > maximumBytes) {
            throw IOException(
                "$role '${document.name}' is ${document.sizeBytes} bytes; maximum is $maximumBytes",
            )
        }
    }

    private fun uniqueCacheOutput(): File {
        repeat(8) {
            val candidate = File(cacheDir, "bruce-signed-${UUID.randomUUID()}.bin")
            if (!candidate.exists()) return candidate
        }
        throw IOException("Could not reserve a unique cache path for patched firmware")
    }

    private fun findUsbDevice(deviceId: Int): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }

    private fun postUi(block: () -> Unit) {
        runOnUiThread {
            if (!isDestroyed) block()
        }
    }

    private fun Throwable.safeMessage(): String =
        message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private fun SelectedDocument.displayText(): String =
        sizeBytes?.let { "$name · ${formatBytes(it)}" } ?: name

    private fun ReadyFirmware.matches(profile: DeviceProfile): Boolean =
        when (this) {
            is ReadyFirmware.ImportedMerged -> true
            is ReadyFirmware.SignedPatch -> applied.profile.boardId == profile.boardId
        }

    private fun checkGitHubForFirmware() {
        val profile = selectedProfile()
        githubProgressBar.visibility = View.VISIBLE
        githubStatusText.text = getString(R.string.checking_for_updates)
        checkGitHubButton.isEnabled = false
        downloadFirmwareButton.visibility = View.GONE

        lifecycleScope.launchWhenStarted {
            val client = GitHubReleaseClient(this@FirmwareActivity)
            val release = client.fetchLatestRelease()

            githubProgressBar.visibility = View.GONE
            checkGitHubButton.isEnabled = true

            if (release == null) {
                githubStatusText.text = getString(R.string.download_failed, "Network error")
                return@launchWhenStarted
            }

            val asset = release.findAssetForBoard(profile.boardId)
            if (asset == null) {
                githubStatusText.text = getString(
                    R.string.download_failed,
                    "No firmware for ${profile.displayName} in ${release.tagName}",
                )
                return@launchWhenStarted
            }

            latestGitHubRelease = release
            githubStatusText.text = getString(
                R.string.update_available,
                "${release.tagName} — ${asset.name} (${formatBytes(asset.size)})",
            )
            downloadFirmwareButton.visibility = View.VISIBLE
            downloadFirmwareButton.isEnabled = true
            showStatus(
                "GitHub release ${release.tagName} found. Tap Download to fetch firmware.",
                StatusTone.SUCCESS,
            )
        }
    }

    private fun downloadFirmwareFromGitHub() {
        val release = latestGitHubRelease ?: return
        val profile = selectedProfile()
        val asset = release.findAssetForBoard(profile.boardId) ?: return

        githubProgressBar.visibility = View.VISIBLE
        githubProgressBar.isIndeterminate = false
        githubProgressBar.progress = 0
        downloadFirmwareButton.isEnabled = false
        githubStatusText.text = getString(R.string.downloading_firmware)

        lifecycleScope.launchWhenStarted {
            val client = GitHubReleaseClient(this@FirmwareActivity)
            try {
                val result = client.downloadAsset(asset) { downloaded, total ->
                    val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    githubProgressBar.progress = percent
                    githubStatusText.text = "Downloading… $percent%"
                }

                downloadedFirmwareFile = result.file
                githubProgressBar.visibility = View.GONE
                githubStatusText.text = getString(R.string.download_complete)

                // Convert downloaded file to SelectedDocument and set as merged firmware
                val uri = Uri.fromFile(result.file)
                val document = SelectedDocument(
                    uri = uri,
                    name = result.asset.name,
                    sizeBytes = result.asset.size,
                )
                mergedDocument = document
                mergedFileText.text = document.displayText()
                replaceReadyFirmware(ReadyFirmware.ImportedMerged(document))
                showStatus(
                    "Firmware downloaded from GitHub. Identify target before flashing.",
                    StatusTone.SUCCESS,
                )
            } catch (e: Exception) {
                githubProgressBar.visibility = View.GONE
                githubStatusText.text = getString(R.string.download_failed, e.message ?: "Unknown error")
                downloadFirmwareButton.isEnabled = true
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit += 1
        } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format(Locale.US, "%.2f %s", value, units[unit])
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private data class SelectedDocument(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long?,
    )

    private sealed class ReadyFirmware {
        abstract val label: String

        data class ImportedMerged(
            val document: SelectedDocument,
        ) : ReadyFirmware() {
            override val label: String = document.name
        }

        data class SignedPatch(
            val applied: AppliedFirmware,
            val packageId: String,
        ) : ReadyFirmware() {
            override val label: String = "$packageId (signed BRP1 output)"
        }
    }

    private data class IdentifiedTarget(
        val deviceId: Int,
        val boardId: String,
        val deviceInfo: DeviceInfo,
    )

    private data class PendingPermission(
        val action: UsbAction,
        val deviceId: Int,
        val boardId: String,
    )

    private enum class UsbAction {
        IDENTIFY,
        FLASH,
    }

    private enum class RunningOperation {
        IDENTIFY,
        PATCH,
        FLASH,
    }

    private enum class StatusTone {
        MUTED,
        WORKING,
        SUCCESS,
        ERROR,
    }

    private companion object {
        const val ACTION_FLASH_USB_PERMISSION =
            "io.bruceremote.app.FLASH_USB_PERMISSION"
        const val PINNED_DEVELOPMENT_KEY_ASSET =
            "firmware_keys/dev_p256_public.der"
        const val PINNED_DEVELOPMENT_KEY_ID = "dev-local-2026"
        const val MAXIMUM_PINNED_KEY_BYTES = 1024L
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val FIRMWARE_PREFERENCES = "firmware_trust"
        const val PREFERENCE_HIGHEST_CATALOG = "highest_catalog_version"
    }
}
