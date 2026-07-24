package com.example.simpleaudioplayer

import android.content.Intent
import android.content.SharedPreferences
import android.media.PlaybackParams
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.simpleaudioplayer.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaPlayer: MediaPlayer? = null
    private var currentUri: Uri? = null
    private var currentSpeed: Float = 1.0f
    private var isUserSeeking = false

    private var sleepTimer: CountDownTimer? = null
    private var sleepTimerMillisLeft: Long = 0

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var progressRunnable: Runnable

    // خيارات السرعة المتاحة (تصل حتى 3x)
    private val speedOptions = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "audio_player_prefs"
        private const val KEY_LAST_URI = "last_uri"
        private const val KEY_POSITION_PREFIX = "position_" // + uri string
    }

    private val openDocumentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> loadAudio(uri, resumeFromSaved = true) }
        }
    }

    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // اقبل عدة أنواع صوت لتفادي إخفاء بعض الملفات بسبب اختلاف نوع mimeType
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "audio/*",
                    "application/ogg",
                    "application/x-ogg",
                    "application/x-flac"
                )
            )
            // حاول فتح المتصفح مباشرة على التخزين الداخلي بدل تبويب "الأخيرة" الفارغ
            try {
                val initialUri = DocumentsContract.buildRootUri(
                    "com.android.externalstorage.documents", "primary"
                )
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            } catch (e: Exception) {
                // تجاهل إن لم يدعمه الجهاز
            }
        }
        openDocumentLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setupSpeedSpinner()
        setupButtons()
        setupSeekBar()

        // إذا تم فتح التطبيق من ملف صوتي مباشرة (عبر "فتح باستخدام")
        if (handleIncomingIntent(intent)) {
            return
        }

        // وإلا، أعد فتح آخر ملف تم تشغيله تلقائيًا إن وجد
        val lastUriString = prefs.getString(KEY_LAST_URI, null)
        if (lastUriString != null) {
            try {
                val uri = Uri.parse(lastUriString)
                loadAudio(uri, resumeFromSaved = true)
            } catch (e: Exception) {
                // تجاهل إذا تعذر فتح الملف السابق (قد يكون محذوفًا)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** يتعامل مع فتح التطبيق مباشرة من ملف صوتي في تطبيق آخر (مدير ملفات، إلخ). يعيد true إذا تم فتح ملف. */
    private fun handleIncomingIntent(intent: Intent?): Boolean {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            loadAudio(intent.data!!, resumeFromSaved = true)
            return true
        }
        return false
    }

    private fun setupSpeedSpinner() {
        val labels = speedOptions.map { formatSpeedLabel(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSpeed.adapter = adapter
        // القيمة الافتراضية 1x
        binding.spinnerSpeed.setSelection(speedOptions.indexOf(1.0f))

        binding.spinnerSpeed.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                currentSpeed = speedOptions[position]
                applyPlaybackSpeed()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun formatSpeedLabel(speed: Float): String {
        return if (speed == speed.toLong().toFloat()) {
            "${speed.toLong()}x"
        } else {
            "${speed}x"
        }
    }

    private fun setupButtons() {
        binding.btnChooseFile.setOnClickListener {
            launchFilePicker()
        }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        binding.btnRewind.setOnClickListener {
            seekBy(-10_000)
        }

        binding.btnForward.setOnClickListener {
            seekBy(10_000)
        }

        binding.btnSleepTimer.setOnClickListener {
            showSleepTimerDialog()
        }
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let { mp ->
                        val duration = mp.duration
                        if (duration > 0) {
                            binding.tvCurrentTime.text = formatTime((progress.toLong() * duration) / 1000)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                mediaPlayer?.let { mp ->
                    val duration = mp.duration
                    if (duration > 0) {
                        val newPos = ((seekBar?.progress ?: 0).toLong() * duration / 1000).toInt()
                        mp.seekTo(newPos)
                    }
                }
            }
        })
    }

    private fun loadAudio(uri: Uri, resumeFromSaved: Boolean) {
        try {
            // احتفظ بصلاحية الوصول للملف بشكل دائم
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // بعض الملفات (مثل آخر ملف محفوظ) قد لا تحتاج هذا أو قد يفشل، تجاهل
            }

            // احفظ موضع الملف الحالي قبل التبديل
            saveCurrentPosition()

            releasePlayer()

            currentUri = uri
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                setOnPreparedListener { mp ->
                    binding.tvTotalTime.text = formatTime(mp.duration.toLong())
                    applyPlaybackSpeed()

                    if (resumeFromSaved) {
                        val savedPos = prefs.getInt(positionKey(uri), 0)
                        if (savedPos > 0 && savedPos < mp.duration) {
                            mp.seekTo(savedPos)
                        }
                    }
                    updateSeekBarLoop()
                }
                setOnCompletionListener {
                    binding.btnPlayPause.text = getString(R.string.play)
                    binding.btnPlayPause.contentDescription = getString(R.string.play)
                    // عند الانتهاء، امسح الموضع المحفوظ حتى يبدأ من جديد لاحقًا
                    prefs.edit().remove(positionKey(uri)).apply()
                }
                prepareAsync()
            }

            val fileName = getFileNameFromUri(uri) ?: getString(R.string.no_file_loaded)
            binding.tvFileName.text = getString(R.string.now_playing_prefix, fileName)
            binding.tvFileName.contentDescription = binding.tvFileName.text

            // احفظ هذا الملف كآخر ملف تم فتحه
            prefs.edit().putString(KEY_LAST_URI, uri.toString()).apply()

        } catch (e: Exception) {
            binding.tvFileName.text = "تعذر فتح الملف: ${e.message}"
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex >= 0) {
                name = it.getString(nameIndex)
            }
        }
        return name
    }

    private fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            binding.btnPlayPause.text = getString(R.string.play)
            binding.btnPlayPause.contentDescription = getString(R.string.play)
            saveCurrentPosition()
        } else {
            mp.start()
            applyPlaybackSpeed()
            binding.btnPlayPause.text = getString(R.string.pause)
            binding.btnPlayPause.contentDescription = getString(R.string.pause)
            updateSeekBarLoop()
        }
    }

    private fun seekBy(deltaMillis: Int) {
        val mp = mediaPlayer ?: return
        val newPos = (mp.currentPosition + deltaMillis).coerceIn(0, mp.duration)
        mp.seekTo(newPos)
    }

    private fun applyPlaybackSpeed() {
        val mp = mediaPlayer ?: return
        try {
            val wasPlaying = mp.isPlaying
            mp.playbackParams = PlaybackParams().setSpeed(currentSpeed)
            if (wasPlaying && !mp.isPlaying) {
                mp.start()
            }
        } catch (e: Exception) {
            // بعض الأجهزة القديمة قد لا تدعم كل السرعات، تجاهل بأمان
        }
    }

    private fun updateSeekBarLoop() {
        progressRunnable = Runnable {
            mediaPlayer?.let { mp ->
                if (!isUserSeeking) {
                    val duration = mp.duration
                    if (duration > 0) {
                        val progress = (mp.currentPosition.toLong() * 1000 / duration).toInt()
                        binding.seekBar.progress = progress
                        binding.tvCurrentTime.text = formatTime(mp.currentPosition.toLong())
                    }
                }
                if (mp.isPlaying) {
                    handler.postDelayed(progressRunnable, 500)
                }
            }
        }
        handler.post(progressRunnable)
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("10 دقائق", "15 دقيقة", "30 دقيقة", "45 دقيقة", "60 دقيقة", getString(R.string.sleep_timer_option_off))
        val minutesValues = intArrayOf(10, 15, 30, 45, 60, 0)

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_sleep_timer)
            .setItems(options) { dialog, which ->
                val minutes = minutesValues[which]
                if (minutes == 0) {
                    cancelSleepTimer()
                } else {
                    startSleepTimer(minutes * 60_000L)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startSleepTimer(millis: Long) {
        sleepTimer?.cancel()
        sleepTimerMillisLeft = millis
        sleepTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                sleepTimerMillisLeft = millisUntilFinished
                binding.tvSleepTimerStatus.text = getString(
                    R.string.sleep_timer_remaining,
                    formatTime(millisUntilFinished)
                )
            }

            override fun onFinish() {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        mp.pause()
                        saveCurrentPosition()
                    }
                }
                binding.btnPlayPause.text = getString(R.string.play)
                binding.btnPlayPause.contentDescription = getString(R.string.play)
                binding.tvSleepTimerStatus.text = getString(R.string.sleep_timer_off)
            }
        }.start()
    }

    private fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
        binding.tvSleepTimerStatus.text = getString(R.string.sleep_timer_off)
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds)
        val seconds = totalSeconds - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun positionKey(uri: Uri): String = KEY_POSITION_PREFIX + uri.toString()

    private fun saveCurrentPosition() {
        val mp = mediaPlayer ?: return
        val uri = currentUri ?: return
        try {
            prefs.edit().putInt(positionKey(uri), mp.currentPosition).apply()
        } catch (e: Exception) {
            // المشغل قد يكون في حالة غير صالحة، تجاهل
        }
    }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.let {
            try {
                it.stop()
            } catch (e: Exception) { }
            it.release()
        }
        mediaPlayer = null
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
    }

    override fun onDestroy() {
        saveCurrentPosition()
        sleepTimer?.cancel()
        releasePlayer()
        super.onDestroy()
    }
}
