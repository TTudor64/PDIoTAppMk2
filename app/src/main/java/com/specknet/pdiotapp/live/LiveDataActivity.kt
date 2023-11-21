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
import com.specknet.pdiotapp.utils.CsvReader
import com.specknet.pdiotapp.utils.RESpeckLiveData
import com.specknet.pdiotapp.utils.Utils
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlin.math.sign


class LiveDataActivity : AppCompatActivity() {

    // global graph variables
    lateinit var dataSet_res_accel_x: LineDataSet
    lateinit var dataSet_res_accel_y: LineDataSet
    lateinit var dataSet_res_accel_z: LineDataSet
    lateinit var dataSet_res_gyro_x: LineDataSet
    lateinit var dataSet_res_gyro_y: LineDataSet
    lateinit var dataSet_res_gyro_z: LineDataSet



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
    lateinit var looperAnalysis: Looper
    lateinit var tflite: Interpreter

    lateinit var rawMean : ArrayList<FloatArray>
    lateinit var rawStd : ArrayList<FloatArray>
    lateinit var fftMean : ArrayList<FloatArray>
    lateinit var fftStd : ArrayList<FloatArray>
    lateinit var diffMean : ArrayList<FloatArray>
    lateinit var diffStd : ArrayList<FloatArray>

    val filterTestRespeck = IntentFilter(Constants.ACTION_RESPECK_LIVE_BROADCAST)

    fun onReceiveRespeckDataFrame(xa: Float, ya: Float, za: Float, xg: Float, yg: Float, zg: Float) {


        // Update graph
        time += 1
        updateGraph("respeck", xa, ya, za, xg,yg,zg)

        // add data to current buffer array
        respeckBuffer[buffertime.toInt()] = floatArrayOf(xa, ya, za, xg, yg, zg)

        buffertime += 1

        if (buffertime >= Constants.MODEL_INPUT_SIZE) {
            // do analysis
            //Log.d("Live", "onReceive: analysis time")
            analyseData()
            buffertime /= 3
            //empty buffer

            shiftBufferArray()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_data)

        setupCharts()
        var csvReader = CsvReader(this)
        rawMean = csvReader.readCsv("dm.csv")
        rawStd = csvReader.readCsv("ds.csv")
        fftMean = csvReader.readCsv("fm.csv")
        fftStd = csvReader.readCsv("fs.csv")
        diffMean = csvReader.readCsv("m.csv")
        diffStd = csvReader.readCsv("s.csv")
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

        assert(shape1.maxOrNull() == Constants.MODEL_INPUT_SIZE)

        for (i in 0 until tflite.outputTensorCount)
            println("Output tensor $i shape: ${tflite.getOutputTensor(i).shape().contentToString()}")

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
                    //Log.d("Live", "onReceive: liveData = " + liveData)

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

        val testDataHandler = Handler(looperAnalysis)
        testDataHandler.postDelayed(object : Runnable {
            var testData: Array<FloatArray>? = null
            var currentIndex: Int = 0

            override fun run()  {
                if (testData == null && time == 0f && assets.list("")!!.contains(Constants.TEST_DATA_FILE_NAME)) {
                    val lines = assets.open(Constants.TEST_DATA_FILE_NAME).bufferedReader().useLines { it.filter { it != "" && it[0].isDigit() }.toList() }
                    testData = Array(lines.size) {
                        val entries = lines[it].split(",")
                        val offset = entries.size - 6
                        FloatArray(6) { entries[it + offset].trim().toFloat() }
                    }
                }

                if (testData != null) {
                    testDataHandler.postDelayed(this, Constants.IDEAL_MS_BETWEEN_FRAMES)

                    val frame = testData!![currentIndex]
                    onReceiveRespeckDataFrame(frame[0], frame[1], frame[2], frame[3], frame[4], frame[5])

                    if (++currentIndex == testData!!.size)
                        currentIndex = 0
                }
            }
        }, 1000)
    }


