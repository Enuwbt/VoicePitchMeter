package rec.enuwbt.vpmc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt

import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity


class MainActivity : ComponentActivity() {
    companion object {
        const val PERMISSION_REQUEST_RECORD_AUDIO = 200
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val BUFFER_SIZE: Int =
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 3

        var UNIQUE_COUNT = 0

        val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        const val START_OCTAVE = 1
        const val END_OCTAVE = 6
        const val TOTAL_OCTAVES = END_OCTAVE - START_OCTAVE + 1

        val TOTAL_NOTES = NOTE_NAMES.size * TOTAL_OCTAVES

    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAudioPermission()
        setContent {
            PitchDetectorScreen()
        }
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_RECORD_AUDIO
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }

    fun startRecording(onPitchDetected: (Float) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            BUFFER_SIZE
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return

        audioRecord?.startRecording()
        isRecording = true

        Thread {
            detectPitch(onPitchDetected)
        }.start()
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun detectPitch(onPitchDetected: (Float) -> Unit) {
        val audioBuffer = ShortArray(BUFFER_SIZE)
        while (isRecording) {
            val bytesRead = audioRecord?.read(audioBuffer, 0, BUFFER_SIZE) ?: 0
            if (bytesRead > 0) {
                val pitchInHz = calculatePitch(audioBuffer, bytesRead)

                runOnUiThread {
                    onPitchDetected(pitchInHz)
                }
            }
        }
    }

    private fun calculatePitch(audioBuffer: ShortArray, bytesRead: Int): Float {
        val frameSize = bytesRead / 2
        val yinBuffer = FloatArray(frameSize / 2)
        val threshold = 0.15f

        for (tau in yinBuffer.indices) {
            yinBuffer[tau] = 0f
            for (j in yinBuffer.indices) {
                val delta = audioBuffer[j].toFloat() - audioBuffer[j + tau].toFloat()
                yinBuffer[tau] += delta * delta
            }
        }

        var sum = 0f
        yinBuffer[0] = 1f
        for (tau in 1 until yinBuffer.size) {
            sum += yinBuffer[tau]
            yinBuffer[tau] = yinBuffer[tau] * tau / sum
        }

        var tau = 2
        while (tau < yinBuffer.size) {
            if (yinBuffer[tau] < threshold) {
                while (tau + 1 < yinBuffer.size && yinBuffer[tau + 1] < yinBuffer[tau]) {
                    tau++
                }
                val betterTau = tau + (yinBuffer[tau - 1] - yinBuffer[tau + 1]) /
                        (2 * (yinBuffer[tau - 1] - 2 * yinBuffer[tau] + yinBuffer[tau + 1]))
                return SAMPLE_RATE / betterTau
            }
            tau++
        }
        return -1f
    }
}

data class PitchPoint(val frequency: Float, val continuous: Boolean = true)


@Composable
fun PitchDetectorScreen() {
    var isRecording by remember { mutableStateOf(false) }
    var currentPitch by remember { mutableStateOf(0f) }
    var currentNote by remember { mutableStateOf("") }


    var pitchHistory by remember { mutableStateOf(List(100) { PitchPoint(0f, true) }) }

    var autoScrollEnabled by remember { mutableStateOf(false) }
    var scrollSpeed by remember { mutableStateOf(2f) }
    var scrollPosition by remember { mutableStateOf(0f) }
    var lastPitchTime by remember { mutableStateOf(0L) }

    val silenceThreshold = 500L

    val verticalScrollState = rememberScrollState()
    var containerHeight by remember { mutableStateOf(0) }

    val currentScaleKey = "C Major"
    val context = LocalContext.current

    val darkBlue = Color(0xFF355C7D)
    val purple = Color(0xFF725A7A)
    val rose = Color(0xFFC56C86)
    val coral = Color(0xFFFF7582)
    val darkBg = Color(0xFF1E2A3A)
    val lightText = Color(0xFFF5F5F5)
    val mediumText = Color(0xFFCCCCCC)
    val dimText = Color(0xFF999999)

    val startOctave = 1

    val onPitchDetected: (Float) -> Unit = { pitch ->
        val currentTime = System.currentTimeMillis()

        if (pitch > 0) {
            val continuous = if (currentTime - lastPitchTime < silenceThreshold) true else false

            currentPitch = pitch
            currentNote = frequencyToNote(pitch)
            pitchHistory = (pitchHistory + PitchPoint(pitch, continuous)).takeLast(100)
            lastPitchTime = currentTime
        } else if (autoScrollEnabled) {
            val timeSinceLastPitch = currentTime - lastPitchTime

            if (timeSinceLastPitch > silenceThreshold) {
                pitchHistory = (pitchHistory + PitchPoint(0f, false)).takeLast(100)
            } else {
                pitchHistory = (pitchHistory + PitchPoint(0f, true)).takeLast(100)
            }

            currentPitch = 0.0f
            currentNote = ""
        }
    }

    LaunchedEffect(autoScrollEnabled, scrollSpeed) {
        while (autoScrollEnabled) {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastPitchTime > silenceThreshold && pitchHistory.lastOrNull()?.continuous == true) {
                val newList = pitchHistory.toMutableList()
                if (newList.isNotEmpty()) {
                    val lastPoint = newList.last()
                    newList[newList.size - 1] = PitchPoint(lastPoint.frequency, false)
                    pitchHistory = newList
                }
            }

            delay(100)
        }
    }

