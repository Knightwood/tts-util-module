@file:Suppress(
    "SpellCheckingInspection"
)

package com.kiylx.ttsmodule

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.*

/**
 * 使用：
 * ```
 *viewLifecycleOwner.lifecycleScope.launch {
 *      val tts = TTSHolder.getTTS(app = requireActivity().application)
 *      tts.initTTS { //初始化后
 *          if (it == TextToSpeech.SUCCESS) {
 *              //使用此方法开始解析
 *              tts.parseText("你好", "hello", asyncTaskObserver)
 *          }
 *      }
 *}
 *```
 */
interface ITTS {
    infix fun initTTS(block: (status: Int) -> Unit)
    fun releaseTTS()

    //这里是使用的开端
    fun parseText(text: String, textSourceDescription: String, taskObserver: TaskObserver)
    fun parseFile(
        uri: Uri,
        displayName: String,
        outDirectory: Directory,
        waveFilename: String,
        taskObserver: TaskObserver
    )

    fun openSystemTTSSettings(ctx: Context)

}

class TTSHolder(var app: Application) : OnInitListener,
    CoroutineScope by MainScope() + CoroutineName("TTS_SCOPE"), ITTS {
    private var ttsInitialized: Boolean = false
    private var tts: TextToSpeech? = null
    private var ttsInitErrorMessage: String? = null

    //保留最新的task，缓存1
    private val taskQueue = MutableSharedFlow<Task>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_LATEST
    )
    private var processTaskJob: Job? = null

    override infix fun initTTS(block: (status: Int) -> Unit) {
        if (this.tts != null && this.ttsInitialized) {
            block(TextToSpeech.SUCCESS)
        } else {
//            LogUtils.dTag(TAG, "INIT及处理...")
            processTask()

            ttsInitialized = false
            ttsInitErrorMessage = null
            // Prepare the OnInitListener.
            val wrappedListener = OnInitListener { status ->
                this.onInit(status)
                block(status)
                if (!ttsInitialized) releaseTTS()
            }

            // Display a message to the user.
            ToastUtils.showShort(R.string.tts_initializing_message)

            // Begin text-to-speech initialization.
            tts = TextToSpeech(app, wrappedListener)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS && this.tts != null) {
            tts?.let {
                it.setPitch(1.3f)
                it.setSpeechRate(1f)
//                it.language = Locale.CHINESE

                //check language setting
                val lang =
                    it.checkLanguages(listOf(Locale.CHINA, Locale.CHINESE, Locale.getDefault()))
                if (lang == null) {
                    val message = app.getString(R.string.no_tts_language_available_msg)
                    ttsInitErrorMessage = message
                    ToastUtils.showShort(message)
                    releaseTTS()
                    return
                } else {
                    it.language = lang
                }
                ttsInitialized = true
            }
        } else {
            val engines = tts?.engines ?: listOf()
            val messageId = if (engines.isEmpty()) {
                // No usable TTS engines.
                R.string.no_engine_available_message
            } else {
                // General TTS initialisation failure.
                R.string.tts_initialization_failure_msg
            }
            ttsInitErrorMessage = app.getString(messageId)
            ToastUtils.showLong(messageId)
            openSystemTTSSettings(app)
        }
    }

    override fun releaseTTS() {
        tts?.stop()
        tts?.shutdown()
        tts = null

        processTaskJob?.cancel()

        ttsInitialized = false

        // Release audio focus.
        releaseAudioFocus()
    }

    private fun processTask() {
        stopTask()
        processTaskJob = launch {
            taskQueue.collect {
//                LogUtils.dTag(TAG, "开始处理，订阅数量：${taskQueue.subscriptionCount.value}")
                if (tts != null) {
                    val result = it.begin()
                    handleTaskResult(result)
                }
            }
        }

    }

    @Synchronized
    fun stopTask(): Boolean {
        val tts = tts ?: return true
        // Interrupt TTS synthesis.
        val success = tts.stop() == 0
        processTaskJob?.cancel()
        processTaskJob = null
        return success
    }

    //这里是使用的开端
    /**
     * @param text 文本数据
     * @param textSourceDescription 文本的描述
     */
    override fun parseText(
        text: String,
        textSourceDescription: String,
        taskObserver: TaskObserver
    ) {
        tts?.let {
            // Read text and handle the result.
            val inputSource = InputSource.CharSequence(text, textSourceDescription)
            // Initialize the task and add it to the queue.
            val task = ReadInputTask(
                app, it, inputSource, taskObserver,
                TextToSpeech.QUEUE_ADD
            )
            submitTask(task)
        }
    }

    override fun parseFile(
        uri: Uri,
        displayName: String,
        outDirectory: Directory,
        waveFilename: String,
        taskObserver: TaskObserver
    ) {
        tts?.let {
            val inputSource = InputSource.DocumentUri(uri, displayName)

            // Initialise the mutable list of files to be populated by the first task
            // and processed by the second.
            val inWaveFiles = mutableListOf<File>()
            val task = FileSynthesisTask(
                app, it, inputSource, taskObserver,
                outDirectory, waveFilename, inWaveFiles
            )
            submitTask(task)
        }
    }

    override fun openSystemTTSSettings(ctx: Context) {
        // Got this from: https://stackoverflow.com/a/8688354
        val intent = Intent()
        intent.action = "com.android.settings.TTS_SETTINGS"
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        ctx.startActivity(intent)
    }

    private suspend fun handleTaskResult(result: Int) {
        val message: String = when (result) {
            TTS_NOT_READY -> {
                val defaultMessage = app.getString(R.string.tts_not_ready_message)
                val errorMessage = ttsInitErrorMessage
                errorMessage ?: defaultMessage
            }
            UNAVAILABLE_OUT_DIR -> {
                app.getString(R.string.unavailable_out_dir_message)
            }
            UNAVAILABLE_INPUT_SRC -> {
                app.getString(R.string.unavailable_input_src_message)
            }
            UNWRITABLE_OUT_DIR -> {
                app.getString(R.string.unwritable_out_dir_message)
            }
            ZERO_LENGTH_INPUT -> {
                taskQueue.first().getZeroLengthInputMessage(app)
            }
            else -> {
                ""
            }
        }

        // Display the message, if appropriate.
        if (message.isNotEmpty()) {
            ToastUtils.showShort(message)
        }
    }


    private fun submitTask(task: Task) {
        if (this.ttsInitialized) {
            taskQueue.tryEmit(task)
        } else {
            //LogUtils.dTag(TAG,"提交任务失败...")
            ToastUtils.showShort(R.string.engine_init_failed_message)
        }
    }

    //音频
    private val audioManager: AudioManager by lazy {
        app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val audioFocusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    private val audioFocusRequest: AudioFocusRequest by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()
            AudioFocusRequest.Builder(audioFocusGain)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(onAudioFocusChangeListener)
                .build()
        } else
            throw RuntimeException("should not use AudioFocusRequest below SDK v26")
    }

    private val onAudioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> stopTask()
            }
        }

    fun requestAudioFocus(): Boolean {
        val audioManager = audioManager
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(audioFocusRequest)
        } else {
            @Suppress("deprecation")
            audioManager.requestAudioFocus(
                onAudioFocusChangeListener, AudioManager.STREAM_MUSIC,
                audioFocusGain
            )
        }

        // Check the success value.
        return success == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    //释放音频焦点
    fun releaseAudioFocus() {
        // Abandon the audio focus using the same context and
        // AudioFocusChangeListener used to request it.
        val audioManager = audioManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("deprecation")
            audioManager.abandonAudioFocus(onAudioFocusChangeListener)
        } else {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        }
    }

    companion object {
        const val TAG = "tty1-TTS"

        @Volatile
        private var instance: TTSHolder? = null

        @MaybeNoInit
        fun getTTS(): TTSHolder {
            return instance!!
        }

        fun getTTS(app: Application): ITTS {
            instance ?: synchronized(this) {
                instance ?: TTSHolder(app).also { instance = it }
            }
            return instance!!
        }

    }
}

@RequiresOptIn("使用此方法前确保目标已经被初始化", RequiresOptIn.Level.WARNING)
annotation class MaybeNoInit
