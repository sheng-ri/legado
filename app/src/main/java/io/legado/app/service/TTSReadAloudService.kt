package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.ensureActive

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        initTts()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                play()
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        if (!ttsInitFinish) return
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakJob?.cancel()
        speakJob = execute {
            val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
                for (i in parIndex until contentList.size) {
                    ensureActive()
                    val text = contentList[i]
                    if (text.matches(AppPattern.notReadAloudRegex)) {
                        continue
                    }
                    if (i == 0) {
                        val result = tts.runCatching {
                            speak(text, TextToSpeech.QUEUE_FLUSH, null, AppConst.APP_TAG + i)
                        }.getOrElse {
                            AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                            TextToSpeech.ERROR
                        }
                        if (result == TextToSpeech.ERROR) {
                            AppLog.put("tts出错 尝试重新初始化")
                        }
                    } else {
                        val result = tts.runCatching {
                            speak(text, TextToSpeech.QUEUE_ADD, null, AppConst.APP_TAG + i)
                        }.getOrElse {
                            AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                            TextToSpeech.ERROR
                        }
                        if (result == TextToSpeech.ERROR) {
                            AppLog.put("tts朗读出错:$text")
                        }
                    }
                }
            LogUtils.d(TAG, "朗读内容添加完成")
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    override fun playStop() {
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
//            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
//            textChapter?.let {
//                if (readAloudNumber + 1 > it.getReadLength(pageIndex + 1)) {
//                    pageIndex++
//                    ReadBook.moveToNextPage()
//                }
//                upTtsProgress(readAloudNumber + 1)
//            }
            // TODO: 下一页功能
            run {
                if (parIndex + 1 > textChapter!!.getReadLength(pageIndex + 1)) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                }
                upTtsProgress(parIndex + 1)
            }
        }

        override fun onDone(s: String) {
            // TODO: 下一页功能
            LogUtils.d(TAG, "onDone utteranceId:$s")
            nextParagraph()
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            // TODO: Unknown
            super.onRangeStart(utteranceId, start, end, frame)
//            val msg =
//                "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
//            LogUtils.d(TAG, msg)
//            textChapter?.let {
//                if (readAloudNumber + start > it.getReadLength(pageIndex + 1)) {
//                    pageIndex++
//                    ReadBook.moveToNextPage()
//                    upTtsProgress(readAloudNumber + start)
//                }
//            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
//            LogUtils.d(
//                TAG,
//                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
//            )
            nextParagraph()
        }

        private fun nextParagraph() {
            //跳过全标点段落
            run {
                do {
                    parIndex++
                    if (parIndex >= contentList.size) {
                        nextChapter()
                        return
                    }
                } while (needSkipPar())
            }

        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
//            LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
            nextParagraph()
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}