    fun setupCharts() {
        respeckChart = findViewById(R.id.respeck_chart)

        // Respeck
        time = 0f
        val entries_res_accel_x = ArrayList<Entry>()
        val entries_res_accel_y = ArrayList<Entry>()
        val entries_res_accel_z = ArrayList<Entry>()
        val entries_res_gyro_x = ArrayList<Entry>()
        val entries_res_gyro_y = ArrayList<Entry>()
        val entries_res_gyro_z = ArrayList<Entry>()

        dataSet_res_accel_x = LineDataSet(entries_res_accel_x, "Accel X")
        dataSet_res_accel_y = LineDataSet(entries_res_accel_y, "Accel Y")
        dataSet_res_accel_z = LineDataSet(entries_res_accel_z, "Accel Z")
        dataSet_res_gyro_x = LineDataSet(entries_res_gyro_x, "Gyro X")
        dataSet_res_gyro_y = LineDataSet(entries_res_gyro_y, "Gyro Y")
        dataSet_res_gyro_z = LineDataSet(entries_res_gyro_z, "Gyro Z")


        dataSet_res_accel_x.setDrawCircles(false)
        dataSet_res_accel_y.setDrawCircles(false)
        dataSet_res_accel_z.setDrawCircles(false)
        dataSet_res_gyro_x.setDrawCircles(false)
        dataSet_res_gyro_y.setDrawCircles(false)
        dataSet_res_gyro_z.setDrawCircles(false)


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
        dataSet_res_gyro_x.setColor(
            ContextCompat.getColor(
                this,
                R.color.purple
            )
        )
        dataSet_res_gyro_y.setColor(
            ContextCompat.getColor(
                this,
                R.color.cyan
            )
        )
        dataSet_res_gyro_z.setColor(
            ContextCompat.getColor(
                this,
                R.color.orange
            )
        )

        val dataSetsRes = ArrayList<ILineDataSet>()
        dataSetsRes.add(dataSet_res_accel_x)
        dataSetsRes.add(dataSet_res_accel_y)
        dataSetsRes.add(dataSet_res_accel_z)
        dataSetsRes.add(dataSet_res_gyro_x)
        dataSetsRes.add(dataSet_res_gyro_y)
        dataSetsRes.add(dataSet_res_gyro_z)

        allRespeckData = LineData(dataSetsRes)
        respeckChart.data = allRespeckData
        respeckChart.invalidate()

        // Thingy

        time = 0f
        val entries_thingy_accel_x = ArrayList<Entry>()
        val entries_thingy_accel_y = ArrayList<Entry>()
        val entries_thingy_accel_z = ArrayList<Entry>()
    }

    private fun stationaryPoseId(x: Double, y: Double, z: Double): Int {
        val max = x.absoluteValue.coerceAtLeast(y.absoluteValue).coerceAtLeast(z.absoluteValue)
        return (if (x.absoluteValue == max)
                    x.sign
                else if (y.absoluteValue == max)
                    2 * y.sign
                else
                    3 * z.sign).toInt()
    }

    private fun poseName(id: Int): String {
        // TODO: Figure out what id is what pose
        return "Stationary pose $id"
    }

    private fun isStationaryOutput(id: Int): Boolean {
        return id == 2 || id == 7
    }

    private fun breathingName(id: Int): String {
        println("Output1 is : $id ")
        return when (id) {
            0 -> "Other Breathing"
            1 -> "Normal Breathing"
            2 -> "Coughing"
            3 -> "Hyperventilating"
            else -> "Invalid output"
        }
    }

    private fun activityName(id: Int): String {
        println("Output2 is : $id ")
        return when (id) {
            0 -> "ascending stairs"
            1 -> "descending stairs"
            /*2 -> "lying down back"
            3 -> "lying down on left"
            4 -> "lying down on right"
            5 -> "lying down on stomach"*/
            3 -> "miscellaneous movements"
            4 -> "normal walking"
            5 -> "running"
            6 -> "shuffle walking"
            //10 -> "Stationary"
            else -> "Invalid output"
        }
    }

