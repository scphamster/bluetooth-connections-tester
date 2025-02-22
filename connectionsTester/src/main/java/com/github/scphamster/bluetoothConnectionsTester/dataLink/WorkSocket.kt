package com.github.scphamster.bluetoothConnectionsTester.dataLink

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.io.OutputStream
import java.lang.Long.max
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WorkSocket(val keepAliveMessage: KeepAliveMessage? = null) : DeviceLink {
    companion object {
        private const val Tag = "WorkSocket"
        private const val SOCKET_CONNECTION_TIMEOUT = 10_000
        private const val AUTOMATIC_SOCKET_NUMBER_FLAG = 0
        private const val SOCKET_PERF_BANDWIDTH = 5
        private const val SOCKET_PERF_LATENCY = 10
        private const val SOCKET_PERF_CONNECTION_TIME = 0
    }
    
    data class KeepAliveMessage(val message: Collection<Byte>, val sendPeriodMs: Long)
    class SocketIsDeadException(val msg: String) : Exception(msg)
    
    var port: Int = -1
    
    val isAlive = AtomicBoolean(false)
    override val isReady = AtomicBoolean(false)
    override val id: Int
        get() = port
    override val outputDataChannel = Channel<Collection<Byte>>(Channel.UNLIMITED)
    override val inputDataChannel = Channel<Collection<Byte>>(Channel.UNLIMITED)
    override val lastInputOperationTimeStamp: Long
        get() {
            return lastReadOperationTimeMs.get()
        }
    
    private val lastSendOperationTimeMs = AtomicLong(0)
    private val lastReadOperationTimeMs = AtomicLong(0)
    private val Tag: String
        get() = Companion.Tag + "$port"
    
    private lateinit var serverSocket: ServerSocket
    private lateinit var socket: Socket
    private lateinit var outStream: OutputStream
    private lateinit var inStream: InputStream
    private lateinit var inputJob: Deferred<Unit>
    private lateinit var outputJob: Deferred<Unit>
    
    override suspend fun run() = withContext(Dispatchers.IO) {
        val Tag = Tag + ":MAIN"
        
        Log.d(Tag, "Starting new working socket")
        try {
            serverSocket = ServerSocket(AUTOMATIC_SOCKET_NUMBER_FLAG)
            port = serverSocket.localPort
            
            Log.d(Tag, "New working socket has port number: ${serverSocket.localPort}")
            
            serverSocket.soTimeout = SOCKET_CONNECTION_TIMEOUT
            socket = serverSocket.accept()
            socket.setPerformancePreferences(SOCKET_PERF_CONNECTION_TIME, SOCKET_PERF_LATENCY, SOCKET_PERF_BANDWIDTH)
            socket.keepAlive = true
            
            outStream = socket.getOutputStream()
            inStream = socket.getInputStream()
            
            inputJob = async {
                inputChannelTask()
            }
            outputJob = async {
                outputChannelTask()
            }
            
            val keepAliveJob = if (keepAliveMessage != null) {
                async {
                    keepAliveWriteTask()
                } //                launch {
                //                    keepAliveReadTask()
                //                }
            }
            else null
            
            isReady.set(true)
            val jobs = arrayListOf(inputJob, outputJob)
            
            if (keepAliveJob != null) jobs.add(keepAliveJob)
            
            jobs.awaitAll()
        }
        catch (e: CancellationException) {
            Log.d(Tag, "WorkSocket cancelled due to: ${e.message} : ${e.cause}")
        }
        catch (e: Exception) {
            Log.e(Tag, "Unexpected exception: ${e.message} : ${e.cause}")
        } finally {
            Log.d(Tag, "Returning from worksocket")
            stop()
        }
        
        return@withContext
    }
    
    override fun stop() {
        if (::inputJob.isInitialized && inputJob.isActive) {
            Log.d(Tag, "input job canceling")
            inputJob.cancel("Stop invoked")
        }
        if (::outputJob.isInitialized && outputJob.isActive) {
            Log.d(Tag, "output job canceling")
            outputJob.cancel("Stop invoked")
        }
        
        if (::socket.isInitialized && !socket.isClosed) {
            Log.d(Tag, "closing socket ")
            socket.close()
        }
        
        if (::serverSocket.isInitialized && !serverSocket.isClosed) {
            Log.d(Tag, "server socket is closing")
            serverSocket.close()
            Log.d(Tag, "Server socket is closed")
        }
    }
    
    private fun readN(len: Int): Pair<Array<Byte>, Boolean> {
        val buffer = ByteArray(len)
        var obtained = 0
        while (obtained < len) {
            val nowObtained = inStream.read(buffer, obtained, len - obtained)
            
            if (nowObtained < 0) break;
            
            obtained += nowObtained
        }
        
        return Pair(buffer.toTypedArray(), obtained == len)
    }
    
    private suspend fun keepAliveWriteTask() = withContext(Dispatchers.Default) {
        if (keepAliveMessage == null) return@withContext
        lastSendOperationTimeMs.set(System.currentTimeMillis())
        val Tag = Tag + ":KAWT"
        
        while (isActive) {
            outputDataChannel.send(keepAliveMessage.message)
            delay(keepAliveMessage.sendPeriodMs)
            Log.v(Tag, "KeepAlive sent")
        }
    }

    private suspend fun inputChannelTask() = withContext(Dispatchers.IO) {
        while (isActive) {
            // each message begins with message size in bytes
            val messageSizeBuffer = readN(Int.SIZE_BYTES)
            lastReadOperationTimeMs.set(System.currentTimeMillis())
            
            if (messageSizeBuffer.second != true) {
                Log.e(Tag, "Not obtained all requested bytes!")
                continue
            }

            val messageSize = Int(messageSizeBuffer.first.iterator())
            
            val messageBuffer = readN(messageSize)
            lastReadOperationTimeMs.set(System.currentTimeMillis())
            
            if (!messageBuffer.second) {
                Log.e(Tag, "Not full message was obtained!")
                continue
            }
            Log.d("$Tag:ICT", "New data arrived, size: $messageSize")
            
            inputDataChannel.send(messageBuffer.first.toList())
        }
    }
    
    private suspend fun outputChannelTask() = withContext(Dispatchers.IO) {
        val Tag = Tag + ":OCT"
     
        while (isActive) {
            val result = outputDataChannel.receiveCatching()
            val data = result.getOrNull()
            if (data == null) {
                Log.e(Tag, "data is null!")
                continue
            }
            
            if (data.size == 0) {
                Log.e(Tag, "data size is zero!")
                continue
            }
            
            val bytes = data.size.toByteArray()
                .toMutableList()
            bytes.addAll(data.toList())
            
            for((index, byte) in bytes.withIndex()) {
                Log.v(Tag, "Byte $index : ${byte.toUByte()}")
            }
            
            try {
                outStream.write(bytes.toByteArray())
            }
            catch (e: Exception) {
                Log.e(Tag, "Exception during output stream write: ${e.message}")
                return@withContext
            }
            
            lastSendOperationTimeMs.set(System.currentTimeMillis())
        }
        
        outStream.close()
    }
}