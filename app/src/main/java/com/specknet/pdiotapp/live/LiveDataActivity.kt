package com.specknet.pdiotapp.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.specknet.pdiotapp.R
import com.specknet.pdiotapp.utils.Constants
import com.specknet.pdiotapp.utils.RESpeckLiveData
import com.specknet.pdiotapp.utils.ThingyLiveData
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.apache.commons.math3.complex.Complex
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import kotlin.math.atan2


class LiveDataActivity : AppCompatActivity() {

    // global graph variables
    lateinit var dataSet_res_accel_x: LineDataSet
    lateinit var dataSet_res_accel_y: LineDataSet
    lateinit var dataSet_res_accel_z: LineDataSet

    lateinit var dataSet_thingy_accel_x: LineDataSet
    lateinit var dataSet_thingy_accel_y: LineDataSet
    lateinit var dataSet_thingy_accel_z: LineDataSet


    var respeckBuffer = Array(128) { FloatArray(6) }
    var time = 0f
    var buffertime = 0
    private var outputString = "Please do activity for 4 seconds"
    private val myHandler = Handler(Looper.getMainLooper())
    lateinit var allRespeckData: LineData

    lateinit var allThingyData: LineData

    lateinit var respeckChart: LineChart
    lateinit var thingyChart: LineChart

    // global broadcast receiver so we can unregister it
    lateinit var respeckLiveUpdateReceiver: BroadcastReceiver
    lateinit var thingyLiveUpdateReceiver: BroadcastReceiver
    lateinit var respeckAnalysisReceiver: BroadcastReceiver
    lateinit var looperRespeck: Looper
    lateinit var looperAnalysis: Looper
    lateinit var looperThingy: Looper
    lateinit var tflite: Interpreter

