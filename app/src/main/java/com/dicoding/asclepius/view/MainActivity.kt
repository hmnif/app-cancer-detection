package com.dicoding.asclepius.view

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.dicoding.asclepius.R
import com.dicoding.asclepius.data.local.HistoryEntity
import com.dicoding.asclepius.databinding.ActivityMainBinding
import com.dicoding.asclepius.helper.ImageClassifierHelper
import com.dicoding.asclepius.utils.ViewModelFactory
import com.dicoding.asclepius.utils.convertMillisToDateString
import com.dicoding.asclepius.view.history.HistoryViewModel
import com.dicoding.asclepius.view.result.ResultActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.task.vision.classifier.Classifications
import com.yalantis.ucrop.UCrop

class ImageViewModel : ViewModel() {
    var currentImageUri: Uri? = null
}

class MainActivity : BaseClass() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: HistoryViewModel by viewModels { ViewModelFactory.getInstance(application) }
    private val imageViewModel: ImageViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                showToast("Permintaan Izin Disetujui")
            } else {
                showToast("Permintaan Izin Ditolak")
            }
        }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, REQUIRED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore the URI from ViewModel
        imageViewModel.currentImageUri?.let {
            showImage(it)
        }

        setActionBar()

        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(REQUIRED_PERMISSION)
        }

        binding.apply {
            analyzeButton.setOnClickListener {
                imageViewModel.currentImageUri?.let {
                    analyzeImage(it)
                } ?: showToast("No image selected")
            }
            galleryButton.setOnClickListener {
                startGallery()
            }
        }
    }

    private fun startGallery() {
        launcherGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private val launcherGallery = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            UCrop.of(uri, Uri.fromFile(cacheDir.resolve("${System.currentTimeMillis()}.jpg")))
                .withAspectRatio(16F, 9F)
                .withMaxResultSize(2000, 2000)
                .start(this)
        } else {
            Log.d("Photo Picker", "No media selected")
        }
    }

    @Deprecated("Deprecated in Java", ReplaceWith(
        "super.onActivityResult(requestCode, resultCode, data)",
        "androidx.appcompat.app.AppCompatActivity"
    ))
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == UCrop.REQUEST_CROP) {
            val resultUri = UCrop.getOutput(data!!)
            if (resultUri != null) {
                imageViewModel.currentImageUri = resultUri
                showImage(resultUri)
            } else {
                Log.e("Crop Error", "Result URI is null")
                showToast("Error: Crop result is null")
            }
        } else if (resultCode == UCrop.RESULT_ERROR) {
            val cropError = UCrop.getError(data!!)
            Log.e("Crop Error", "onActivityResult: $cropError")
        }
    }

    private fun showImage(uri: Uri) {
        binding.previewImageView.setImageURI(uri)
        binding.analyzeButton.visibility = android.view.View.VISIBLE
        binding.galleryButton.apply {
            text = resources.getString(R.string.replace_image)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
        }
    }

    private fun analyzeImage(image: Uri) {

        val imageHelper = ImageClassifierHelper(
            context = this,
            classifierListener = object : ImageClassifierHelper.ClassifierListener {
                override fun onError(error: String) {
                    showToast(error)
                }

                override fun onResults(results: List<Classifications>?) {
                    val resultString = results?.joinToString("\n") {
                        val threshold = (it.categories[0].score * 100).toInt()
                        "${it.categories[0].label} : ${threshold}%"
                    }
                    if (resultString != null) {
                        val data = HistoryEntity(date = convertMillisToDateString(System.currentTimeMillis()), uri = image.toString(), result = resultString)
                        lifecycleScope.launch(Dispatchers.IO) {

                            this@MainActivity.runOnUiThread {
                                viewModel.addHistory(data)
                                moveToResult(image, resultString)
                            }

                        }
                    }
                }
            }
        )

        imageHelper.classifyStaticImage(image)

    }

    private fun moveToResult(image:Uri, result: String){
        val intent = Intent(this, ResultActivity::class.java)
        intent.putExtra(ResultActivity.EXTRA_IMAGE_URI, image.toString())
        intent.putExtra(ResultActivity.EXTRA_RESULT, result)
        startActivity(intent)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUIRED_PERMISSION = Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
