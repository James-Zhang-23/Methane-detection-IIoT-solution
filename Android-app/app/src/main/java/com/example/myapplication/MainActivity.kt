package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AppCompatActivity
import android.webkit.URLUtil
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.gson.Gson
import com.journeyapps.barcodescanner.CaptureActivity
import okhttp3.*
import java.io.IOException
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import kotlinx.coroutines.*



class MainActivity : AppCompatActivity(){

    private val requestCodeForQRCode = 2
    private val requestCodeForPermission = 3
    private lateinit var deviceIDText: TextView
    private lateinit var longitudeText: TextView
    private lateinit var latitudeText: TextView
    private lateinit var thingsboardURL: TextView
    private lateinit var httpResponse: TextView
    private lateinit var azimuthText: TextView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    data class ThingsBoardData (
        var lat: Double = 0.0,
        var long: Double = 0.0,
        var azimuth: Double = 0.0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // render layouts
        setContentView(R.layout.activity_main)

        val btnScan = findViewById<Button>(R.id.buttonScan)
        val btnClear = findViewById<Button>(R.id.buttonClear)

        longitudeText = findViewById(R.id.longitudeVal)
        latitudeText = findViewById(R.id.latitudeVal)
        deviceIDText = findViewById(R.id.deviceVal)
        thingsboardURL = findViewById(R.id.thingsboardURL)
        httpResponse = findViewById(R.id.httpResponse)
        azimuthText = findViewById(R.id.azimuthVal)


        val pref = getSharedPreferences("data", MODE_PRIVATE)
        mapOf(
            thingsboardURL to "thingsboardURL",
            deviceIDText to "deviceID",
            latitudeText to "latitude",
            longitudeText to "longitude"
        ).forEach { (s, p) ->
            s.text = pref.getString("Text${s.id}", p)
        }


        val policy = ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        // request permissions
        val permissionList = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (permissionList.any {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            requestPermissions(permissionList, requestCodeForPermission)
        }

        // get location service
        locationManager =
            applicationContext.getSystemService(LOCATION_SERVICE) as LocationManager


        btnScan.setOnClickListener {
            // scan qr code
            val intent = Intent(applicationContext, CaptureActivity::class.java)
            intent.action = "com.google.zxing.client.android.SCAN"
            mapOf(
                "SAVE_HISTORY" to false,
                "SCAN_MODE" to "QR_CODE_MODE",
                "PROMPT_MESSAGE" to "Scan QR code for IoT",
                "BEEP_ENABLED" to false
            ).forEach { (s, p) ->
                intent.putExtra(s, p)
            }

            // get gps location
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                // GPS provider is enabled, try getting the location
                val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                location?.let {
                    longitudeText.text = location.longitude.toString()
                    latitudeText.text = location.latitude.toString()
                }
            } else {
                // GPS provider is not enabled, prompt user to enable it
                Toast.makeText(this, "Please enable GPS", Toast.LENGTH_SHORT).show()
            }

            val minTime: Long = 5000 // Minimum time between updates (in milliseconds)
            val minDistance: Float = 10f // Minimum distance between updates (in meters)
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    // Handle the new location here
                    longitudeText.text = location.longitude.toString()
                    latitudeText.text = location.latitude.toString()
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTime, minDistance, locationListener)

            startActivityForResult(intent, requestCodeForQRCode)
        }

        btnClear.setOnClickListener {
            longitudeText.text = "Empty"
            latitudeText.text = "Empty"
            azimuthText.text = "Empty"
            deviceIDText.text = "Empty"
        }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) when (requestCode) {
            requestCodeForQRCode -> {
                    deviceIDText.text = data?.getStringExtra("SCAN_RESULT")
            }
        }


        // compass reading
        // Initialize the sensor manager and the compass sensor
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // Register the rotation vector sensor listener with a single function
        sensorManager.registerListener(object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // We don't need to do anything here
            }

            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    // Get the rotation vector data and convert it to a compass heading
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    val azimuthDegrees = Math.toDegrees(orientation[0].toDouble())
                    val azimuthOutput = if (azimuthDegrees < 0) azimuthDegrees + 360 else azimuthDegrees
                    azimuthText.text = azimuthOutput.toString()

                    // Unregister the sensor event listener to conserve resources
                    sensorManager.unregisterListener(this)
                }
            }
        }, sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_NORMAL)


        // http post
        val payload = Gson().toJson(ThingsBoardData(
            latitudeText.text.toString().toDoubleOrNull() ?: 0.0,
            longitudeText.text.toString().toDoubleOrNull() ?: 0.0,
            azimuthText.text.toString().toDoubleOrNull() ?: 0.0,
        ))
        httpResponse.text = "posting: $payload"
        val rawURL1 = "http://frontgate.tplinkdns.com:8080/api/v1/"
        val rawURL2 = "/attributes"
        val tokenID = deviceIDText.text.toString()
        val postURL = rawURL1 + tokenID +rawURL2

        val okHttpClient = OkHttpClient()
        val requestBody: RequestBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), payload)
        val request = Request.Builder()
            .method("POST", requestBody)
            .url(postURL)
            .addHeader("Content-Type", "application/json")
            .build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    httpResponse.text = e.message
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    httpResponse.text = response.message() + ' ' + response.body()?.string()
                }
            }
        })
    }

    override fun onStop() {
        super.onStop()
        val editor = getSharedPreferences("data", MODE_PRIVATE).edit()
        arrayOf(thingsboardURL, deviceIDText, latitudeText, longitudeText). forEach {
            editor.putString("Text${it.id}", it.text.toString())
        }
        editor.apply()
    }

}