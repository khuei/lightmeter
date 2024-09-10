package com.example.lightmeter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.lightmeter.ui.theme.LightMeterTheme

class MainActivity : ComponentActivity(), SensorEventListener {

	private lateinit var sensorManager: SensorManager
	private var lightSensor: Sensor? = null
	private var luxValue: Float = 0f

	private lateinit var cameraManager: CameraManager
	private var captureRequestBuilder: CaptureRequest.Builder? = null

	// Define ISO and aperture steps
	private val isoSteps = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600)
	private val apertureSteps = listOf(1.8f, 2.8f, 4f, 5.6f, 8f, 11f, 16f, 22f)

	@Composable
	fun CameraControlScreen() {
		var isoIndex by remember { mutableIntStateOf(0) }  // Index in the isoSteps list
		var apertureIndex by remember { mutableIntStateOf(0) }  // Index in the apertureSteps list

		val currentIso = isoSteps[isoIndex]
		val currentAperture = apertureSteps[apertureIndex]

		// Calculate shutter speed
		val shutterSpeed = calculateShutterSpeed(luxValue, currentIso, currentAperture)

		// Convert shutter speed to "1/time" format
		val shutterSpeedText = if (shutterSpeed >= 1) {
			String.format("%.1fs", shutterSpeed) // If shutter speed is slower than 1 second
		} else {
			"1/${(1 / shutterSpeed).toInt()}" // Convert to fractional form if faster than 1 second
		}

		Column(
			modifier = Modifier
			.fillMaxSize()
			.padding(16.dp),
			verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally
		) {
			// Display Lux value
			Text(
				text = "Lux: ${luxValue.toInt()}",
				style = MaterialTheme.typography.headlineMedium,
				modifier = Modifier.padding(8.dp)
			)

			Spacer(modifier = Modifier.height(16.dp))

			// ISO Control
			Text(
				text = "ISO: ${isoSteps[isoIndex]}",
				style = MaterialTheme.typography.titleLarge,
				modifier = Modifier.padding(8.dp)
			)
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Button(
					onClick = {
						if (isoIndex > 0) isoIndex--
					},
					enabled = isoIndex > 0
				) {
					Text("Decrease ISO")
				}

				Button(
					onClick = {
						if (isoIndex < isoSteps.size - 1) isoIndex++
					},
					enabled = isoIndex < isoSteps.size - 1
				) {
					Text("Increase ISO")
				}
			}

			Spacer(modifier = Modifier.height(16.dp))

			// Aperture Control
			Text(
				text = "Aperture: f/${apertureSteps[apertureIndex]}",
				style = MaterialTheme.typography.titleLarge,
				modifier = Modifier.padding(8.dp)
			)
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Button(
					onClick = {
						if (apertureIndex > 0) apertureIndex--
					},
					enabled = apertureIndex > 0
				) {
					Text("Decrease Aperture")
				}

				Button(
					onClick = {
						if (apertureIndex < apertureSteps.size - 1) apertureIndex++
					},
					enabled = apertureIndex < apertureSteps.size - 1
				) {
					Text("Increase Aperture")
				}
			}

			Spacer(modifier = Modifier.height(16.dp))

			// Display calculated shutter speed
			Text(
				text = "Shutter Speed: $shutterSpeedText",
				style = MaterialTheme.typography.headlineMedium,
				modifier = Modifier.padding(8.dp)
			)

			// Update camera settings based on the selected ISO and aperture values
			LaunchedEffect(isoIndex, apertureIndex) {
				updateCameraSettings(isoSteps[isoIndex], apertureSteps[apertureIndex])
			}
		}
	}


	// Function to calculate shutter speed based on lux, ISO, and aperture
	private fun calculateShutterSpeed(lux: Float, iso: Int, aperture: Float): Double {
		// Base EV (Assuming EV = 0 for ISO 100 and aperture f/1.0 at lux = 1)
		val baseEv = 0

		// Calculate EV based on ISO
		val ev = baseEv + Math.log(iso / 100.0) / Math.log(2.0)

		// Shutter speed (in seconds)
		// Shutter Speed T = (N^2) / (L * 2^EV)
		val shutterSpeed = (aperture * aperture) / (lux * Math.pow(2.0, ev))
		return shutterSpeed
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			LightMeterTheme {
				// A surface container using the 'background' color from the theme
				Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					CameraControlScreen()
				}
			}
		}

		sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
		lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
		cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

		// Request camera permission if not already granted
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
		} else {
			startSensor()
			setupCamera()
		}
	}

	private fun startSensor() {
		lightSensor?.also { light ->
			sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL)
		}
	}

	private fun setupCamera() {
		val cameraId = cameraManager.cameraIdList[0]
		try {
			val characteristics = cameraManager.getCameraCharacteristics(cameraId)
			characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
			?.getOutputSizes(android.graphics.ImageFormat.JPEG)

			if (ActivityCompat.checkSelfPermission(
				this,
				Manifest.permission.CAMERA
			) != PackageManager.PERMISSION_GRANTED
		) {
			// TODO: Consider calling
			//    ActivityCompat#requestPermissions
			// here to request the missing permissions, and then overriding
			//   public void onRequestPermissionsResult(int requestCode, String[] permissions,
			//                                          int[] grantResults)
			// to handle the case where the user grants the permission. See the documentation
			// for ActivityCompat#requestPermissions for more details.
			return
		}
		cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
			override fun onOpened(camera: CameraDevice) {
				captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
			}

			override fun onDisconnected(camera: CameraDevice) {
				camera.close()
			}

			override fun onError(camera: CameraDevice, error: Int) {
				Toast.makeText(this@MainActivity, "Camera error: $error", Toast.LENGTH_SHORT).show()
			}
		}, null)
	} catch (e: CameraAccessException) {
		e.printStackTrace()
	}
}

private fun updateCameraSettings(iso: Int, aperture: Float) {
	val shutterSpeed = calculateShutterSpeed(luxValue, iso, aperture)

	captureRequestBuilder?.apply {
		set(CaptureRequest.SENSOR_SENSITIVITY, iso)
		set(CaptureRequest.LENS_APERTURE, aperture)
		// Convert shutter speed from seconds to nanoseconds
		set(CaptureRequest.SENSOR_EXPOSURE_TIME, (shutterSpeed * 1_000_000_000).toLong())
	}
}


override fun onSensorChanged(event: SensorEvent?) {
	event?.let {
		if (it.sensor.type == Sensor.TYPE_LIGHT) {
			luxValue = it.values[0]
			// Update UI
			setContent {
				LightMeterTheme {
					Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
						CameraControlScreen()
					}
				}
			}
		}
	}
}

override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
	// Do nothing
}

override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
	super.onRequestPermissionsResult(requestCode, permissions, grantResults)
	if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
		startSensor()
		setupCamera()
	}
}

override fun onDestroy() {
	super.onDestroy()
	sensorManager.unregisterListener(this)
}
}
