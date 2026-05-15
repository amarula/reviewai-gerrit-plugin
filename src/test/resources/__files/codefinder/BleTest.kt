package com.example.testapp

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.example.testapp.ble.SampleBleConnectivityListener
import com.example.testapp.ble.SampleBleManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampleBleTestChunk : SampleBleConnectivityListener {

    private val defaultTimeoutSeconds = 10L
    private lateinit var appContext: Context
    private lateinit var bleManager: SampleBleManager
    private lateinit var bleAdapter: BluetoothAdapter
    private val connectedLatch = CountDownLatch(1)

    @Before
    fun before() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        bleAdapter =
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bleManager = SampleBleManager(bleAdapter)
        bleManager.registerBleConnectivityListener(this)
        bleManager.scanDevice()
        connectedLatch.await(defaultTimeoutSeconds, TimeUnit.SECONDS)
    }

    @After
    fun after() {
        bleManager.disconnectDevice()
    }
}
