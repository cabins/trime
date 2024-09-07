// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.Keep
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.KeyValue
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.core.RimeProto
import com.osfans.trime.core.RimeResponse
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.db.DraftHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.broadcast.IntentReceiver
import com.osfans.trime.ime.enums.FullscreenMode
import com.osfans.trime.ime.enums.InlinePreeditMode
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.Event
import com.osfans.trime.ime.keyboard.InitializationUi
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ime.symbol.SymbolBoardType
import com.osfans.trime.ime.symbol.TabManager
import com.osfans.trime.ime.text.TextInputManager
import com.osfans.trime.util.ShortcutUtils
import com.osfans.trime.util.ShortcutUtils.openCategory
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.findSectionFrom
import com.osfans.trime.util.isLandscape
import com.osfans.trime.util.isNightMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.bitflags.hasFlag
import splitties.systemservices.inputMethodManager
import splitties.views.gravityBottom
import timber.log.Timber
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** [輸入法][InputMethodService]主程序  */

open class TrimeInputMethodService : LifecycleInputMethodService() {
    private lateinit var rime: RimeSession
    private val jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private var normalTextEditor = false
    private val prefs: AppPrefs
        get() = AppPrefs.defaultInstance()
    var inputView: InputView? = null
    private val commonKeyboardActionListener: CommonKeyboardActionListener?
        get() = inputView?.commonKeyboardActionListener
    private var initializationUi: InitializationUi? = null
    private var eventListeners = WeakHashSet<EventListener>()
    private var mIntentReceiver: IntentReceiver? = null
    private var isWindowShown = false // 键盘窗口是否已显示
    private var textInputManager: TextInputManager? = null // 文字输入管理器

    var shouldUpdateRimeOption = false

    var lastCommittedText: CharSequence = ""
        private set

    @Keep
    private val onColorChangeListener =
        ColorManager.OnColorChangeListener {
            lifecycleScope.launch(Dispatchers.Main) {
                recreateInputView()
                currentInputEditorInfo?.let { inputView?.startInput(it) }
            }
        }

    private fun postJob(
        ctx: CoroutineContext,
        scope: CoroutineScope,
        block: suspend () -> Unit,
    ): Job {
        val job = scope.launch(ctx, CoroutineStart.LAZY) { block() }
        jobs.trySend(job)
        return job
    }

    /**
     * Post a rime operation to [jobs] to be executed
     *
     * Unlike `rime.runOnReady` or `rime.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postRimeJob] ensures that operations are executed sequentially.
     */
    fun postRimeJob(
        ctx: CoroutineContext = EmptyCoroutineContext,
        block: suspend RimeApi.() -> Unit,
    ) = postJob(ctx, rime.lifecycleScope) { rime.runOnReady(block) }