    val filterTestRespeck = IntentFilter(Constants.ACTION_RESPECK_LIVE_BROADCAST)
    val filterTestThingy = IntentFilter(Constants.ACTION_THINGY_BROADCAST)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_data)

        setupCharts()

        tflite = try {
            Interpreter(loadModelFile())
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

        val textView: TextView = findViewById(R.id.analysisResult)
        textView.text = outputString
        // set up the broadcast receiver
        respeckLiveUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {

                Log.i("thread", "I am running on thread = " + Thread.currentThread().name)

                val action = intent.action

                if (action == Constants.ACTION_RESPECK_LIVE_BROADCAST) {

                    val liveData =
                        intent.getSerializableExtra(Constants.RESPECK_LIVE_DATA) as RESpeckLiveData
                    Log.d("Live", "onReceive: liveData = " + liveData)

                    // get all relevant intent contents
                    val x = liveData.accelX
                    val y = liveData.accelY
                    val z = liveData.accelZ

                    time += 1
                    updateGraph("respeck", x, y, z)

                }
            }
        }

        // register receiver on another thread
        val handlerThreadRespeck = HandlerThread("bgThreadRespeckLive")
        handlerThreadRespeck.start()
        looperRespeck = handlerThreadRespeck.looper
        val handlerRespeck = Handler(looperRespeck)
        this.registerReceiver(respeckLiveUpdateReceiver, filterTestRespeck, null, handlerRespeck)

        respeckAnalysisReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {

                Log.i("thread", "I am running on thread = " + Thread.currentThread().name)

                val action = intent.action

                if (action == Constants.ACTION_RESPECK_LIVE_BROADCAST) {

                    val liveData =
                        intent.getSerializableExtra(Constants.RESPECK_LIVE_DATA) as RESpeckLiveData
                    Log.d("Live", "onReceive: liveData = " + liveData)

                    // get all relevant intent contents
                    val xa = liveData.accelX
                    val ya = liveData.accelY
                    val za = liveData.accelZ

                    val xg = liveData.gyro.x
                    val yg = liveData.gyro.y
                    val zg = liveData.gyro.z

                    // add data to current buffer array
                    respeckBuffer[buffertime.toInt()] = floatArrayOf(xa, ya, za, xg, yg, zg)

                    buffertime += 1

                    if (buffertime >= 128) {
                        // do analysis
                        Log.d("Live", "onReceive: analysis time")
                        analyseData()
                        buffertime = 0
                        //empty buffer
                        respeckBuffer = Array(128) { FloatArray(6) }


                    }

                }
            }
        }

        val handlerAnalysisThread = HandlerThread("bgThreadRespeckAnalysis")
        handlerAnalysisThread.start()
        looperAnalysis = handlerAnalysisThread.looper
        val handlerAnalysis = Handler(looperAnalysis)
        this.registerReceiver(respeckAnalysisReceiver, filterTestRespeck, null, handlerAnalysis)

        // set up the broadcast receiver
        thingyLiveUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {

                Log.i("thread", "I am running on thread = " + Thread.currentThread().name)

                val action = intent.action

                if (action == Constants.ACTION_THINGY_BROADCAST) {

                    val liveData =
                        intent.getSerializableExtra(Constants.THINGY_LIVE_DATA) as ThingyLiveData
                    Log.d("Live", "onReceive: liveData = " + liveData)

                    // get all relevant intent contents
                    val x = liveData.accelX
                    val y = liveData.accelY
                    val z = liveData.accelZ

                    time += 1
                    updateGraph("thingy", x, y, z)

                }
            }
        }

        // register receiver on another thread
        val handlerThreadThingy = HandlerThread("bgThreadThingyLive")
        handlerThreadThingy.start()
        looperThingy = handlerThreadThingy.looper
        val handlerThingy = Handler(looperThingy)
        this.registerReceiver(thingyLiveUpdateReceiver, filterTestThingy, null, handlerThingy)

    }


    fun setupCharts() {
        respeckChart = findViewById(R.id.respeck_chart)
        thingyChart = findViewById(R.id.thingy_chart)

        // Respeck
        time = 0f
        val entries_res_accel_x = ArrayList<Entry>()
        val entries_res_accel_y = ArrayList<Entry>()
        val entries_res_accel_z = ArrayList<Entry>()

        dataSet_res_accel_x = LineDataSet(entries_res_accel_x, "Accel X")
        dataSet_res_accel_y = LineDataSet(entries_res_accel_y, "Accel Y")
        dataSet_res_accel_z = LineDataSet(entries_res_accel_z, "Accel Z")

        dataSet_res_accel_x.setDrawCircles(false)
        dataSet_res_accel_y.setDrawCircles(false)
        dataSet_res_accel_z.setDrawCircles(false)

        dataSet_res_accel_x.setColor(
            ContextCompat.getColor(
                this,
                R.color.red
            )
        )
        dataSet_res_accel_y.setColor(
            ContextCompat.getColor(
                this,
                R.color.green
            )
        )
        dataSet_res_accel_z.setColor(
            ContextCompat.getColor(
                this,
                R.color.blue
            )
        )

        val dataSetsRes = ArrayList<ILineDataSet>()
        dataSetsRes.add(dataSet_res_accel_x)
        dataSetsRes.add(dataSet_res_accel_y)
        dataSetsRes.add(dataSet_res_accel_z)

        allRespeckData = LineData(dataSetsRes)
        respeckChart.data = allRespeckData
        respeckChart.invalidate()

        // Thingy

        time = 0f
        val entries_thingy_accel_x = ArrayList<Entry>()
        val entries_thingy_accel_y = ArrayList<Entry>()
        val entries_thingy_accel_z = ArrayList<Entry>()

        dataSet_thingy_accel_x = LineDataSet(entries_thingy_accel_x, "Accel X")
        dataSet_thingy_accel_y = LineDataSet(entries_thingy_accel_y, "Accel Y")
        dataSet_thingy_accel_z = LineDataSet(entries_thingy_accel_z, "Accel Z")

        dataSet_thingy_accel_x.setDrawCircles(false)
        dataSet_thingy_accel_y.setDrawCircles(false)
        dataSet_thingy_accel_z.setDrawCircles(false)

        dataSet_thingy_accel_x.setColor(
            ContextCompat.getColor(
                this,
                R.color.red
            )
        )
        dataSet_thingy_accel_y.setColor(
            ContextCompat.getColor(
                this,
                R.color.green
            )
        )
        dataSet_thingy_accel_z.setColor(
            ContextCompat.getColor(
                this,
                R.color.blue
            )
        )

        val dataSetsThingy = ArrayList<ILineDataSet>()
        dataSetsThingy.add(dataSet_thingy_accel_x)
        dataSetsThingy.add(dataSet_thingy_accel_y)
        dataSetsThingy.add(dataSet_thingy_accel_z)

        allThingyData = LineData(dataSetsThingy)
        thingyChart.data = allThingyData
        thingyChart.invalidate()
    }

    private fun analyseData() {
        // do analysis using tflite model

        //Generate fourier transformed data
        val fourierTransform = fftAmplitudeAndPhase(respeckBuffer)

        //Generate differentials
        val differentials = differential(respeckBuffer)

        val input = arrayOf(respeckBuffer,fourierTransform,differentials)


        val output = Array(2) {0}

        val inputTensor = tflite.getInputTensor(0)

        // Get input tensor details
        val shape = inputTensor.shape()
        val dataType = inputTensor.dataType()

        // Print the details
        println("Input tensor shape: ${shape.contentToString()}")
        println("Input tensor data type: $dataType")

        tflite.run(input, output)

        //translate 1st output to activity string.
        var output1 = output[0]
        var output2 = output[1]

        val breathing = when (output1) {
            0 -> "normal"
            1 -> "coughing"
            2 -> "hyperventilating"
            3 -> "Laughing/Singing/Talking/Eating"
            else -> "Invalid output"
        }
        val activity = when (output2) {
            0 -> "ascending stairs"
            1 -> "descending stairs"
            2 -> "lying down back"
            3 -> "lying down on left"
            4 -> "lying down on stomach"
            5 -> "lying down right"
            6 -> "miscellaneous movements"
            7 -> "normal walking"
            8 -> "running"
            9 -> "shuffle walking"
            10 -> "Stationary"
            else -> "Invalid output"
        }
        
        updateText(breathing,activity)
    }

    private fun updateText(breathing: String, activity: String) {
        // update the text with the activity
        outputString = "Currently: $breathing and $activity"
        this.findViewById<TextView>(R.id.analysisResult).text = outputString
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = assets.openFd("respmodel.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    fun updateGraph(graph: String, x: Float, y: Float, z: Float) {
        // take the first element from the queue
        // and update the graph with it
        if (graph == "respeck") {
            dataSet_res_accel_x.addEntry(Entry(time, x))
            dataSet_res_accel_y.addEntry(Entry(time, y))
            dataSet_res_accel_z.addEntry(Entry(time, z))

            runOnUiThread {
                allRespeckData.notifyDataChanged()
                respeckChart.notifyDataSetChanged()
                respeckChart.invalidate()
                respeckChart.setVisibleXRangeMaximum(150f)
                respeckChart.moveViewToX(respeckChart.lowestVisibleX + 40)
            }
        } else if (graph == "thingy") {
            dataSet_thingy_accel_x.addEntry(Entry(time, x))
            dataSet_thingy_accel_y.addEntry(Entry(time, y))
            dataSet_thingy_accel_z.addEntry(Entry(time, z))

            runOnUiThread {
                allThingyData.notifyDataChanged()
                thingyChart.notifyDataSetChanged()
                thingyChart.invalidate()
                thingyChart.setVisibleXRangeMaximum(150f)
                thingyChart.moveViewToX(thingyChart.lowestVisibleX + 40)
            }
        }


    }


    private fun fftAmplitudeAndPhase(input: Array<FloatArray>): Array<Array<Pair<Double, Double>>> {
        val transformer = FastFourierTransformer(DftNormalization.STANDARD)
        val result = Array(input[0].size) { Array(input.size) { Pair(0.0, 0.0) } }

        for (i in 0 until input[0].size) {
            val doubleArray = input.map { it[i].toDouble() }.toDoubleArray()
            val complexResult = transformer.transform(doubleArray, TransformType.FORWARD)

            for (j in complexResult.indices) {
                val amplitude = complexResult[j].abs()
                val phase = atan2(complexResult[j].imaginary, complexResult[j].real)
                result[i][j] = Pair(amplitude, phase)
            }
        }

        return result
    }

    private fun differential(input: Array<FloatArray>): Array<FloatArray> {
        val output = Array(input.size) { FloatArray(6) }

        for (i in input.indices) {
            for (j in -Constants.DERIVATIVE_SMOOTHING..Constants.DERIVATIVE_SMOOTHING) {
                var clamped = i + j;

                if (clamped < 0)
                    clamped = 0
                else if (clamped >= input.size)
                    clamped = input.size - 1;

                addTo(output[i], input[clamped]);
            }

            multiplyBy(output[i], 1f / (2 * Constants.DERIVATIVE_SMOOTHING + 1));
        }

        return output
    }

    private fun addTo(modify: FloatArray, other: FloatArray) {
        for (i in modify.indices) {
            modify[i] += other[i]
        }
    }

    private fun multiplyBy(modify: FloatArray, scalar: Float) {
        for (i in modify.indices) {
            modify[i] *= scalar
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(respeckLiveUpdateReceiver)
        unregisterReceiver(thingyLiveUpdateReceiver)
        unregisterReceiver(respeckAnalysisReceiver)
        looperRespeck.quit()
        looperThingy.quit()
        looperAnalysis.quit()
    }



}
