package ir.factory.entryexit.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import ir.factory.entryexit.R
import ir.factory.entryexit.data.PersonEntity
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.databinding.ActivitySetupBinding
import ir.factory.entryexit.databinding.ItemSetupEntryBinding
import ir.factory.entryexit.util.AiKeyHelper
import ir.factory.entryexit.util.GeminiVisionClient
import ir.factory.entryexit.util.ImagePrep
import ir.factory.entryexit.util.PlateMatcher
import ir.factory.entryexit.util.toPersianDigitsInString
import ir.factory.entryexit.viewmodel.FactoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets the office set up profile photos for personnel/drivers and equipment photos for
 * machinery before the app goes into daily use.
 *
 * Two AI-assisted flows live here on top of the original plain gallery picker:
 *  - Personnel/Driver rows: photo (gallery or camera) can optionally be sent to Gemini
 *    ([GeminiVisionClient.generatePersonnelPhoto]) to be cleaned up into a uniform official
 *    ID-style photo, with the face itself explicitly preserved.
 *  - Machinery tab: a toolbar action lets the guard bulk-upload several vehicle photos at once;
 *    each is read by Gemini for its plate number ([GeminiVisionClient.detectLicensePlate]) and
 *    auto-attached to the matching vehicle card, with no manual matching needed.
 *
 * Every saved photo — AI-processed or not — is downscaled, EXIF-corrected, and copied into the
 * app's own storage via [ImagePrep], so [ir.factory.entryexit.data.PersonEntity.imageUri] always
 * ends up pointing at a `file://` Uri we control rather than a possibly-transient content:// Uri.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var viewModel: FactoryViewModel
    private lateinit var adapter: SetupAdapter
    private var currentType: PersonType = PersonType.PERSONNEL

    /** The roster row a launched picker/camera intent is currently working on. */
    private var pendingTarget: PersonEntity? = null
    private var pendingCameraUri: Uri? = null
    private var pendingPlateScanCameraUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val person = pendingTarget
        if (uri != null && person != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onPhotoObtained(person, uri)
        }
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val person = pendingTarget
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && person != null && uri != null) {
            onPhotoObtained(person, uri)
        }
    }

    private val pickMultipleForPlateScan =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) runPlateScanBulk(uris)
        }

    private val takePhotoForPlateScan = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingPlateScanCameraUri
        pendingPlateScanCameraUri = null
        if (success && uri != null) runPlateScanBulk(listOf(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[FactoryViewModel::class.java]

        binding.toolbar.title = getString(R.string.setup_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_setup)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_plate_scan) {
                showPlateScanChooser()
                true
            } else {
                false
            }
        }

        adapter = SetupAdapter(currentType, ::launchGalleryPicker, ::launchCameraCapture)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(R.string.setup_subtitle_personnel)))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(R.string.setup_subtitle_machinery)))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(R.string.setup_subtitle_driver)))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentType = when (tab.position) {
                    0 -> PersonType.PERSONNEL
                    1 -> PersonType.MACHINERY
                    else -> PersonType.DRIVER
                }
                adapter = SetupAdapter(currentType, ::launchGalleryPicker, ::launchCameraCapture)
                binding.recyclerView.adapter = adapter
                updatePlateScanMenuVisibility()
                loadRoster()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        updatePlateScanMenuVisibility()
        loadRoster()
    }

    private fun updatePlateScanMenuVisibility() {
        binding.toolbar.menu.findItem(R.id.action_plate_scan)?.isVisible = (currentType == PersonType.MACHINERY)
    }

    private fun launchGalleryPicker(person: PersonEntity) {
        pendingTarget = person
        pickImage.launch(arrayOf("image/*"))
    }

    private fun launchCameraCapture(person: PersonEntity) {
        pendingTarget = person
        val uri = ImagePrep.createCameraCaptureUri(this)
        pendingCameraUri = uri
        takePhoto.launch(uri)
    }

    private fun loadRoster() {
        viewModel.loadRosterOnce(currentType) { roster -> adapter.submit(roster) }
    }

    // ---- Feature 1: personnel/driver photo -> optional AI headshot cleanup ----

    private fun onPhotoObtained(person: PersonEntity, uri: Uri) {
        val type = runCatching { PersonType.valueOf(person.type) }.getOrNull()
        if (type == PersonType.PERSONNEL || type == PersonType.DRIVER) {
            offerAiProcessing(person, uri)
        } else {
            saveDirectly(person, uri)
        }
    }

    private fun offerAiProcessing(person: PersonEntity, uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_headshot_dialog_title)
            .setMessage(R.string.ai_headshot_dialog_message)
            .setPositiveButton(R.string.ai_headshot_dialog_yes) { _, _ -> runAiHeadshot(person, uri) }
            .setNegativeButton(R.string.ai_headshot_dialog_no) { _, _ -> saveDirectly(person, uri) }
            .show()
    }

    private fun saveDirectly(person: PersonEntity, uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { ImagePrep.readAsJpeg(this@SetupActivity, uri) }
            if (bytes == null) {
                Toast.makeText(this@SetupActivity, R.string.setup_photo_read_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            finalizeSave(person, bytes)
        }
    }

    private fun runAiHeadshot(person: PersonEntity, uri: Uri) {
        lifecycleScope.launch {
            val apiKey = AiKeyHelper.resolveApiKey(this@SetupActivity)
            if (apiKey == null) {
                showAiKeyMissingDialog()
                return@launch
            }

            val prepared = withContext(Dispatchers.IO) { ImagePrep.readAsJpeg(this@SetupActivity, uri) }
            if (prepared == null) {
                Toast.makeText(this@SetupActivity, R.string.setup_photo_read_error, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val (dialog, _) = showProgressDialog(getString(R.string.ai_headshot_processing))
            val result = withContext(Dispatchers.IO) {
                GeminiVisionClient.generatePersonnelPhoto(apiKey, prepared, "image/jpeg")
            }
            dialog.dismiss()

            result.onSuccess { newBytes ->
                showHeadshotPreview(person, newBytes, prepared)
            }.onFailure { err ->
                showAiErrorDialog(
                    error = err,
                    onRetry = { runAiHeadshot(person, uri) },
                    onUseOriginal = { finalizeSave(person, prepared) }
                )
            }
        }
    }

    private fun showHeadshotPreview(person: PersonEntity, aiBytes: ByteArray, originalBytes: ByteArray) {
        val view = layoutInflater.inflate(R.layout.dialog_ai_photo_preview, null)
        val iv = view.findViewById<ImageView>(R.id.ivPreview)
        val bitmap = runCatching { BitmapFactory.decodeByteArray(aiBytes, 0, aiBytes.size) }.getOrNull()
        if (bitmap != null) iv.setImageBitmap(bitmap)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_headshot_preview_title)
            .setView(view)
            .setPositiveButton(R.string.ai_headshot_use_new) { _, _ -> finalizeSave(person, aiBytes) }
            .setNeutralButton(R.string.ai_headshot_use_original) { _, _ -> finalizeSave(person, originalBytes) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showAiErrorDialog(error: Throwable, onRetry: () -> Unit, onUseOriginal: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_headshot_error_title)
            .setMessage(error.message ?: getString(R.string.error_generic))
            .setPositiveButton(R.string.ai_headshot_retry) { _, _ -> onRetry() }
            .setNeutralButton(R.string.ai_headshot_use_original) { _, _ -> onUseOriginal() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun finalizeSave(person: PersonEntity, bytes: ByteArray) {
        lifecycleScope.launch {
            val savedUri = withContext(Dispatchers.IO) {
                ImagePrep.savePermanently(this@SetupActivity, bytes, person.imageUri)
            }
            viewModel.updatePersonImage(person.id, savedUri.toString()) { result ->
                result.onSuccess {
                    Toast.makeText(this@SetupActivity, R.string.setup_image_updated, Toast.LENGTH_SHORT).show()
                    loadRoster()
                }.onFailure {
                    Toast.makeText(this@SetupActivity, it.message ?: getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---- Feature 2: machinery bulk photo upload with automatic plate detection ----

    private fun showPlateScanChooser() {
        val options = arrayOf(
            getString(R.string.plate_scan_pick_gallery),
            getString(R.string.plate_scan_take_photo)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plate_scan_title)
            .setItems(options) { _, which ->
                if (which == 0) {
                    pickMultipleForPlateScan.launch("image/*")
                } else {
                    val uri = ImagePrep.createCameraCaptureUri(this)
                    pendingPlateScanCameraUri = uri
                    takePhotoForPlateScan.launch(uri)
                }
            }
            .show()
    }

    private fun runPlateScanBulk(uris: List<Uri>) {
        lifecycleScope.launch {
            val apiKey = AiKeyHelper.resolveApiKey(this@SetupActivity)
            if (apiKey == null) {
                showAiKeyMissingDialog()
                return@launch
            }

            val roster = withContext(Dispatchers.IO) { viewModel.repository.getRosterOnce(PersonType.MACHINERY) }
            val (dialog, progressText) = showProgressDialog(getString(R.string.plate_scan_progress_format, 0, uris.size))

            val results = mutableListOf<String>()
            uris.forEachIndexed { index, uri ->
                progressText.text = getString(R.string.plate_scan_progress_format, index + 1, uris.size)
                results += withContext(Dispatchers.IO) { processOnePlateImage(apiKey, uri, roster) }
            }

            dialog.dismiss()
            MaterialAlertDialogBuilder(this@SetupActivity)
                .setTitle(R.string.plate_scan_result_title)
                .setMessage(results.joinToString("\n"))
                .setPositiveButton(R.string.btn_ok, null)
                .show()
            loadRoster()
        }
    }

    /** Runs on Dispatchers.IO. Reads, detects, matches, and (on success) saves+attaches one
     *  vehicle photo — returning a single human-readable outcome line for the summary dialog. */
    private suspend fun processOnePlateImage(apiKey: String, uri: Uri, roster: List<PersonEntity>): String {
        val bytes = ImagePrep.readAsJpeg(this@SetupActivity, uri)
            ?: return getString(R.string.plate_scan_result_read_error)

        val plateResult = GeminiVisionClient.detectLicensePlate(apiKey, bytes, "image/jpeg")
        val digits = plateResult.fold(
            onSuccess = { it },
            onFailure = { err -> return err.message ?: getString(R.string.error_generic) }
        ) ?: return getString(R.string.plate_scan_result_no_plate)

        val match = PlateMatcher.findMatch(digits, roster)
            ?: return getString(R.string.plate_scan_result_no_match_format, digits.toPersianDigitsInString())

        val savedUri = ImagePrep.savePermanently(this@SetupActivity, bytes, match.imageUri)
        val updateResult = viewModel.repository.updatePersonImage(match.id, savedUri.toString())
        return if (updateResult.isSuccess) {
            getString(R.string.plate_scan_result_success_format, match.name)
        } else {
            updateResult.exceptionOrNull()?.message ?: getString(R.string.error_generic)
        }
    }

    // ---- Shared small helpers ----

    private fun showProgressDialog(message: String): Pair<AlertDialog, TextView> {
        val view = layoutInflater.inflate(R.layout.dialog_ai_progress, null)
        val tv = view.findViewById<TextView>(R.id.tvProgressMessage)
        tv.text = message
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .show()
        return dialog to tv
    }

    private fun showAiKeyMissingDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_key_missing_title)
            .setMessage(R.string.ai_key_missing_message)
            .setPositiveButton(R.string.ai_open_settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private class SetupAdapter(
        private val type: PersonType,
        private val onPickGallery: (PersonEntity) -> Unit,
        private val onTakePhoto: (PersonEntity) -> Unit
    ) : RecyclerView.Adapter<SetupAdapter.VH>() {

        private var items: List<PersonEntity> = emptyList()

        fun submit(list: List<PersonEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemSetupEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size

        inner class VH(private val binding: ItemSetupEntryBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(person: PersonEntity) {
                binding.tvName.text = person.name
                val iconRes = when (type) {
                    PersonType.PERSONNEL -> R.drawable.ic_personnel
                    PersonType.DRIVER -> R.drawable.ic_driver
                    else -> R.drawable.ic_machinery
                }

                if (person.imageUri != null) {
                    binding.ivTypeIcon.visibility = View.GONE
                    binding.ivPhoto.visibility = View.VISIBLE
                    Glide.with(binding.root.context)
                        .load(Uri.parse(person.imageUri))
                        .placeholder(iconRes)
                        .error(iconRes)
                        .circleCrop()
                        .into(binding.ivPhoto)
                } else {
                    binding.ivPhoto.visibility = View.GONE
                    binding.ivTypeIcon.visibility = View.VISIBLE
                    binding.ivTypeIcon.setImageResource(iconRes)
                }

                binding.btnPickImage.setOnClickListener { onPickGallery(person) }
                binding.btnTakePhoto.setOnClickListener { onTakePhoto(person) }
            }
        }
    }
}