    private fun analyseData() {
        var timeStart = System.currentTimeMillis()
        // do analysis using tflite model

        //Generate fourier transformed data
        val fourierTransform = fftAmplitude(respeckBuffer)

        //Generate differentials
        val differentials = differential(respeckBuffer)


        println("Untransformed Raw Data: ${respeckBuffer.contentDeepToString()}")
        println("Untransformed FT Data: ${fourierTransform.contentDeepToString()}")
        println("Untransformed Differential Data: ${differentials.contentDeepToString()}")
        val input1 = FloatBuffer.allocate(respeckBuffer.size * respeckBuffer[0].size)
        val input2 = FloatBuffer.allocate(fourierTransform.size * fourierTransform[0].size)
        val input3 = FloatBuffer.allocate(differentials.size * differentials[0].size)

        var count = 0
        for (fa in respeckBuffer) {
            var tmpArray = FloatArray(6)
            for (i in fa.indices) {
                tmpArray[i] = normalizeValue(fa[i], rawMean[count][i], rawStd[count][i])
            }
            input1.put(tmpArray)
            count++
        }
        count = 0
        println(input1)
        for (fa in fourierTransform) {
            var tmpArray = FloatArray(6)
            for (i in fa.indices) {
                tmpArray[i] = normalizeValue(fa[i], fftMean[count][i], fftStd[count][i])
            }
            input2.put(tmpArray)
            count++
        }

        count = 0
        //Removed till model is fixed

        for (fa in differentials) {
            var tmpArray = FloatArray(6)
            for (i in fa.indices) {
                if(diffStd[count][i] == 0f)
                    tmpArray[i] = 0f
                else
                    tmpArray[i] = normalizeValue(fa[i], diffMean[count][i], diffStd[count][i])
            }
            input3.put(tmpArray)
            count++
        }
        /*
        for (fa in differentials){
            input3.put(0f)
        }
        */


        /*
        for (j in respeckBuffer[0].indices)
            for (i in respeckBuffer.indices) {
                input1.put(respeckBuffer[i][j])
                input2.put(fourierTransform[i][j])
                input3.put(differentials[i][j])
            }
        */

        input1.rewind()
        input2.rewind()
        input3.rewind()

        val output = HashMap<Int, Any>()

        output[0] = FloatBuffer.allocate(4)
        output[1] = FloatBuffer.allocate(11)

        println("Raw Input: ${input1.array().contentToString()}")
        println("Raw Input Size: ${input1.array().size}")
        println("FT Input: ${input2.array().contentToString()}, Size = ${input2.array().size}")
        println("FT Input Size: ${input2.array().size}")
        println("Differential Input: ${input3.array().contentToString()}, Size = ${input3.array().size}")
        println("Differential Input Size: ${input3.array().size}")

        input1.rewind()
        input2.rewind()
        input3.rewind()
        tflite.runForMultipleInputsOutputs(arrayOf(input1, input2, input3), output)

        //translate 1st output to activity string.
        val output1 = Utils.maxIndex(output[0] as FloatBuffer)
        val output2 = Utils.maxIndex(output[1] as FloatBuffer)

        val breathing = breathingName(output1)
        val activity = if (isStationaryOutput(output2)) {
            val xa = respeckBuffer.sumOf { it[0].toDouble() }
            val ya = respeckBuffer.sumOf { it[1].toDouble() }
            val za = respeckBuffer.sumOf { it[2].toDouble() }
            poseName(stationaryPoseId(xa, ya, za))
        } else
            activityName(output2)

        for (i in 0..3)
            println("Probability of ${breathingName(i)}: ${(output[0] as FloatBuffer).get(i)}")

        for (i in 0..10)
            println("Probability of ${activityName(i)}: ${(output[1] as FloatBuffer).get(i)}")

        updateText(breathing, activity)
        println("Analysis time: ${System.currentTimeMillis() - timeStart} ms")
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
    fun updateGraph(graph: String, x: Float, y: Float, z: Float, gyroX: Float, gyroY: Float, gyroZ: Float) {
        // take the first element from the queue
        // and update the graph with it
        if (graph == "respeck") {
            dataSet_res_accel_x.addEntry(Entry(time, x))
            dataSet_res_accel_y.addEntry(Entry(time, y))
            dataSet_res_accel_z.addEntry(Entry(time, z))
            dataSet_res_gyro_x.addEntry(Entry(time, gyroX))
            dataSet_res_gyro_y.addEntry(Entry(time, gyroY))
            dataSet_res_gyro_z.addEntry(Entry(time, gyroZ))

            runOnUiThread {
                allRespeckData.notifyDataChanged()
                respeckChart.notifyDataSetChanged()
                respeckChart.invalidate()
                respeckChart.setVisibleXRangeMaximum(150f)
                respeckChart.moveViewToX(respeckChart.lowestVisibleX + 40)
            }
        }
    }

    private fun fftAmplitude(input: Array<FloatArray>): Array<FloatArray> {
        val transformer = FastFourierTransformer(DftNormalization.STANDARD)
        val output = Array(input.size) { FloatArray(input[0].size) }

        for (column in input[0].indices) {
            val fftInput = DoubleArray(Integer.highestOneBit(input.size - 1) * 2)
                { if (it < input.size) input[it][column].toDouble() else 0.0 }

            val fftOutput = transformer.transform(fftInput, TransformType.FORWARD)

            for (row in output.indices)
                output[row][column] = fftOutput[row].abs().toFloat()
        }

        return output
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

        for (i in (input.size - 1) downTo 1) {
            subtractFrom(output[i], output[i - 1])
        }

        multiplyBy(output[0], 0f)

        return output
    }

    private fun addTo(modify: FloatArray, other: FloatArray) {
        for (i in modify.indices) {
            modify[i] += other[i]
        }
    }

    private fun subtractFrom(modify: FloatArray, other: FloatArray) {
        for (i in modify.indices) {
            modify[i] -= other[i]
        }
    }

    private fun multiplyBy(modify: FloatArray, scalar: Float) {
        for (i in modify.indices) {
            modify[i] *= scalar
        }
    }

    private fun shiftBufferArray() {
        for (i in 0 until respeckBuffer.size/3) {
            respeckBuffer[i] = respeckBuffer[i + (respeckBuffer.size/3) * 2]
        }
    }

    private fun normalizeValue(x: Float, mean: Float, std:Float) : Float {
        if (std == 0f){
            println("x = $x, mean = $mean, std = $std")
            return 0f
        }
        else {
            return (x - mean) / std
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(respeckAnalysisReceiver)
        looperAnalysis.quit()
    }
}
