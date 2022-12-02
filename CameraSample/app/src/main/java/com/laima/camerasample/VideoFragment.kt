package com.laima.camerasample

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.res.Configuration
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenCreated
import androidx.navigation.NavController
import androidx.navigation.Navigation
import com.laima.camerasample.databinding.FragmentCameraBinding
import com.laima.camerasample.databinding.FragmentVideoBinding
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class VideoFragment : Fragment() {

    // View
    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!
    private val captureLiveStatus = MutableLiveData<String>()

    // 화질 선택
    private var enumerationDeferred: Deferred<Unit>? = null

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    private val cameraCapabilities = mutableListOf<CameraCapability>()

    // Camera UI  states and inputs
    enum class UiState {
        IDLE,       // Not recording, all UI controls are active.
        RECORDING,  // Camera is recording, only display Pause/Resume & Stop button.
        FINALIZED,  // Recording just completes, disable all RECORDING UI controls.
        RECOVERY    // For future use.
    }

    private suspend fun bindCaptureUsecase() {
        val cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

        val cameraSelector = getCameraSelector(cameraIndex)

        val quality = cameraCapabilities[cameraIndex].qualities[qualityIndex]
        val qualitySelector = QualitySelector.from(quality)

//        binding.viewFinder.updateLayoutParams<ConstraintLayout.LayoutParams> {
//            val orientation = this@VideoFragment.resources.configuration.orientation
//            dimensionRatio = quality.getAspectRatioString(quality,
//                (orientation == Configuration.ORIENTATION_PORTRAIT))
//        }

        val preview = Preview.Builder()
            .setTargetAspectRatio(quality.getAspectRatio(quality))
            .build()
            .apply {
                setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                videoCapture,
                preview
            )
        } catch (e : Exception) {
            e.printStackTrace()

            resetUIandState("bindToLifecycle failed")
        }

        enableUI(true)
    }

    init {
        // Camera 있는지 확인만 하고 리스트에 넣어줌
        enumerationDeferred = lifecycleScope.async {
            whenCreated {
                val provider = ProcessCameraProvider.getInstance(requireContext()).await()

                provider.unbindAll()
                for (cameraSelector in arrayOf(
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    CameraSelector.DEFAULT_FRONT_CAMERA
                )) {
                    try {
                        // just get the camera.cameraInfo to query capabilities
                        // we are not binding anything here.
                        if (provider.hasCamera(cameraSelector)) {
                            val camera = provider.bindToLifecycle(requireActivity(), cameraSelector)
                            QualitySelector
                                .getSupportedQualities(camera.cameraInfo)
                                .filter {
                                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                                        .contains(it)
                                }.also {
                                    cameraCapabilities.add(CameraCapability(cameraSelector, it))
                                }
                        }

                    } catch (e : java.lang.Exception) {
                        e.printStackTrace()
                    }

                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentVideoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCameraFragment()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /**
     * Retrieve the asked camera's type(lens facing type). In this sample, only 2 types:
     *   idx is even number:  CameraSelector.LENS_FACING_BACK
     *          odd number:   CameraSelector.LENS_FACING_FRONT
     */
    private fun getCameraSelector(idx: Int) : CameraSelector {
        if (cameraCapabilities.size == 0) {
            Log.i("[jw]", "Error: This device does not have any camera, bailing out")
            requireActivity().finish()
        }
        return (cameraCapabilities[idx % cameraCapabilities.size].cameraSelector)
    }

    data class CameraCapability(val cameraSelector: CameraSelector, val qualities: List<Quality>)


    companion object {
        // default Quality selection if no input from UI
        const val DEFAULT_QUALITY_IDX = 0
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    private var cameraIndex = 0
    private var qualityIndex = DEFAULT_QUALITY_IDX
    private var audioEnabled = false

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordingState: VideoRecordEvent


    /**
     * One time initialize for CameraFragment (as a part of fragment layout's creation process).
     * This function performs the following:
     *   - initialize but disable all UI controls except the Quality selection.
     *   - set up the Quality selection recycler view.
     *   - bind use cases to a lifecycle camera, enable UI controls.
     */
    private fun initCameraFragment() {
        initializeUI()
        viewLifecycleOwner.lifecycleScope.launch {
            if (enumerationDeferred != null) {
                enumerationDeferred!!.await()
                enumerationDeferred = null
            }

            bindCaptureUsecase()
        }
    }

    /**
     * Initialize UI. Preview and Capture actions are configured in this function.
     * Note that preview and capture are both initialized either by UI or CameraX callbacks
     * (except the very 1st time upon entering to this fragment in onCreateView()
     */
    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeUI() {
        binding.reverseCameraLens.apply {
            setOnClickListener {
                cameraIndex = (cameraIndex + 1) % cameraCapabilities.size
                // camera device change is in effect instantly:
                //   - reset quality selection
                //   - restart preview
                qualityIndex = DEFAULT_QUALITY_IDX
                enableUI(false)
                viewLifecycleOwner.lifecycleScope.launch {
                    bindCaptureUsecase()
                }
            }
            isEnabled = false
        }

        // React to user touching the capture button
        binding.captureButton.apply {
            setOnClickListener {
                if (!this@VideoFragment::recordingState.isInitialized ||
                    recordingState is VideoRecordEvent.Finalize)
                {
                    enableUI(false)  // Our eventListener will turn on the Recording UI.
                    startRecording()
                } else {
                    when (recordingState) {
                        is VideoRecordEvent.Start -> {
                            currentRecording?.pause()
                            binding.stopButton.visibility = View.VISIBLE
                        }
                        is VideoRecordEvent.Pause -> currentRecording?.resume()
                        is VideoRecordEvent.Resume -> currentRecording?.pause()
                        else -> throw IllegalStateException("recordingState in unknown state")
                    }
                }
            }
            isEnabled = false
        }

        binding.stopButton.apply {
            setOnClickListener {
                // stopping: hide it after getting a click before we go to viewing fragment
                binding.stopButton.visibility = View.INVISIBLE
                if (currentRecording == null || recordingState is VideoRecordEvent.Finalize) {
                    return@setOnClickListener
                }

                val recording = currentRecording
                if (recording != null) {
                    recording.stop()
                    currentRecording = null
                }
                binding.captureButton.setImageResource(R.drawable.ic_start)
            }
            // ensure the stop button is initialized disabled & invisible
            visibility = View.INVISIBLE
            isEnabled = false
        }
        captureLiveStatus.observe(viewLifecycleOwner) {
            binding.timer.apply {
                post { text = it }
            }
        }
    }

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }
    /**
     * Kick start the video recording
     *   - config Recorder to capture to MediaStoreOutput
     *   - register RecordEvent Listener
     *   - apply audio request from user
     *   - start recording!
     * After this function, user could start/pause/resume/stop recording and application listens
     * to VideoRecordEvent for the current recording status.
     */
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        // create MediaStoreOutputOptions for our recorder: resulting our recording!
        val name = "CameraX-recording-" +
                SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            requireActivity().contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        // configure Recorder and Start recording to the mediaStoreOutput.
        currentRecording = videoCapture.output
            .prepareRecording(requireActivity(), mediaStoreOutput)
            .apply { if (audioEnabled) withAudioEnabled() }
            .start(mainThreadExecutor, captureListener)

    }

    /**
     * CaptureEvent listener.
     */
    private val captureListener = Consumer<VideoRecordEvent> { event ->
        // cache the recording state
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        updateUI(event)

//        if (event is VideoRecordEvent.Finalize) {
//            // display the captured video
//            lifecycleScope.launch {
//                navController.navigate(
//                    CaptureFragmentDirections.actionCaptureToVideoViewer(
//                        event.outputResults.outputUri
//                    )
//                )
//            }
//        }
    }

    /**
     * UpdateUI according to CameraX VideoRecordEvent type:
     *   - user starts capture.
     *   - this app disables all UI selections.
     *   - this app enables capture run-time UI (pause/resume/stop).
     *   - user controls recording with run-time UI, eventually tap "stop" to end.
     *   - this app informs CameraX recording to stop with recording.stop() (or recording.close()).
     *   - CameraX notify this app that the recording is indeed stopped, with the Finalize event.
     *   - this app starts VideoViewer fragment to view the captured result.
     */
    private fun updateUI(event: VideoRecordEvent) {
        val state = if (event is VideoRecordEvent.Status) recordingState.getNameString()
                    else event.getNameString()
        when (event) {
            is VideoRecordEvent.Status -> {
                // placeholder: we update the UI with new status after this when() block,
                // nothing needs to do here.
            }
            is VideoRecordEvent.Start -> {
                showUI(UiState.RECORDING, event.getNameString())
            }
            is VideoRecordEvent.Finalize-> {
                showUI(UiState.FINALIZED, event.getNameString())
            }
        }

        val stats = event.recordingStats
        val size = stats.numBytesRecorded / 1000

        val timeHours = java.util.concurrent.TimeUnit
            .NANOSECONDS
            .toHours(stats.recordedDurationNanos).let {
                if ((it%60) < 10)
                    "0${it%60}"
                else
                    it%60
            }

        val timeSeconds = java.util.concurrent.TimeUnit
            .NANOSECONDS
            .toSeconds(stats.recordedDurationNanos).let {
            if ((it%60) < 10)
                "0${it%60}"
            else
                it%60
        }

        val timeMinutes = java.util.concurrent.TimeUnit
            .NANOSECONDS
            .toMinutes(stats.recordedDurationNanos).let {
                if ((it%60) < 10)
                    "0${it%60}"
                else
                    it%60
            }

        var text = "$timeHours : $timeMinutes : $timeSeconds"
        if(event is VideoRecordEvent.Finalize)
            text = "${text}\nFile saved to: ${event.outputResults.outputUri}"

        captureLiveStatus.value = text
    }

    /**
     * Enable/disable UI:
     *    User could select the capture parameters when recording is not in session
     *    Once recording is started, need to disable able UI to avoid conflict.
     */
    private fun enableUI(enable: Boolean) {
        arrayOf(binding.reverseCameraLens,
            binding.captureButton,
            binding.stopButton,
            binding.modeVideo,
            binding.modePicture).forEach {
            it.isEnabled = enable
        }
        // disable the camera button if no device to switch
        if (cameraCapabilities.size <= 1) {
            binding.reverseCameraLens.isEnabled = false
        }
        // disable the resolution list if no resolution to switch
//        if (cameraCapabilities[cameraIndex].qualities.size <= 1) {
//            binding.qualitySelection.apply { isEnabled = false }
//        }
    }



    /**
     * ResetUI (restart):
     *    in case binding failed, let's give it another change for re-try. In future cases
     *    we might fail and user get notified on the status
     */
    private fun resetUIandState(reason: String) {
        enableUI(true)
        showUI(UiState.IDLE, reason)

        cameraIndex = 0
        qualityIndex = DEFAULT_QUALITY_IDX
        audioEnabled = false
    }

    /**
     * initialize UI for recording:
     *  - at recording: hide audio, qualitySelection,change camera UI; enable stop button
     *  - otherwise: show all except the stop button
     */
    private fun showUI(state: UiState, status:String = "idle") {
        binding.let {
            when(state) {
                UiState.IDLE -> {
                    it.captureButton.setImageResource(R.drawable.ic_start)
                    it.stopButton.visibility = View.INVISIBLE
                }
                UiState.RECORDING -> {
                    it.captureButton.setImageResource(R.drawable.ic_stop)
                    it.captureButton.isEnabled = true
                    it.stopButton.visibility = View.VISIBLE
                    it.stopButton.isEnabled = true
                }
                UiState.FINALIZED -> {
                    it.captureButton.setImageResource(R.drawable.ic_start)
                    it.stopButton.visibility = View.INVISIBLE
                }
                else -> {
                    val errorMsg = "Error: showUI($state) is not supported"
                    return
                }
            }
        }
    }


}
/**
 * A helper extended function to get the name(string) for the VideoRecordEvent.
 */
fun VideoRecordEvent.getNameString() : String {
    return when (this) {
        is VideoRecordEvent.Status -> "Status"
        is VideoRecordEvent.Start -> "Started"
        is VideoRecordEvent.Finalize-> "Finalized"
        is VideoRecordEvent.Pause -> "Paused"
        is VideoRecordEvent.Resume -> "Resumed"
        else -> throw IllegalArgumentException("Unknown VideoRecordEvent: $this")
    }
}


/**
 * Get the name (a string) from the given Video.Quality object.
 */
fun Quality.getNameString() :String {
    return when (this) {
        Quality.UHD -> "QUALITY_UHD(2160p)"
        Quality.FHD -> "QUALITY_FHD(1080p)"
        Quality.HD -> "QUALITY_HD(720p)"
        Quality.SD -> "QUALITY_SD(480p)"
        else -> throw IllegalArgumentException("Quality $this is NOT supported")
    }
}

/**
 * a helper function to retrieve the aspect ratio from a QualitySelector enum.
 */
fun Quality.getAspectRatio(quality: Quality): Int {
    return when {
        arrayOf(Quality.UHD, Quality.FHD, Quality.HD)
            .contains(quality)   -> AspectRatio.RATIO_16_9
        (quality ==  Quality.SD) -> AspectRatio.RATIO_4_3
        else -> throw UnsupportedOperationException()
    }
}

/**
 * a helper function to retrieve the aspect ratio string from a Quality enum.
 */
fun Quality.getAspectRatioString(quality: Quality, portraitMode:Boolean) :String {
    val hdQualities = arrayOf(Quality.UHD, Quality.FHD, Quality.HD)
    val ratio =
        when {
            hdQualities.contains(quality) -> Pair(16, 9)
            quality == Quality.SD         -> Pair(4, 3)
            else -> throw UnsupportedOperationException()
        }

    return if (portraitMode) "V,${ratio.second}:${ratio.first}"
    else "H,${ratio.first}:${ratio.second}"
}