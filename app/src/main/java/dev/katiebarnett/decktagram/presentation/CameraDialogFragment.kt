package dev.katiebarnett.decktagram.presentation

import android.Manifest
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.AndroidEntryPoint
import dev.katiebarnett.decktagram.NavGraphDirections
import dev.katiebarnett.decktagram.R
import dev.katiebarnett.decktagram.databinding.CameraDialogFragmentBinding
import dev.katiebarnett.decktagram.util.*
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@androidx.camera.core.ExperimentalUseCaseGroup
@androidx.camera.lifecycle.ExperimentalUseCaseGroupLifecycle
@AndroidEntryPoint
class CameraDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "CameraDialogFragment"
        
        private const val ARG_GAME_ID = "gameId"
        
        private val PERMISSIONS_INTERNAL_APP_STORAGE = arrayOf(
            Manifest.permission.CAMERA
        )
        private val PERMISSIONS_GALLERY_STORAGE = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
        )
        
        fun newInstance(gameId: Long): CameraDialogFragment {
            val dialog = CameraDialogFragment()
            val args = Bundle()
            args.putLong(ARG_GAME_ID, gameId)
            dialog.arguments = args
            return dialog
        }
    }

    interface DialogListener {
        fun onDone(imagePaths: List<String>)
    }

    internal var listener: DialogListener? = null

    private val viewModel: CameraViewModel by viewModels()

    @Inject
    lateinit var crashlytics: FirebaseCrashlytics

    @Inject
    lateinit var analytics: FirebaseAnalytics

    private lateinit var binding: CameraDialogFragmentBinding
    
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>

    private var imageCapture: ImageCapture? = null

    private lateinit var cameraExecutor: ExecutorService
    
    private lateinit var requiredPermissions: Array<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_Decktagram_FullScreenDialog)
        
        viewModel.gameId = arguments?.getLong(ARG_GAME_ID)

        requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { isGranted ->
                if (isGranted.containsValue(false)) {
                    displayCameraPermissionError()
                } else {
                    initCamera()
                }
            }
        cameraExecutor = Executors.newSingleThreadExecutor()
    }
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.camera_dialog_fragment, container, false)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this
        return binding.root
    }
    
    override fun onStart() {
        super.onStart()
        
        val dialog: Dialog? = dialog
        if (dialog != null) {
            val width = ViewGroup.LayoutParams.MATCH_PARENT
            val height = ViewGroup.LayoutParams.MATCH_PARENT
            dialog.window?.setLayout(width, height)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        viewModel.imageBufferCount.observe(viewLifecycleOwner, {})
        
        binding.captureButton.setOnClickListener { takePhoto() }

        binding.doneButton.setOnClickListener {
            analytics.logAction(CameraDone(viewModel.imageBuffer.value?.size ?: 0))
            listener?.onDone(viewModel.imageBuffer.value ?: listOf())
            dismiss()
        }

        viewModel.snackbar.observe(viewLifecycleOwner, {
            it?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
            }
        })

        binding.settingsButton.setOnClickListener {
            findNavController().navigate(NavGraphDirections.actionGlobalSettingsFragment())
        }

        viewModel.storeImagesInGallery.observe(viewLifecycleOwner, {
            lifecycleScope.launch {
                requiredPermissions = if (it) {
                    PERMISSIONS_GALLERY_STORAGE
                } else {
                    PERMISSIONS_INTERNAL_APP_STORAGE
                }
                requestCameraPermission()
            }
        })
        
        viewModel.targetSize.observe(viewLifecycleOwner, {
            initCamera()
        })
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadSettings()
        analytics.logScreenView(CameraScreen)
    }

    private fun requestCameraPermission() {
        context?.let { context ->
            val permissionsGranted = requiredPermissions.firstOrNull {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            } == null
            if (permissionsGranted) {
                initCamera()
                return
            }
            val showRationale = requiredPermissions.firstOrNull {
                shouldShowRequestPermissionRationale(it)
            } != null
            if (showRationale) {
                displayCameraPermissionError()
                return
            }
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }
    
    private fun displayCameraPermissionError() {
        context?.let {
            MaterialAlertDialogBuilder(it, R.string.error_camera_permission)
                .setNegativeButton(R.string.error_button_negative) { _, _ ->
                    dismiss()
                }
                .setPositiveButton(R.string.error_camera_permission_settings) { _, _ ->
                    navigateToAppSystemSettings()
                    dismiss()
                }.create().show()
        } ?: throw IllegalStateException("Context cannot be null")
    }
    
    private fun showGenericError() {
        context?.let {
            MaterialAlertDialogBuilder(it, R.string.error_camera_generic)
                .setNeutralButton(R.string.error_button_neutral) { _, _ ->
                    dismiss()
                    this.dismiss()
                }.create().show()
        } ?: throw IllegalStateException("Context cannot be null")
    }

    private fun navigateToAppSystemSettings() {
        context?.let {
            val appPackageName = it.packageName
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$appPackageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                it.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                crashlytics.recordException(e)
                MaterialAlertDialogBuilder(it, R.string.error_settings)
                    .setNegativeButton(R.string.error_button_negative) { _, _ ->
                        dismiss()
                    }
            }
        } ?: throw IllegalStateException("Context cannot be null")
    }

    private fun initCamera() {
        context?.let {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(it)
            cameraProviderFuture.addListener({
                val targetSize = viewModel.targetSize.value
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val previewBuilder = Preview.Builder()
                targetSize?.let {
                    previewBuilder.setTargetResolution(targetSize)
                }
                val preview = previewBuilder.build()
                    .also {
                        it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                    }
                val imageCaptureBuilder = ImageCapture.Builder()
                targetSize?.let {
                    imageCaptureBuilder.setTargetResolution(targetSize)
                }
                imageCapture = imageCaptureBuilder.build()
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                val useCaseGroupBuilder = UseCaseGroup.Builder()
                    .addUseCase(preview)
                imageCapture?.let {
                    useCaseGroupBuilder.addUseCase(it)
                }
                binding.viewFinder.viewPort?.let {
                    useCaseGroupBuilder.setViewPort(it)
                }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroupBuilder.build())
                } catch (e: Exception) {
                    crashlytics.recordException(e)
                    Log.e(TAG, "Use case binding failed", e)
                    showGenericError()
                }
            }, ContextCompat.getMainExecutor(it))
        }
    }

    private fun takePhoto() {
        context?.let { _context ->
            val imageCapture = imageCapture ?: return
            lifecycleScope.launch {
                val outputOptions = viewModel.getOutputOptions(_context)
                    .build()
                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(_context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(e: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${e.message}", e)
                            crashlytics.recordException(e)
                            showGenericError()
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.d(TAG, "Photo capture succeeded: ${output.savedUri}")
                            val path = output.savedUri?.let {
                                viewModel.getRealPathFromURI(_context, it)
                            } ?: viewModel.internalAppFilePath
                            if (path != null) {
                                viewModel.addImageToGalleryIfRequired(_context, path)
                                viewModel.saveImageToBuffer(path)
                            } else {
                                crashlytics.recordException(RuntimeException("Image path is null"))
                                showGenericError()
                            }
                        }
                    })
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        val imageCount = viewModel.imageBufferCount.value ?: 0
        analytics.logAction(CameraCancel(imageCount))
        if (imageCount > 0) {
            context?.let {
                MaterialAlertDialogBuilder(it).setMessage(
                    resources.getQuantityString(R.plurals.camera_cancel_message, imageCount))
                    .setNegativeButton(R.string.camera_cancel_message_button_negative) { _, _ ->
                        dismiss()
                    }
                    .setPositiveButton(R.string.camera_cancel_message_button_positive) { _, _ ->
                        listener?.onDone(viewModel.imageBuffer.value ?: listOf())
                        dismiss()
                    }.create().show()
            } ?: throw IllegalStateException("Context cannot be null")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    fun setListener(listener: DialogListener) {
        this.listener = listener
    }
}
