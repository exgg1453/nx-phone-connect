package com.nxteam.nxconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class HostService : Service() {
    @Volatile
    private var authenticatedClient: FramedSocket? = null
    private var serverSocket: ServerSocket? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var pairingCode = ""
    private var lastFrameTime = 0L
    private var paddedBitmap: Bitmap? = null
    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val isSendingFrame = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        pairingCode = intent.getStringExtra(EXTRA_PAIRING_CODE).orEmpty()
        startForegroundNotification()

        @Suppress("DEPRECATION")
        val permissionData = intent.getParcelableExtra<Intent>(EXTRA_PERMISSION_DATA)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        if (permissionData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val thread = HandlerThread("NXPhoneConnectCapture")
        thread.start()
        captureThread = thread
        val handler = Handler(thread.looper)

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = manager.getMediaProjection(resultCode, permissionData)
        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
        projection = newProjection

        startServer()
        startCapture(newProjection, handler)
        isRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        registrationListener?.let {
            try {
                getSystemService(NsdManager::class.java).unregisterService(it)
            } catch (exception: IllegalArgumentException) {
            }
        }
        authenticatedClient?.close()
        authenticatedClient = null
        try {
            serverSocket?.close()
        } catch (exception: IOException) {
        }
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        captureThread?.quitSafely()
        frameExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_IDENTIFIER, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_IDENTIFIER)
            .setContentTitle("NX Phone Connect")
            .setContentText("Sharing this device")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_IDENTIFIER, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_IDENTIFIER, notification)
        }
    }

    private fun startServer() {
        val newServerSocket = ServerSocket(0)
        serverSocket = newServerSocket

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = Build.MODEL
            serviceType = "_nxconnect._tcp."
            port = newServerSocket.localPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        getSystemService(NsdManager::class.java).registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)

        Thread {
            while (true) {
                val socket = try {
                    newServerSocket.accept()
                } catch (exception: IOException) {
                    break
                }
                Thread { serve(FramedSocket(socket)) }.start()
            }
        }.start()
    }

    private fun serve(client: FramedSocket) {
        client.setReadTimeout(HANDSHAKE_TIMEOUT)
        val first = client.readMessage()
        val receivedCode = first?.second?.toString(Charsets.UTF_8)
        if (first?.first != MessageKind.HELLO || receivedCode != pairingCode) {
            client.send(MessageKind.REJECTED)
            client.close()
            return
        }
        client.setReadTimeout(0)
        authenticatedClient?.close()
        authenticatedClient = client
        client.send(MessageKind.ACCEPTED)

        while (true) {
            val message = client.readMessage() ?: break
            val service = RemoteControlAccessibilityService.instance ?: continue
            try {
                val json = JSONObject(message.second.toString(Charsets.UTF_8))
                when (message.first) {
                    MessageKind.TOUCH -> service.handleTouch(
                        json.getString("phase"),
                        json.getDouble("normalizedHorizontalPosition").toFloat(),
                        json.getDouble("normalizedVerticalPosition").toFloat()
                    )
                    MessageKind.KEY -> service.handleKey(json.getString("name"))
                    else -> {}
                }
            } catch (exception: org.json.JSONException) {
            }
        }
        if (authenticatedClient === client) {
            authenticatedClient = null
        }
        client.close()
    }

    private fun startCapture(activeProjection: MediaProjection, handler: Handler) {
        val metrics = resources.displayMetrics
        val scale = min(1f, MAXIMUM_FRAME_WIDTH / metrics.widthPixels)
        val width = (metrics.widthPixels * scale).toInt()
        val height = (metrics.heightPixels * scale).toInt()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = activeProjection.createVirtualDisplay(
            "NXPhoneConnect",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage()
            if (image != null) {
                try {
                    sendFrame(image)
                } finally {
                    image.close()
                }
            }
        }, handler)
    }

    private fun sendFrame(image: Image) {
        val client = authenticatedClient ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameTime < MINIMUM_FRAME_INTERVAL || isSendingFrame.get()) {
            return
        }
        lastFrameTime = now

        val plane = image.planes[0]
        val paddedWidth = plane.rowStride / plane.pixelStride
        val bitmap = paddedBitmap?.takeIf { it.width == paddedWidth && it.height == image.height }
            ?: Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888).also {
                paddedBitmap?.recycle()
                paddedBitmap = it
            }
        bitmap.copyPixelsFromBuffer(plane.buffer)
        val frameBitmap = if (paddedWidth == image.width) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        }
        val stream = ByteArrayOutputStream()
        frameBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        if (frameBitmap !== bitmap) {
            frameBitmap.recycle()
        }
        val frameData = stream.toByteArray()
        isSendingFrame.set(true)
        frameExecutor.execute {
            client.send(MessageKind.FRAME, frameData)
            isSendingFrame.set(false)
        }
    }

    companion object {
        const val ACTION_STOP = "com.nxteam.nxconnect.STOP"
        const val EXTRA_PAIRING_CODE = "pairingCode"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_PERMISSION_DATA = "permissionData"

        @Volatile
        var isRunning = false

        private const val CHANNEL_IDENTIFIER = "screen_sharing"
        private const val NOTIFICATION_IDENTIFIER = 1
        private const val MAXIMUM_FRAME_WIDTH = 720f
        private const val MINIMUM_FRAME_INTERVAL = 200L
        private const val JPEG_QUALITY = 45
        private const val HANDSHAKE_TIMEOUT = 10_000
    }
}
