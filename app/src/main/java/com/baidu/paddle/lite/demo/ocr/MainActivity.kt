package com.baidu.paddle.lite.demo.ocr

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.os.*
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.baidu.paddle.lite.demo.ocr.MainActivity
import com.baidu.paddle.lite.demo.ocr.Utils.parseFloatsFromString
import com.baidu.paddle.lite.demo.ocr.Utils.parseLongsFromString
import com.baidu.paddle.lite.demo.ocr.Utils.rotateBitmap
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    protected var pbLoadModel: ProgressDialog? = null
    protected var pbRunModel: ProgressDialog? = null
    protected var receiver: Handler? = null // Receive messages from worker thread
    protected var sender: Handler? = null // Send command to worker thread
    protected var worker: HandlerThread? = null // Worker thread to load&run model

    // UI components of object detection
    protected var tvInputSetting: TextView? = null
    protected var tvStatus: TextView? = null
    protected var ivInputImage: ImageView? = null
    protected var tvOutputResult: TextView? = null
    protected var tvInferenceTime: TextView? = null

    // Model settings of object detection
    protected var modelPath: String? = ""
    protected var labelPath: String? = ""
    protected var imagePath: String? = ""
    protected var cpuThreadNum = 1
    protected var cpuPowerMode: String? = ""
    protected var inputColorFormat: String? = ""
    protected var inputShape = longArrayOf()
    protected var inputMean = floatArrayOf()
    protected var inputStd = floatArrayOf()
    protected var scoreThreshold = 0.1f
    private var currentPhotoPath: String? = null
    private var assetManager: AssetManager? = null
    protected var predictor: Predictor? = Predictor()
    @SuppressLint("HandlerLeak")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Clear all setting items to avoid app crashing due to the incorrect settings
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val editor = sharedPreferences.edit()
        editor.clear()
        editor.apply()

        // Setup the UI components
        tvInputSetting = findViewById(R.id.tv_input_setting)
        tvStatus = findViewById(R.id.tv_model_img_status)
        ivInputImage = findViewById(R.id.iv_input_image)
        tvInferenceTime = findViewById(R.id.tv_inference_time)
        tvOutputResult = findViewById(R.id.tv_output_result)
        tvInputSetting!!.setMovementMethod(ScrollingMovementMethod.getInstance())
        tvOutputResult!!.setMovementMethod(ScrollingMovementMethod.getInstance())

        // Prepare the worker thread for mode loading and inference
        receiver = object : Handler() {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    RESPONSE_LOAD_MODEL_SUCCESSED -> {
                        if (pbLoadModel != null && pbLoadModel!!.isShowing) {
                            pbLoadModel!!.dismiss()
                        }
                        onLoadModelSuccessed()
                    }
                    RESPONSE_LOAD_MODEL_FAILED -> {
                        if (pbLoadModel != null && pbLoadModel!!.isShowing) {
                            pbLoadModel!!.dismiss()
                        }
                        Toast.makeText(this@MainActivity, "Load model failed!", Toast.LENGTH_SHORT)
                            .show()
                        onLoadModelFailed()
                    }
                    RESPONSE_RUN_MODEL_SUCCESSED -> {
                        if (pbRunModel != null && pbRunModel!!.isShowing) {
                            pbRunModel!!.dismiss()
                        }
                        onRunModelSuccessed()
                    }
                    RESPONSE_RUN_MODEL_FAILED -> {
                        if (pbRunModel != null && pbRunModel!!.isShowing) {
                            pbRunModel!!.dismiss()
                        }
                        Toast.makeText(this@MainActivity, "Run model failed!", Toast.LENGTH_SHORT)
                            .show()
                        onRunModelFailed()
                    }
                    else -> {}
                }
            }
        }
        worker = HandlerThread("Predictor Worker")
        worker!!.start()
        sender = object : Handler(worker!!.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    REQUEST_LOAD_MODEL ->                         // Load model and reload test image
                        if (onLoadModel()) {
                            receiver!!.sendEmptyMessage(RESPONSE_LOAD_MODEL_SUCCESSED)
                        } else {
                            receiver!!.sendEmptyMessage(RESPONSE_LOAD_MODEL_FAILED)
                        }
                    REQUEST_RUN_MODEL ->                         // Run model if model is loaded
                        if (onRunModel()) {
                            receiver!!.sendEmptyMessage(RESPONSE_RUN_MODEL_SUCCESSED)
                        } else {
                            receiver!!.sendEmptyMessage(RESPONSE_RUN_MODEL_FAILED)
                        }
                    else -> {}
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        var settingsChanged = false
        val model_path = sharedPreferences.getString(
            getString(R.string.MODEL_PATH_KEY),
            getString(R.string.MODEL_PATH_DEFAULT)
        )
        val label_path = sharedPreferences.getString(
            getString(R.string.LABEL_PATH_KEY),
            getString(R.string.LABEL_PATH_DEFAULT)
        )
        val image_path = sharedPreferences.getString(
            getString(R.string.IMAGE_PATH_KEY),
            getString(R.string.IMAGE_PATH_DEFAULT)
        )
        settingsChanged = settingsChanged or !model_path.equals(modelPath, ignoreCase = true)
        settingsChanged = settingsChanged or !label_path.equals(labelPath, ignoreCase = true)
        settingsChanged = settingsChanged or !image_path.equals(imagePath, ignoreCase = true)
        val cpu_thread_num = sharedPreferences.getString(
            getString(R.string.CPU_THREAD_NUM_KEY),
            getString(R.string.CPU_THREAD_NUM_DEFAULT)
        )!!.toInt()
        settingsChanged = settingsChanged or (cpu_thread_num != cpuThreadNum)
        val cpu_power_mode = sharedPreferences.getString(
            getString(R.string.CPU_POWER_MODE_KEY),
            getString(R.string.CPU_POWER_MODE_DEFAULT)
        )
        settingsChanged = settingsChanged or !cpu_power_mode.equals(cpuPowerMode, ignoreCase = true)
        val input_color_format = sharedPreferences.getString(
            getString(R.string.INPUT_COLOR_FORMAT_KEY),
            getString(R.string.INPUT_COLOR_FORMAT_DEFAULT)
        )
        settingsChanged =
            settingsChanged or !input_color_format.equals(inputColorFormat, ignoreCase = true)
        val input_shape = parseLongsFromString(
            sharedPreferences.getString(
                getString(R.string.INPUT_SHAPE_KEY),
                getString(R.string.INPUT_SHAPE_DEFAULT)
            )!!, ","
        )
        val input_mean = parseFloatsFromString(
            sharedPreferences.getString(
                getString(R.string.INPUT_MEAN_KEY),
                getString(R.string.INPUT_MEAN_DEFAULT)
            )!!, ","
        )
        val input_std = parseFloatsFromString(
            sharedPreferences.getString(
                getString(R.string.INPUT_STD_KEY), getString(R.string.INPUT_STD_DEFAULT)
            )!!, ","
        )
        settingsChanged = settingsChanged or (input_shape.size != inputShape.size)
        settingsChanged = settingsChanged or (input_mean.size != inputMean.size)
        settingsChanged = settingsChanged or (input_std.size != inputStd.size)
        if (!settingsChanged) {
            for (i in input_shape.indices) {
                settingsChanged = settingsChanged or (input_shape[i] != inputShape[i])
            }
            for (i in input_mean.indices) {
                settingsChanged = settingsChanged or (input_mean[i] != inputMean[i])
            }
            for (i in input_std.indices) {
                settingsChanged = settingsChanged or (input_std[i] != inputStd[i])
            }
        }
        val score_threshold = sharedPreferences.getString(
            getString(R.string.SCORE_THRESHOLD_KEY),
            getString(R.string.SCORE_THRESHOLD_DEFAULT)
        )!!.toFloat()
        settingsChanged = settingsChanged or (scoreThreshold != score_threshold)
        if (settingsChanged) {
            modelPath = model_path
            labelPath = label_path
            imagePath = image_path
            cpuThreadNum = cpu_thread_num
            cpuPowerMode = cpu_power_mode
            inputColorFormat = input_color_format
            inputShape = input_shape
            inputMean = input_mean
            inputStd = input_std
            scoreThreshold = score_threshold
            // Update UI
            tvInputSetting!!.text = """
                Model: ${modelPath!!.substring(modelPath!!.lastIndexOf("/") + 1)}
                CPU Thread Num: ${Integer.toString(cpuThreadNum)}
                CPU Power Mode: $cpuPowerMode
                """.trimIndent()
            tvInputSetting!!.scrollTo(0, 0)
            // Reload model if configure has been changed
//            loadModel();
            set_img()
        }
    }

    fun loadModel() {
        pbLoadModel = ProgressDialog.show(this, "", "loading model...", false, false)
        sender!!.sendEmptyMessage(REQUEST_LOAD_MODEL)
    }

    fun runModel() {
        pbRunModel = ProgressDialog.show(this, "", "running model...", false, false)
        sender!!.sendEmptyMessage(REQUEST_RUN_MODEL)
    }

    fun onLoadModel(): Boolean {
        return predictor!!.init(
            this@MainActivity, modelPath!!, labelPath, cpuThreadNum,
            cpuPowerMode,
            inputColorFormat!!,
            inputShape, inputMean,
            inputStd, scoreThreshold
        )
    }

    fun onRunModel(): Boolean {
        return predictor!!.isLoaded() && predictor!!.runModel()
    }

    fun onLoadModelSuccessed() {
        // Load test image from path and run model
        tvStatus!!.text = "STATUS: load model successed"
    }

    fun onLoadModelFailed() {
        tvStatus!!.text = "STATUS: load model failed"
    }

    fun onRunModelSuccessed() {
        tvStatus!!.text = "STATUS: run model successed"
        // Obtain results and update UI
        tvInferenceTime!!.text = "Inference time: " + predictor!!.inferenceTime.toString() + " ms"
        val outputImage: Bitmap = predictor!!.outputImage!!
        if (outputImage != null) {
            ivInputImage!!.setImageBitmap(outputImage)
        }
        Toast.makeText(this, predictor!!.outputResult, Toast.LENGTH_LONG).show()
        Log.d(
            TAG, "onRunModelSuccessed: " + Arrays.toString(
                predictor!!.outputResult.toByteArray()
            )
        )
        tvOutputResult!!.setText(Integer.valueOf(predictor!!.outputResult.length).toString())
        Log.d(TAG, "onRunModelSuccessed: " + predictor!!.outputResult)
        tvOutputResult!!.scrollTo(0, 0)
    }

    fun onRunModelFailed() {
        tvStatus!!.text = "STATUS: run model failed"
    }

    fun onImageChanged(image: Bitmap?) {
        // Rerun model if users pick test image from gallery or camera
        if (image != null && predictor!!.isLoaded()) {
            predictor!!.inputImage = image
            runModel()
        }
    }

    fun set_img() {
        // Load test image from path and run model
        try {
            assetManager = assets
            val `in` = assetManager!!.open(imagePath!!)
            val bmp = BitmapFactory.decodeStream(`in`)
            ivInputImage!!.setImageBitmap(bmp)
        } catch (e: IOException) {
            Toast.makeText(this@MainActivity, "Load image failed!", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    fun onSettingsClicked() {
        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_action_options, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isLoaded = predictor!!.isLoaded()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.settings -> if (requestAllPermissions()) {
                // Make sure we have SDCard r&w permissions to load model from SDCard
                onSettingsClicked()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED || grantResults[1] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAllPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            )
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA
                ),
                0
            )
            return false
        }
        return true
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, null)
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        startActivityForResult(intent, OPEN_GALLERY_REQUEST_CODE)
    }

    private fun takePhoto() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        // Ensure that there's a camera activity to handle the intent
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            // Create the File where the photo should go
            var photoFile: File? = null
            try {
                photoFile = createImageFile()
            } catch (ex: IOException) {
                Log.e("MainActitity", ex.message, ex)
                Toast.makeText(
                    this@MainActivity,
                    "Create Camera temp file failed: " + ex.message, Toast.LENGTH_SHORT
                ).show()
            }
            // Continue only if the File was successfully created
            if (photoFile != null) {
                Log.i(TAG, "FILEPATH " + getExternalFilesDir("Pictures")!!.absolutePath)
                val photoURI = FileProvider.getUriForFile(
                    this,
                    "com.baidu.paddle.lite.demo.ocr.fileprovider",
                    photoFile
                )
                currentPhotoPath = photoFile.absolutePath
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                startActivityForResult(takePictureIntent, TAKE_PHOTO_REQUEST_CODE)
                Log.i(TAG, "startActivityForResult finished")
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp =
            SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val imageFileName = "JPEG_" + timeStamp + "_"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            imageFileName,  /* prefix */
            ".bmp",  /* suffix */
            storageDir /* directory */
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data == null) {
            return
        }
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                OPEN_GALLERY_REQUEST_CODE -> {

                    try {
                        val resolver = contentResolver
                        val uri = data.data
                        val image = MediaStore.Images.Media.getBitmap(resolver, uri)
                        val proj = arrayOf(MediaStore.Images.Media.DATA)
                        val cursor = managedQuery(uri, proj, null, null, null)
                        cursor.moveToFirst()
                        if (image != null) {
//                            onImageChanged(image);
                            ivInputImage!!.setImageBitmap(image)
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, e.toString())
                    }
                }
                TAKE_PHOTO_REQUEST_CODE -> if (currentPhotoPath != null) {
                    var exif: ExifInterface? = null
                    try {
                        exif = ExifInterface(currentPhotoPath)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    val orientation = exif!!.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED
                    )
                    Log.i(TAG, "rotation $orientation")
                    var image = BitmapFactory.decodeFile(currentPhotoPath)
                    image = rotateBitmap(image!!, orientation)
                    if (image != null) {
//                            onImageChanged(image);
                        ivInputImage!!.setImageBitmap(image)
                    }
                } else {
                    Log.e(TAG, "currentPhotoPath is null")
                }
                else -> {}
            }
        }
    }

    fun btn_load_model_click(view: View?) {
        if (predictor!!.isLoaded()) {
            tvStatus!!.text = "STATUS: model has been loaded"
        } else {
            tvStatus!!.text = "STATUS: load model ......"
            loadModel()
        }
    }

    fun btn_run_model_click(view: View?) {
        val image = (ivInputImage!!.drawable as BitmapDrawable).bitmap
        if (image == null) {
            tvStatus!!.text = "STATUS: image is not exists"
        } else if (!predictor!!.isLoaded()) {
            tvStatus!!.text = "STATUS: model is not loaded"
        } else {
            tvStatus!!.text = "STATUS: run model ...... "
            predictor!!.inputImage = image
            runModel()
        }
    }

    fun btn_choice_img_click(view: View?) {
        if (requestAllPermissions()) {
            openGallery()
        }
    }

    fun btn_take_photo_click(view: View?) {
        if (requestAllPermissions()) {
            takePhoto()
        }
    }

    override fun onDestroy() {
        if (predictor != null) {
            predictor!!.releaseModel()
        }
        worker!!.quit()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val OPEN_GALLERY_REQUEST_CODE = 0
        const val TAKE_PHOTO_REQUEST_CODE = 1
        const val REQUEST_LOAD_MODEL = 0
        const val REQUEST_RUN_MODEL = 1
        const val RESPONSE_LOAD_MODEL_SUCCESSED = 0
        const val RESPONSE_LOAD_MODEL_FAILED = 1
        const val RESPONSE_RUN_MODEL_SUCCESSED = 2
        const val RESPONSE_RUN_MODEL_FAILED = 3
    }
}