    init {
        try {
            check(self == null) { "Trime is already initialized" }
            self = this
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (isWindowShown) {
            Timber.i("Ignoring (is already shown)")
            return
        } else {
            Timber.i("onWindowShown...")
        }
        postRimeJob {
            if (currentInputEditorInfo != null) {
                isWindowShown = true
                withContext(Dispatchers.Main) {
                    updateComposing()
                }
                for (listener in eventListeners) {
                    listener.onWindowShown()
                }
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (!isWindowShown) {
            Timber.d("Ignoring (window is already hidden)")
            return
        } else {
            Timber.d("onWindowHidden")
        }
        isWindowShown = false
        if (prefs.profile.syncBackgroundEnabled) {
            val msg = Message()
            msg.obj = this
            syncBackgroundHandler.sendMessageDelayed(msg, 5000) // 输入面板隐藏5秒后，开始后台同步
        }
        for (listener in eventListeners) {
            listener.onWindowHidden()
        }
    }

    private fun updateRimeOption(): Boolean {
        try {
            if (shouldUpdateRimeOption) {
                Rime.setOption("soft_cursor", prefs.keyboard.softCursorEnabled) // 軟光標
                Rime.setOption("_horizontal", ThemeManager.activeTheme.generalStyle.horizontal) // 水平模式
                shouldUpdateRimeOption = false
            }
        } catch (e: Exception) {
            Timber.e(e)
            return false
        }
        return true
    }

    /** 防止重启系统 强行停止应用时alarm任务丢失 */
    @SuppressLint("ScheduleExactAlarm")
    fun restartSystemStartTimingSync() {
        if (prefs.profile.timingSyncEnabled) {
            val triggerTime = prefs.profile.timingSyncTriggerTime
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

            /** 设置待发送的同步事件 */
            val pendingIntent =
                PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent("com.osfans.trime.timing.sync"),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    },
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // 根据SDK设置alarm任务
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }
    }

    override fun onCreate() {
        rime = RimeDaemon.createSession(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            rime.run { responseFlow }.collect {
                handleRimeResponse(it)
            }
        }
        super.onCreate()
        // MUST WRAP all code within Service onCreate() in try..catch to prevent any crash loops
        try {
            // Additional try..catch wrapper as the event listeners chain or the super.onCreate() method
            // could crash
            //  and lead to a crash loop
            Timber.d("onCreate")
            ColorManager.addOnChangedListener(onColorChangeListener)
            mIntentReceiver =
                IntentReceiver().also {
                    it.registerReceiver(this)
                }
            postRimeJob {
                Timber.d("Running Trime.onCreate")
                ColorManager.init(resources.configuration)
                textInputManager = TextInputManager(this@TrimeInputMethodService, rime)
                InputFeedbackManager.init()
                restartSystemStartTimingSync()
                shouldUpdateRimeOption = true
                try {
                    for (listener in eventListeners) {
                        listener.onCreate()
                    }
                } catch (e: Exception) {
                    Timber.e(e)
                }
                Timber.d("Trime.onCreate  completed")
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun handleRimeResponse(response: RimeResponse) {
        val (commit, ctx, _) = response
        if (commit != null && !commit.text.isNullOrEmpty()) {
            commitCharSequence(commit.text)
        }
        if (ctx != null) {
            updateComposingText(ctx)
        }
        updateComposing()
    }

    fun inputSymbol(text: String) {
        commonKeyboardActionListener?.listener?.onPress(KeyEvent.KEYCODE_UNKNOWN)
        if (Rime.isAsciiMode) Rime.setOption("ascii_mode", false)
        val asciiPunch = Rime.isAsciiPunch
        if (asciiPunch) Rime.setOption("ascii_punct", false)
        commonKeyboardActionListener?.listener?.onText("{Escape}$text")
        if (asciiPunch) Rime.setOption("ascii_punct", true)
        self!!.selectLiquidKeyboard(-1)
    }

    fun selectLiquidKeyboard(tabIndex: Int) {
        if (inputView == null) return
        if (tabIndex >= 0) {
            inputView!!.switchBoard(InputView.Board.Symbol)
            inputView!!.liquidKeyboard.select(tabIndex)
        } else {
            // 设置液体键盘处于隐藏状态
            TabManager.setTabExited()
            inputView!!.switchBoard(InputView.Board.Main)
            updateComposing()
        }
    }

    // 按键需要通过tab name来打开liquidKeyboard的指定tab
    fun selectLiquidKeyboard(name: String) {
        if (name.matches("-?\\d+".toRegex())) {
            selectLiquidKeyboard(name.toInt())
        } else if (name.matches("[A-Z]+".toRegex())) {
            selectLiquidKeyboard(SymbolBoardType.valueOf(name))
        } else {
            selectLiquidKeyboard(TabManager.tabTags.indexOfFirst { it.text == name })
        }
    }

    fun selectLiquidKeyboard(type: SymbolBoardType) {
        selectLiquidKeyboard(TabManager.tabTags.indexOfFirst { it.type == type })
    }

    fun pasteByChar() {
        commitTextByChar(checkNotNull(ShortcutUtils.pasteFromClipboard(this)).toString())
    }

    /** Must be called on the UI thread
     *
     * 重置鍵盤、候選條、狀態欄等 !!注意，如果其中調用Rime.setOption，切換方案會卡住  */
    fun recreateInputView() {
        inputView = InputView(this, rime)
        shouldUpdateRimeOption = true // 不能在Rime.onMessage中調用set_option，會卡死
        updateComposing() // 切換主題時刷新候選
        setInputView(inputView!!)
        initializationUi = null
    }

    override fun onDestroy() {
        mIntentReceiver?.unregisterReceiver(this)
        mIntentReceiver = null
        InputFeedbackManager.destroy()
        inputView = null
        for (listener in eventListeners) {
            listener.onDestroy()
        }
        eventListeners.clear()
        ColorManager.removeOnChangedListener(onColorChangeListener)
        super.onDestroy()
        RimeDaemon.destroySession(javaClass.name)
        self = null
    }

    private fun handleReturnKey() {
        currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                return
            }
            if (imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                val ic = currentInputConnection
                ic?.commitText("\n", 1)
                return
            }
            if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                currentInputConnection.performEditorAction(actionId)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> currentInputConnection.commitText("\n", 1)
                else -> currentInputConnection.performEditorAction(action)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        postRimeJob { clearComposition() }
        ColorManager.onSystemNightModeChange(newConfig.isNightMode())
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo) {
        inputView?.updateCursorAnchorInfo(cursorAnchorInfo)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        if (candidatesEnd != -1 && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            // 移動光標時，更新候選區
            if (newSelEnd in candidatesStart..<candidatesEnd) {
                val n = newSelEnd - candidatesStart
                Rime.setCaretPos(n)
                updateComposing()
            }
        }
        if (candidatesStart == -1 && candidatesEnd == -1 && newSelStart == 0 && newSelEnd == 0) {
            // 上屏後，清除候選區
            postRimeJob { clearComposition() }
        }
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    override fun onComputeInsets(outInsets: Insets) {
        val (_, y) =
            intArrayOf(0, 0).also {
                if (inputView?.keyboardView?.isVisible == true) {
                    inputView?.keyboardView?.getLocationInWindow(it)
                } else {
                    initializationUi?.initial?.getLocationInWindow(it)
                }
            }
        outInsets.apply {
            contentTopInsets = y
            touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
            touchableRegion.setEmpty()
            visibleTopInsets = y
        }
    }

    override fun onCreateInputView(): View {
        Timber.d("onCreateInputView")
        postRimeJob(Dispatchers.Main) {
            recreateInputView()
        }
        initializationUi = InitializationUi(this)
        return initializationUi!!.root
    }

    override fun setInputView(view: View) {
        val inputArea =
            window.window!!
                .decorView
                .findViewById<FrameLayout>(android.R.id.inputArea)
        inputArea.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        super.setInputView(view)
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(
        win: Window,
        isFullscreen: Boolean,
        isCandidatesOnly: Boolean,
    ) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onStartInput(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        Timber.d("onStartInput: restarting=$restarting")
        postRimeJob {
            if (restarting) {
                // when input restarts in the same editor, clear previous composition
                clearComposition()
            }
        }
    }

    override fun onStartInputView(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        Timber.d("onStartInputView: restarting=%s", restarting)
        postRimeJob(Dispatchers.Main) {
            InputFeedbackManager.loadSoundEffects(this@TrimeInputMethodService)
            InputFeedbackManager.resetPlayProgress()
            for (listener in eventListeners) {
                listener.onStartInputView(attribute, restarting)
            }
            if (prefs.other.showStatusBarIcon) {
                showStatusIcon(R.drawable.ic_trime_status) // 狀態欄圖標
            }
            setCandidatesViewShown(!rime.run { isEmpty() }) // 軟鍵盤出現時顯示候選欄
            inputView?.startInput(attribute, restarting)
            when (attribute.inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> {
                    Timber.d(
                        "EditorInfo: private;" +
                            " packageName=" +
                            attribute.packageName +
                            "; fieldName=" +
                            attribute.fieldName +
                            "; actionLabel=" +
                            attribute.actionLabel +
                            "; inputType=" +
                            attribute.inputType +
                            "; VARIATION=" +
                            (attribute.inputType and InputType.TYPE_MASK_VARIATION) +
                            "; CLASS=" +
                            (attribute.inputType and InputType.TYPE_MASK_CLASS) +
                            "; ACTION=" +
                            (attribute.imeOptions and EditorInfo.IME_MASK_ACTION),
                    )
                    normalTextEditor = false
                }

                else -> {
                    Timber.d(
                        "EditorInfo: normal;" +
                            " packageName=" +
                            attribute.packageName +
                            "; fieldName=" +
                            attribute.fieldName +
                            "; actionLabel=" +
                            attribute.actionLabel +
                            "; inputType=" +
                            attribute.inputType +
                            "; VARIATION=" +
                            (attribute.inputType and InputType.TYPE_MASK_VARIATION) +
                            "; CLASS=" +
                            (attribute.inputType and InputType.TYPE_MASK_CLASS) +
                            "; ACTION=" +
                            (attribute.imeOptions and EditorInfo.IME_MASK_ACTION),
                    )
                    if (attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        == EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    ) {
                        //  应用程求以隐身模式打开键盘应用程序
                        normalTextEditor = false
                        Timber.d("EditorInfo: normal -> private, IME_FLAG_NO_PERSONALIZED_LEARNING")
                    } else if (attribute.packageName == BuildConfig.APPLICATION_ID ||
                        prefs
                            .clipboard
                            .draftExcludeApp
                            .trim()
                            .split('\n')
                            .contains(attribute.packageName)
                    ) {
                        normalTextEditor = false
                        Timber.d("EditorInfo: normal -> exclude, packageName=%s", attribute.packageName)
                    } else {
                        normalTextEditor = true
                        DraftHelper.onInputEventChanged()
                    }
                }
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        postRimeJob {
            if (normalTextEditor) {
                DraftHelper.onInputEventChanged()
            }
            clearComposition()
        }
        InputFeedbackManager.finishInput()
        inputView?.finishInput()
    }

    // 直接commit不做任何处理
    fun commitCharSequence(
        text: CharSequence,
        clearMeatKeyState: Boolean = false,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        ic.commitText(text, 1)
        if (text.isNotEmpty()) {
            lastCommittedText = text
        }
        if (clearMeatKeyState) {
            ic.clearMetaKeyStates(KeyEvent.getModifierMetaStateMask())
            DraftHelper.onInputEventChanged()
        }
        return true
    }

    /**
     * Commit the current composing text together with the new text
     *
     * @param text the new text to be committed
     */
    fun commitText(text: String?) {
        currentInputConnection.finishComposingText()
        commitCharSequence(text!!, true)
    }

    private fun commitTextByChar(text: String) {
        for (char in text) {
            if (!commitCharSequence(char.toString(), false)) break
        }
    }

    /**
     * Constructs a meta state integer flag which can be used for setting the `metaState` field when sending a KeyEvent
     * to the input connection. If this method is called without a meta modifier set to true, the default value `0` is
     * returned.
     *
     * @param ctrl Set to true to enable the CTRL meta modifier. Defaults to false.
     * @param alt Set to true to enable the ALT meta modifier. Defaults to false.
     * @param shift Set to true to enable the SHIFT meta modifier. Defaults to false.
     *
     * @return An integer containing all meta flags passed and formatted for use in a [KeyEvent].
     */
    fun meta(
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        sym: Boolean = false,
    ): Int {
        var metaState = 0
        if (ctrl) {
            metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (alt) {
            metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        if (shift) {
            metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        if (meta) {
            metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        }
        if (sym) {
            metaState = metaState or KeyEvent.META_SYM_ON
        }

        return metaState
    }

    private fun sendDownKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    private fun sendUpKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    /**
     * Same as [InputMethodService.sendDownUpKeyEvents] but also allows to set meta state.
     *
     * @param keyEventCode The key code to send, use a key code defined in Android's [KeyEvent].
     * @param metaState Flags indicating which meta keys are currently pressed.
     * @param count How often the key is pressed while the meta keys passed are down. Must be greater than or equal to
     *  `1`, else this method will immediately return false.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun sendDownUpKeyEvent(
        keyEventCode: Int,
        metaState: Int = meta(),
        count: Int = 1,
    ): Boolean {
        if (count < 1) return false
        val ic = currentInputConnection ?: return false
        ic.clearMetaKeyStates(
            KeyEvent.META_FUNCTION_ON
                or KeyEvent.META_SHIFT_MASK
                or KeyEvent.META_ALT_MASK
                or KeyEvent.META_CTRL_MASK
                or KeyEvent.META_META_MASK
                or KeyEvent.META_SYM_ON,
        )
        ic.beginBatchEdit()
        val eventTime = SystemClock.uptimeMillis()
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }

        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }

        for (n in 0 until count) {
            sendDownKeyEvent(eventTime, keyEventCode, metaState)
            sendUpKeyEvent(eventTime, keyEventCode, metaState)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }

        if (metaState and KeyEvent.META_META_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }

        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }

        ic.endBatchEdit()
        return true
    }

    fun getActiveText(type: Int): String {
        if (type == 2) return Rime.getRimeRawInput() ?: "" // 當前編碼
        var s = Rime.composingText // 當前候選
        if (s.isEmpty()) {
            val ic = currentInputConnection
            var cs = ic?.getSelectedText(0) // 選中字
            if (type == 1 && cs.isNullOrEmpty()) cs = lastCommittedText // 剛上屏字
            if (cs.isNullOrEmpty() && ic != null) {
                cs = ic.getTextBeforeCursor(if (type == 4) 1024 else 1, 0) // 光標前字
            }
            if (cs.isNullOrEmpty() && ic != null) cs = ic.getTextAfterCursor(1024, 0) // 光標後面所有字
            if (cs != null) s = cs.toString()
        }
        return s
    }

    /**
     * 如果爲Back鍵[KeyEvent.KEYCODE_BACK]，則隱藏鍵盤
     *
     * @param keyCode 鍵碼[KeyEvent.getKeyCode]
     * @return 是否處理了Back鍵事件
     */
    private fun handleBack(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            requestHideSelf(0)
            return true
        }
        return false
    }

    private fun onRimeKey(event: IntArray): Boolean {
        updateRimeOption()
        // todo 改为异步处理按键事件、刷新UI
        return Rime.processKey(event[0], event[1])
    }

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        val modifiers = KeyModifiers.fromKeyEvent(event)
        val keyVal = KeyValue.fromKeyEvent(event)
        if (keyVal.value != RimeKeyMapping.RimeKey_VoidSymbol) {
            postRimeJob {
                processKey(keyVal, modifiers)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = forwardKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = forwardKeyEvent(event) || super.onKeyUp(keyCode, event)

    fun switchToPrevIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToLastInputMethod(window.window!!.attributes.token)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the previous IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun switchToNextIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToNextInputMethod(window.window!!.attributes.token, false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the next IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    // 处理键盘事件(Android keycode)
    fun handleKey(
        keyEventCode: Int,
        metaState: Int,
    ): Boolean { // 軟鍵盤
        commonKeyboardActionListener?.needSendUpRimeKey = false
        if (onRimeKey(Event.getRimeEvent(keyEventCode, metaState))) {
            // 如果输入法消费了按键事件，则需要释放按键
            commonKeyboardActionListener?.needSendUpRimeKey = true
            Timber.d(
                "\t<TrimeInput>\thandleKey()\trimeProcess, keycode=%d, metaState=%d",
                keyEventCode,
                metaState,
            )
        } else if (hookKeyboard(keyEventCode, metaState)) {
            Timber.d("\t<TrimeInput>\thandleKey()\thookKeyboard, keycode=%d", keyEventCode)
        } else if (performEnter(keyEventCode) || handleBack(keyEventCode)) {
            // 处理返回键（隐藏软键盘）和回车键（换行）
            // todo 确认是否有必要单独处理回车键？是否需要把back和escape全部占用？
            Timber.d("\t<TrimeInput>\thandleKey()\tEnterOrHide, keycode=%d", keyEventCode)
        } else if (openCategory(keyEventCode)) {
            // 打开系统默认应用
            Timber.d("\t<TrimeInput>\thandleKey()\topenCategory keycode=%d", keyEventCode)
        } else {
            commonKeyboardActionListener?.needSendUpRimeKey = true
            Timber.d(
                "\t<TrimeInput>\thandleKey()\treturn FALSE, keycode=%d, metaState=%d",
                keyEventCode,
                metaState,
            )
            return false
        }
        return true
    }

    fun shareText(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ic = currentInputConnection ?: return false
            val cs = ic.getSelectedText(0)
            if (cs == null) ic.performContextMenuAction(android.R.id.selectAll)
            return ic.performContextMenuAction(android.R.id.shareText)
        }
        return false
    }

    /** 編輯操作 */
    private fun hookKeyboard(
        code: Int,
        mask: Int,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        // 没按下 Ctrl 键
        if (mask != KeyEvent.META_CTRL_ON) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (prefs.keyboard.hookCtrlZY) {
                when (code) {
                    KeyEvent.KEYCODE_Y -> return ic.performContextMenuAction(android.R.id.redo)
                    KeyEvent.KEYCODE_Z -> return ic.performContextMenuAction(android.R.id.undo)
                }
            }
        }

        when (code) {
            KeyEvent.KEYCODE_A -> {
                // 全选
                return if (prefs.keyboard.hookCtrlA) ic.performContextMenuAction(android.R.id.selectAll) else false
            }

            KeyEvent.KEYCODE_X -> {
                // 剪切
                if (prefs.keyboard.hookCtrlCV) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionEnd - et.selectionStart > 0) return ic.performContextMenuAction(android.R.id.cut)
                    }
                }
                Timber.w("hookKeyboard cut fail")
                return false
            }

            KeyEvent.KEYCODE_C -> {
                // 复制
                if (prefs.keyboard.hookCtrlCV) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionEnd - et.selectionStart > 0) return ic.performContextMenuAction(android.R.id.copy)
                    }
                }
                Timber.w("hookKeyboard copy fail")
                return false
            }

            KeyEvent.KEYCODE_V -> {
                // 粘贴
                if (prefs.keyboard.hookCtrlCV) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et == null) {
                        Timber.d("hookKeyboard paste, et == null, try commitText")
                        if (ic.commitText(ShortcutUtils.pasteFromClipboard(this), 1)) {
                            return true
                        }
                    } else if (ic.performContextMenuAction(android.R.id.paste)) {
                        return true
                    }
                    Timber.w("hookKeyboard paste fail")
                }
                return false
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (prefs.keyboard.hookCtrlLR) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionEnd)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (prefs.keyboard.hookCtrlLR) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionStart, true)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
        }
        return false
    }

    private fun updateComposingText(ctx: RimeProto.Context) {
        val ic = currentInputConnection ?: return
        val text =
            when (prefs.keyboard.inlinePreedit) {
                InlinePreeditMode.PREVIEW -> ctx.composition.commitTextPreview ?: ""
                InlinePreeditMode.COMPOSITION -> ctx.composition.preedit ?: ""
                InlinePreeditMode.INPUT -> ctx.input
                InlinePreeditMode.NONE -> ""
            }
        if (ic.getSelectedText(0).isNullOrEmpty() || text.isNotEmpty()) {
            ic.setComposingText(text, 1)
        }
    }

    /** 更新Rime的中西文狀態、編輯區文本  */
    fun updateComposing() {
        inputView?.updateComposing(currentInputConnection)
        if (!onEvaluateInputViewShown()) setCandidatesViewShown(textInputManager!!.isComposable) // 實體鍵盤打字時顯示候選欄
    }

    /**
     * 如果爲回車鍵[KeyEvent.KEYCODE_ENTER]，則換行
     *
     * @param keyCode 鍵碼[KeyEvent.getKeyCode]
     * @return 是否處理了回車事件
     */
    private fun performEnter(keyCode: Int): Boolean { // 回車
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            DraftHelper.onInputEventChanged()
            handleReturnKey()
            return true
        }
        return false
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        val config = resources.configuration
        if (config == null || !resources.configuration.isLandscape()) return false
        return when (prefs.keyboard.fullscreenMode) {
            FullscreenMode.AUTO_SHOW -> {
                Timber.d("FullScreen: Auto")
                val ei = currentInputEditorInfo
                if (ei != null && ei.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0) {
                    return false
                }
                Timber.d("FullScreen: Always")
                true
            }

            FullscreenMode.ALWAYS_SHOW -> {
                Timber.d("FullScreen: Always")
                true
            }

            FullscreenMode.NEVER_SHOW -> {
                Timber.d("FullScreen: Never")
                false
            }
        }
    }

    override fun updateFullscreenMode() {
        super.updateFullscreenMode()
        updateSoftInputWindowLayoutParameters()
    }

    /** Updates the layout params of the window and input view.  */
    private fun updateSoftInputWindowLayoutParameters() {
        val w = window.window ?: return
        if (inputView != null) {
            val layoutHeight =
                if (isFullscreenMode) {
                    WindowManager.LayoutParams.WRAP_CONTENT
                } else {
                    WindowManager.LayoutParams.MATCH_PARENT
                }
            val inputArea = w.decorView.findViewById<FrameLayout>(android.R.id.inputArea)
            inputArea.updateLayoutParams {
                height = layoutHeight
                if (this is FrameLayout.LayoutParams) {
                    this.gravity = inputArea.gravityBottom
                } else if (this is LinearLayout.LayoutParams) {
                    this.gravity = inputArea.gravityBottom
                }
            }
            inputView?.updateLayoutParams {
                height = layoutHeight
            }
        }
    }

    fun addEventListener(listener: EventListener): Boolean = eventListeners.add(listener)

    fun removeEventListener(listener: EventListener): Boolean = eventListeners.remove(listener)

    interface EventListener {
        fun onCreate() {}

        fun onDestroy() {}

        fun onStartInputView(
            info: EditorInfo,
            restarting: Boolean,
        ) {}

        fun onWindowShown() {}

        fun onWindowHidden() {}
    }

    companion object {
        var self: TrimeInputMethodService? = null

        @JvmStatic
        fun getService(): TrimeInputMethodService = self ?: throw IllegalStateException("Trime not initialized")

        fun getServiceOrNull(): TrimeInputMethodService? = self

        private val syncBackgroundHandler =
            Handler(
                Looper.getMainLooper(),
            ) { msg: Message ->
                // 若当前没有输入面板，则后台同步。防止面板关闭后5秒内再次打开
                val service = msg.obj as TrimeInputMethodService
                if (!service.isShowInputRequested) {
                    ShortcutUtils.syncInBackground()
                    service.shouldUpdateRimeOption = true
                }
                false
            }
    }
}