    LaunchedEffect(currentPitch, containerHeight) {
        if (currentPitch > 0 && containerHeight > 0) {

            pitchHistory.forEachIndexed { index, pitchPoint ->
                val pitch = pitchPoint.frequency
                if (pitch <= 0) return@forEachIndexed

                val noteNumber = frequencyToNoteNumber(frequency = pitch)
                val height = containerHeight

                val lowestNoteNumber = (startOctave * 12) + 12
                val normalizedNote = noteNumber - lowestNoteNumber
                val y = height - (normalizedNote / (MainActivity.TOTAL_NOTES.toFloat()) * height)

                val scrollTop = verticalScrollState.value
                val scrollBottom = scrollTop + verticalScrollState.viewportSize

                println("top: $scrollTop, pitchY: $y, bottom: $scrollBottom")

                if (scrollTop > y || y > scrollBottom) {

                    val dst = y.toInt() - verticalScrollState.viewportSize / 2
                    verticalScrollState.scrollTo(dst)
                    System.err.println("dst: $dst")
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Vocal Pitch Monitor",
                        style = TextStyle(
                            color = lightText,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = currentScaleKey,
                        style = TextStyle(
                            color = dimText,
                            fontSize = 16.sp
                        )
                    )
                }

                Card(
                    modifier = Modifier.padding(end = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = darkBlue.copy(alpha = 0.3f)
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 0.dp
                    )
                ) {
                    Text(
                        text = "120 BPM",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = TextStyle(
                            color = mediumText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = rose.copy(alpha = 0.15f)
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 0.dp
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (currentNote.isNotEmpty()) currentNote else "—",
                        style = TextStyle(
                            color = if (currentNote.isNotEmpty()) coral else mediumText,
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (currentPitch > 0) String.format("%.2f Hz", currentPitch) else "0.00 Hz",
                        style = TextStyle(
                            color = mediumText,
                            fontSize = 18.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Pitch History",
                style = TextStyle(
                    color = lightText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = darkBlue.copy(alpha = 0.2f)
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 0.dp
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                        .verticalScroll(verticalScrollState)
                        .onSizeChanged { size ->
                            containerHeight = size.height
                        }
                ) {
                    PitchGraph(
                        pitchHistory = pitchHistory,
                        currentPitch = currentPitch,
                        currentScaleKey = currentScaleKey,
                        autoScrollEnabled = autoScrollEnabled,
                        scrollSpeed = scrollSpeed,
                        scrollPosition = scrollPosition,
                        colorPalette = listOf(darkBlue, purple, rose, coral),
                        modifier = Modifier.height(1800.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Scroll Speed",
                        style = TextStyle(color = dimText, fontSize = 14.sp),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Slider(
                        value = scrollSpeed,
                        onValueChange = { scrollSpeed = 1f + it * 9f },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = coral,
                            activeTrackColor = coral,
                            inactiveTrackColor = purple.copy(alpha = 0.3f)
                        )
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = { autoScrollEnabled = !autoScrollEnabled },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (autoScrollEnabled) coral else purple.copy(alpha = 0.7f)
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = if (autoScrollEnabled) "Auto-Scroll ON" else "Auto-Scroll OFF",
                        color = lightText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    isRecording = if (isRecording) {
                        (context as? MainActivity)?.stopRecording()
                        false
                    } else {
                        (context as? MainActivity)?.startRecording(onPitchDetected)
                        true
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) darkBlue else coral
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                    tint = lightText,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRecording) "Stop Recording" else "Start Recording",
                    color = lightText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun PitchGraph(
    pitchHistory: List<PitchPoint>,
    currentPitch: Float,
    currentScaleKey: String,
    autoScrollEnabled: Boolean,
    scrollSpeed: Float,
    scrollPosition: Float,
    colorPalette: List<Color>,
    modifier: Modifier = Modifier
) {
    val darkBlue = colorPalette[0]
    val purple = colorPalette[1]
    val rose = colorPalette[2]
    val coral = colorPalette[3]

    val backgroundColor = Color(0xFF121212)
    val gridLineColor = Color(0xFF2A2A2A)
    val highlightLineColor = Color(0xFF3A3A3A)
    val textColor = Color(0xFFAAAAAA)
    val accentColor = coral

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        val width = size.width
        val height = size.height


        val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        val startOctave = MainActivity.START_OCTAVE
        val endOctave = MainActivity.END_OCTAVE
        val totalOctaves = endOctave - startOctave + 1
        val totalNotes = noteNames.size * totalOctaves

        val tickCount = 20
        for (i in 0..tickCount) {
            val x = i * width / tickCount
            drawLine(
                color = gridLineColor,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 1f
            )
        }

        for (octave in startOctave..endOctave) {
            for (noteIndex in noteNames.indices) {
                val note = noteNames[noteIndex]
                val position = (octave - startOctave) * noteNames.size + noteIndex
                val y = height - (position * height / totalNotes.toFloat())

                drawLine(
                    color = gridLineColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )
                val noteName = "$note$octave"
                drawContext.canvas.nativeCanvas.drawText(
                    noteName,
                    10f,
                    y,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 30f
                    }
                )
            }
        }

        val majorScale = when (currentScaleKey) {
            "C Major" -> listOf(0, 2, 4, 5, 7, 9, 11)
            else -> listOf(0, 2, 4, 5, 7, 9, 11)
        }

        for (octave in startOctave..endOctave) {
            for (noteIndex in majorScale) {
                val position = (octave - startOctave) * noteNames.size + noteIndex
                val y = height - (position * height / totalNotes.toFloat())

                drawLine(
                    color = highlightLineColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.5f
                )
            }
        }

        if (pitchHistory.isNotEmpty()) {
            var currentPath = Path()
            var isPathStarted = false

            pitchHistory.forEachIndexed { index, pitchPoint ->
                val pitch = pitchPoint.frequency
                if (pitch <= 0) return@forEachIndexed

                val noteNumber = frequencyToNoteNumber(pitch)

                val x = (index * width / pitchHistory.size)

                val lowestNoteNumber = (startOctave * 12) + 12
                val normalizedNote = noteNumber - lowestNoteNumber
                val y = height - (normalizedNote / (totalNotes.toFloat()) * height)

                if (!pitchPoint.continuous || !isPathStarted) {
                    if (isPathStarted) {
                        drawPath(
                            path = currentPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(coral, rose),
                                startY = 0f,
                                endY = height
                            ),
                            style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }

                    currentPath = Path()
                    currentPath.moveTo(x, y)
                    isPathStarted = true
                } else {
                    currentPath.lineTo(x, y)
                }

                drawCircle(
                    color = coral,
                    radius = 4f,
                    center = Offset(x, y)
                )
            }

            if (isPathStarted) {
                drawPath(
                    path = currentPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(coral, rose),
                        startY = 0f,
                        endY = height
                    ),
                    style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        if (currentPitch > 0) {
            val currentNoteNumber = frequencyToNoteNumber(currentPitch)
            val normalizedNote = currentNoteNumber - (startOctave * 12)
            val y = height - (normalizedNote / (totalNotes.toFloat()) * height)

            drawLine(
                color = accentColor.copy(alpha = 0.4f),
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )

            drawRoundRect(
                color = accentColor.copy(alpha = 0.2f),
                topLeft = Offset(width - 120f, 20f),
                size = Size(100f, 40f),
                cornerRadius = CornerRadius(8f, 8f)
            )

            drawContext.canvas.nativeCanvas.drawText(
                "HOLD",
                width - 80f,
                50f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 30f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
        }
    }
}

fun frequencyToNoteNumber(frequency: Float): Float {
    return if (frequency <= 0) 0f else (12 * log2(frequency / 440.0) + 69).toFloat()
}

fun frequencyToNote(frequency: Float): String {
    if (frequency <= 0) return ""

    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val noteNumber = (12 * log2(frequency / 440.0) + 69).toInt()
    val octave = noteNumber / 12 - 1
    val note = noteNames[noteNumber % 12]

    return "$note $octave"
}
