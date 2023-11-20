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
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.atan2


class LiveDataActivity : AppCompatActivity() {

    // global graph variables
    lateinit var dataSet_res_accel_x: LineDataSet
    lateinit var dataSet_res_accel_y: LineDataSet
    lateinit var dataSet_res_accel_z: LineDataSet

    var respeckBuffer = Array(Constants.MODEL_INPUT_SIZE) { FloatArray(6) }
    var time = 0f
    var buffertime = 0
    private var outputString = "Please do activity for 4 seconds"
    private val myHandler = Handler(Looper.getMainLooper())
    lateinit var allRespeckData: LineData

    lateinit var allThingyData: LineData

    lateinit var respeckChart: LineChart

    // global broadcast receiver so we can unregister it
    lateinit var respeckAnalysisReceiver: BroadcastReceiver
    lateinit var looperRespeck: Looper
    lateinit var looperAnalysis: Looper
    lateinit var looperThingy: Looper
    lateinit var tflite: Interpreter

    val filterTestRespeck = IntentFilter(Constants.ACTION_RESPECK_LIVE_BROADCAST)

    fun onReceiveRespeckDataFrame(xa: Float, ya: Float, za: Float, xg: Float, yg: Float, zg: Float) {

        // Update graph
        time += 1
        updateGraph("respeck", xa, ya, za)

        // add data to current buffer array
        respeckBuffer[buffertime.toInt()] = floatArrayOf(xa, ya, za, xg, yg, zg)

        buffertime += 1

        if (buffertime >= Constants.MODEL_INPUT_SIZE) {
            // do analysis
            Log.d("Live", "onReceive: analysis time")
            analyseData()
            buffertime /= 2
            //empty buffer

            shiftBufferArray()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_data)

        setupCharts()

        tflite = try {
            Interpreter(loadModelFile())
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

        val inputTensor1 = tflite.getInputTensor(0)
        val inputTensor2 = tflite.getInputTensor(1)
        val inputTensor3 = tflite.getInputTensor(2)

        // Get input tensor details
        val shape1 = inputTensor1.shape()
        val dataType1 = inputTensor1.dataType()

        val shape2 = inputTensor2.shape()
        val dataType2 = inputTensor2.dataType()

        val shape3 = inputTensor3.shape()
        val dataType3 = inputTensor3.dataType()

        // Print the details
        println("Input tensor shape: ${shape1.contentToString()}, ${shape2.contentToString()}, ${shape3.contentToString()}")
        println("Input tensor data type: $dataType1 , $dataType2 , $dataType3")

        val textView: TextView = findViewById(R.id.analysisResult)
        textView.text = outputString

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

                    onReceiveRespeckDataFrame(xa, ya, za, xg, yg, zg)
                }
            }
        }

        val handlerAnalysisThread = HandlerThread("bgThreadRespeckAnalysis")
        handlerAnalysisThread.start()
        looperAnalysis = handlerAnalysisThread.looper
        val handlerAnalysis = Handler(looperAnalysis)
        this.registerReceiver(respeckAnalysisReceiver, filterTestRespeck, null, handlerAnalysis)

        // set up the broadcast receiver

        // register receiver on another thread

    }


    fun setupCharts() {
        respeckChart = findViewById(R.id.respeck_chart)

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


    }

    private fun analyseData() {
        // do analysis using tflite model

        //Generate fourier transformed data
        val fourierTransform = fftAmplitudeAndPhase(respeckBuffer)

        //Generate differentials
        val differentials = differential(respeckBuffer)

        val input1 = FloatBuffer.allocate(respeckBuffer.size * respeckBuffer[0].size)

        for (fa in respeckBuffer)
            input1.put(fa)

        input1.rewind()

        val input2 = FloatBuffer.allocate(fourierTransform.size * respeckBuffer[0].size)

        for (pa in fourierTransform)
            for (f in pa)
                input2.put(f.first.toFloat())

        input2.rewind()

        val input3 = FloatBuffer.allocate(differentials.size * differentials[0].size)

        for (fa in differentials)
            input3.put(fa)

        input3.rewind()

        val output = HashMap<Int, Any>()

        output[0] = IntBuffer.allocate(1)
        output[1] = IntBuffer.allocate(1)

        val inputTensor = tflite.getInputTensor(0)

        // Get input tensor details
        val shape = inputTensor.shape()
        val dataType = inputTensor.dataType()

        // Print the details
        println("Input tensor shape: ${shape.contentToString()}")
        println("Input tensor data type: $dataType")

        assert(shape.maxOrNull() == Constants.MODEL_INPUT_SIZE)

        tflite.runForMultipleInputsOutputs(arrayOf(input1, input2, input3), output)

        //translate 1st output to activity string.
        var output1 = (output[0] as IntBuffer).get(0)
        var output2 = (output[1] as IntBuffer).get(0)

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

    private fun shiftBufferArray() {
        for (i in 0 until respeckBuffer.size/2) {
            respeckBuffer[i] = respeckBuffer[i + respeckBuffer.size/2]
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(respeckAnalysisReceiver)
        looperRespeck.quit()
        looperThingy.quit()
        looperAnalysis.quit()
    }



}
