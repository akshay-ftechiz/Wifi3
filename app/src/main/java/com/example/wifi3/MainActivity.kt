package com.example.wifi3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.snackbar.Snackbar
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ThreadPoolExecutor

class MainActivity : AppCompatActivity() {

    private lateinit var view: View
    private lateinit var wifiStateReceiver: BroadcastReceiver
    private lateinit var progressBar: ProgressBar
    private lateinit var sendDataEditText: EditText
    private lateinit var sendDataButton: Button
    private lateinit var wifiConnectionSwitch: SwitchCompat
    private lateinit var arduinoConnectionSwitch: SwitchCompat

    companion object {
        const val SERVER_IP = "192.168.174.147"
        const val SERVER_PORT = 80
    }

    private var socket: Socket? = null
    private var thread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        view = findViewById(R.id.container) // Assuming you have a container in your layout

        wifiConnectionSwitch = findViewById(R.id.wifiConnectionSwitch)
        arduinoConnectionSwitch = findViewById(R.id.arduinoConnectionSwitch)
        progressBar = findViewById(R.id.progressBar)
        sendDataEditText = findViewById(R.id.sendDataEditText)
        sendDataButton = findViewById(R.id.sendDataButton)

        wifiStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ConnectivityManager.CONNECTIVITY_ACTION == intent.action) {
                    val networkInfo: NetworkInfo? =
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true && networkInfo.typeName.contains("WIFI")) {
                        // Wifi is connected
                        Snackbar.make(view, "WiFi is connected", Snackbar.LENGTH_LONG)
                            .setAction("DISMISS") {}.show()
                        wifiConnectionSwitch.isChecked = true
                    } else {
                        Snackbar.make(view, "WiFi is dis-Connected", Snackbar.LENGTH_LONG)
                            .setAction("DISMISS") {}.show()
                        wifiConnectionSwitch.isChecked = false
                    }
                    Log.e("TAG", "onReceive: ${networkInfo.toString()}")
                }
            }
        }
        arduinoConnectionSwitch.setOnCheckedChangeListener(socketSwitchListener())
        sendDataButton.setOnClickListener { sendData() }
    }

    private fun socketSwitchListener(): CompoundButton.OnCheckedChangeListener {
        return CompoundButton.OnCheckedChangeListener { button, isChecked ->
            if (isChecked) {
                button.error = null
                connectToArduino()
            } else {
                disconnectArduino()
            }
        }
    }
    private fun silentlySwitchArduino(checked: Boolean) {
        arduinoConnectionSwitch.setOnClickListener(null)
        arduinoConnectionSwitch.isChecked = checked
        arduinoConnectionSwitch.setOnCheckedChangeListener(socketSwitchListener())
    }
    private fun checkSwitches() {
        if (!wifiConnectionSwitch.isChecked) {
            arduinoConnectionSwitch.error = "Here"
            Snackbar.make(view, "Please check wifi Connection!", Snackbar.LENGTH_LONG).show()
        }
        if (!arduinoConnectionSwitch.isChecked) {
            arduinoConnectionSwitch.error = "Here"
            Snackbar.make(view, "Please check Socket Connection!", Snackbar.LENGTH_LONG).show()
        }
    }
    private fun sendData() {
        val message = sendDataEditText.text.toString()
        showProgressBar()
        object :AsyncTask<Void,Void,Void>()
        {
            override fun doInBackground(vararg params: Void?): Void? {
                try {
                    if (socket != null) {
                        val out = PrintWriter(
                            BufferedWriter(OutputStreamWriter(socket!!.getOutputStream())), true
                        )
                        out.println(message)
                        out.flush()
//                        out.close()
                        runOnUiThread {
                            sendDataEditText.setText("")
                        }
                    } else {
                        runOnUiThread {
                            checkSwitches()

                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        checkSwitches()
                        Snackbar.make(view, "Error: $e", Snackbar.LENGTH_LONG).show()
                        e.printStackTrace()
                    }
                } finally {
                    runOnUiThread {
                        hideProgressBar()
                    }
                }
                return null ;
            }

        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        Log.d("@connection",""+socket?.isConnected+"closed"+socket?.isClosed)

    }
    private fun connectToArduino(): Boolean {
        if (socket == null) {
            if (thread?.isAlive == true) {
                thread?.interrupt()
                thread = null
            }
        }


        return if (socket == null && thread == null) {
            thread = Thread(ClientThread())
            thread!!.start()
            true
        } else {
            Toast.makeText(this@MainActivity, "Already Connected", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun disconnectArduino() {
        try {
            thread?.interrupt()
            thread = null
            if (socket != null) {
                socket!!.close()
                socket = null
                Snackbar.make(view, "Disconnected Socket", Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(view, "Socket was not connected properly", Snackbar.LENGTH_LONG)
                    .show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Snackbar.make(view, "ERROR : $e", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun hideProgressBar() {
        progressBar.visibility = View.INVISIBLE
    }

    private fun showProgressBar() {
        progressBar.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter()
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(wifiStateReceiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(wifiStateReceiver)
    }
    internal inner class ClientThread : Runnable {
        override fun run() {
            Log.e("TAG", "ClientThread Running")
            try {
                val serverAddress: InetAddress = InetAddress.getByName(SERVER_IP)
                Log.e("TAG", "Attempting socket connection...")
//                socket = Socket(serverAddress, SERVER_PORT)


                // Open the socket and connect to it
                socket = Socket()
                socket!!.connect(InetSocketAddress(serverAddress, SERVER_PORT))
                Log.e("@scoket", "Socket connection established."+ socket?.isClosed+"connected"+socket?.isConnected)
                runOnUiThread {
                    silentlySwitchArduino(true)
                    Snackbar.make(view, "Connected to socket", Snackbar.LENGTH_LONG).show()
                }
            } catch (e1: UnknownHostException) {
                Log.e("TAG", "UnknownHostException: $e1")
                runOnUiThread {
                    silentlySwitchArduino(false)
                    Snackbar.make(view, "UnknownHostException: $e1", Snackbar.LENGTH_LONG).show()
                }
                e1.printStackTrace()
            } catch (e1: IOException) {
                Log.e("TAG", "IOException: $e1")
                runOnUiThread {
                    silentlySwitchArduino(false)
                    Snackbar.make(view, "IOException: $e1", Snackbar.LENGTH_LONG).show()
                }
                e1.printStackTrace()
            }catch (e1: RuntimeException) {
                Log.e("TAG", "RuntimeException: $e1")
                runOnUiThread {
                    silentlySwitchArduino(false)
                    Snackbar.make(view, "IOException: $e1", Snackbar.LENGTH_LONG).show()
                }
                e1.printStackTrace()
            }
        }
    }
}
