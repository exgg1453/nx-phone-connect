package com.nxteam.nxconnect

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class ControllerActivity : Activity() {
    private val discoveredServices = linkedMapOf<String, NsdServiceInfo>()
    private val hostNames = ArrayList<String>()
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private lateinit var nsdManager: NsdManager
    private lateinit var hostAdapter: ArrayAdapter<String>
    private lateinit var formLayout: LinearLayout
    private lateinit var remoteLayout: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var codeField: EditText
    private lateinit var frameView: ImageView
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var selectedName: String? = null
    private var connection: FramedSocket? = null
    private var lastMoveTime = 0L
    @Volatile
    private var isDisplayingFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsdManager = getSystemService(NsdManager::class.java)
        hostAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, hostNames)

        val hostList = ListView(this).apply {
            adapter = hostAdapter
            choiceMode = ListView.CHOICE_MODE_SINGLE
            setOnItemClickListener { _, _, position, _ -> selectedName = hostNames[position] }
        }
        codeField = EditText(this).apply {
            hint = "6-digit pairing code"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        statusView = TextView(this)
        val connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { connectToSelectedHost() }
        }
        val warningView = TextView(this).apply {
            text = "Warning: an iPhone can be viewed but not controlled from Android. iOS does not allow remote input."
            setTextColor(Color.rgb(200, 120, 0))
        }
        formLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(this@ControllerActivity).apply { text = "Devices on your network" })
            addView(hostList, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(codeField)
            addView(connectButton)
            addView(statusView)
            addView(warningView)
        }

        frameView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnTouchListener { view, event -> handleTouch(view as ImageView, event) }
        }
        remoteLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(frameView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(this@ControllerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(keyButton("Back", "back"))
                addView(keyButton("Home", "home"))
                addView(keyButton("Recents", "recents"))
                addView(Button(this@ControllerActivity).apply {
                    text = "Disconnect"
                    setOnClickListener { disconnect() }
                })
            })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(formLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
            addView(remoteLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        })
        startDiscovery()
    }

    override fun onDestroy() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (exception: IllegalArgumentException) {
            }
        }
        connection?.close()
        sendExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun keyButton(title: String, keyName: String): Button {
        return Button(this).apply {
            text = title
            setOnClickListener {
                send(MessageKind.KEY, JSONObject().put("name", keyName).toString().toByteArray())
            }
        }
    }

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runOnUiThread {
                    if (!discoveredServices.containsKey(serviceInfo.serviceName)) {
                        discoveredServices[serviceInfo.serviceName] = serviceInfo
                        hostNames.add(serviceInfo.serviceName)
                        hostAdapter.notifyDataSetChanged()
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                runOnUiThread {
                    discoveredServices.remove(serviceInfo.serviceName)
                    hostNames.remove(serviceInfo.serviceName)
                    hostAdapter.notifyDataSetChanged()
                }
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices("_nxconnect._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun connectToSelectedHost() {
        val service = selectedName?.let { discoveredServices[it] }
        val code = codeField.text.toString()
        if (service == null || code.length != 6) {
            statusView.text = "Select a device and enter the 6-digit code"
            return
        }
        statusView.text = "Connecting..."
        @Suppress("DEPRECATION")
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread { statusView.text = "Could not find the device" }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Thread { openConnection(serviceInfo, code) }.start()
            }
        })
    }

    private fun openConnection(serviceInfo: NsdServiceInfo, code: String) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(serviceInfo.host, serviceInfo.port), CONNECT_TIMEOUT)
        } catch (exception: java.io.IOException) {
            runOnUiThread { statusView.text = "Connection failed" }
            return
        }
        val framed = FramedSocket(socket)
        connection = framed
        framed.send(MessageKind.HELLO, code.toByteArray())
        while (true) {
            val message = framed.readMessage() ?: break
            when (message.first) {
                MessageKind.ACCEPTED -> runOnUiThread { showRemote(true) }
                MessageKind.REJECTED -> runOnUiThread { statusView.text = "Wrong pairing code" }
                MessageKind.FRAME -> {
                    if (isDisplayingFrame) {
                        continue
                    }
                    val bitmap = BitmapFactory.decodeByteArray(message.second, 0, message.second.size)
                    if (bitmap != null) {
                        isDisplayingFrame = true
                        runOnUiThread {
                            frameView.setImageBitmap(bitmap)
                            isDisplayingFrame = false
                        }
                    }
                }
                else -> {}
            }
        }
        framed.close()
        runOnUiThread {
            showRemote(false)
            if (statusView.text != "Wrong pairing code") {
                statusView.text = "Disconnected"
            }
        }
    }

    private fun showRemote(visible: Boolean) {
        remoteLayout.visibility = if (visible) View.VISIBLE else View.GONE
        formLayout.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun disconnect() {
        connection?.close()
        connection = null
        showRemote(false)
    }

    private fun send(kind: MessageKind, payload: ByteArray) {
        val active = connection ?: return
        sendExecutor.execute { active.send(kind, payload) }
    }

    private fun handleTouch(view: ImageView, event: MotionEvent): Boolean {
        val drawable = view.drawable ?: return true
        val values = FloatArray(9)
        view.imageMatrix.getValues(values)
        val scale = values[android.graphics.Matrix.MSCALE_X]
        val horizontal = (event.x - values[android.graphics.Matrix.MTRANS_X]) / (drawable.intrinsicWidth * scale)
        val vertical = (event.y - values[android.graphics.Matrix.MTRANS_Y]) / (drawable.intrinsicHeight * scale)
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "began"
            MotionEvent.ACTION_MOVE -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastMoveTime < MINIMUM_MOVE_INTERVAL) {
                    return true
                }
                lastMoveTime = now
                "moved"
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> "ended"
            else -> return true
        }
        val payload = JSONObject()
            .put("phase", phase)
            .put("normalizedHorizontalPosition", horizontal.toDouble().coerceIn(0.0, 1.0))
            .put("normalizedVerticalPosition", vertical.toDouble().coerceIn(0.0, 1.0))
        send(MessageKind.TOUCH, payload.toString().toByteArray())
        return true
    }

    companion object {
        private const val CONNECT_TIMEOUT = 8000
        private const val MINIMUM_MOVE_INTERVAL = 50L
    }
}
