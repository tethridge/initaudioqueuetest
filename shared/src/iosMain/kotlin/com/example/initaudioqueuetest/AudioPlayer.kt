package com.example.initaudioqueuetest

import kotlinx.cinterop.*
import platform.AVFAudio.*

import platform.AudioToolbox.*
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioStreamPacketDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsSignedInteger
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.Foundation.*
import platform.darwin.*
import platform.posix.QOS_CLASS_USER_INITIATED

import kotlin.native.ref.WeakReference


class AudioPlayer {
    private var inFormat: AudioStreamBasicDescription? = null
    private var playerState: PlayerState? = null

    private var selfStableRef: StableRef<AudioPlayer>? = null
    private var playerStateStableRef: StableRef<PlayerState>? = null

    val playbackDispatchQueue: dispatch_queue_t
    val incomingAudioBuffer = Queue<NSData>()

    init {
        val qos: dispatch_queue_attr_t  = dispatch_queue_attr_make_with_qos_class(
            null,
            QOS_CLASS_USER_INITIATED,
            -1
        )
        playbackDispatchQueue = dispatch_queue_create("AudioQueue", qos)
    }

    fun configure(): Boolean {
        initAudioSession()
        initPlayback()
        return true
    }

    private fun initAudioSession(defaultToSpeaker: Boolean = true) {
        println("Initializing audio session")
        val session = AVAudioSession.sharedInstance()

        var options =
            AVAudioSessionCategoryOptionInterruptSpokenAudioAndMixWithOthers or
                    AVAudioSessionCategoryOptionDuckOthers or
                    AVAudioSessionCategoryOptionAllowBluetooth or
                    AVAudioSessionCategoryOptionAllowBluetoothA2DP

        if (defaultToSpeaker) {
            println("Defaulting to use speaker")
            options = options or AVAudioSessionCategoryOptionDefaultToSpeaker
        }

        memScoped {
            val errorVar = alloc<ObjCObjectVar<NSError?>>()
            session.setCategory(
                category = AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeDefault,
                options = options,
                error = errorVar.ptr
            )

            if (errorVar.value != null) {
                println("Setting category to AVAudioSessionCategoryPlayback failed. " +
                        "Error: ${errorVar.value?.localizedDescription}")
            }
        }
    }

    private fun initPlayback() {
        println("init playback")
        val numBuffers = 3

        val asbd = nativeHeap.alloc<AudioStreamBasicDescription>()
        asbd.mSampleRate = 16000.0
        asbd.mFormatID = kAudioFormatLinearPCM
        asbd.mFormatFlags = kAudioFormatFlagIsSignedInteger
        asbd.mFramesPerPacket = 1u
        asbd.mChannelsPerFrame = 1u
        asbd.mBitsPerChannel = 16u
        asbd.mReserved = 0u
        asbd.mBytesPerFrame = asbd.mChannelsPerFrame * Short.SIZE_BYTES.toUInt()
        asbd.mBytesPerPacket = asbd.mFramesPerPacket * asbd.mBytesPerFrame
        inFormat = asbd

        println("asbd.mSampleRate: ${asbd.mSampleRate}")
        println("asbd.mFormatID: ${asbd.mFormatID}")
        println("asbd.mFormatFlags: ${asbd.mFormatFlags}")
        println("asbd.mFramesPerPacket: ${asbd.mFramesPerPacket}")
        println("asbd.mChannelsPerFrame: ${asbd.mChannelsPerFrame}")
        println("asbd.mBitsPerChannel: ${asbd.mBitsPerChannel}")
        println("asbd.mReserved: ${asbd.mReserved}")
        println("asbd.mBytesPerFrame: ${asbd.mBytesPerFrame}")
        println("asbd.mBytesPerPacket: ${asbd.mBytesPerPacket}")

        val ps = PlayerState()
        ps.dataFormat = asbd.ptr
        ps.numBuffers = numBuffers
        ps.parent = WeakReference(this@AudioPlayer)
        this.playerState = ps

        // Create a pointer to this class instance that we can pass through the native layer
        // to the callback. We keep track of it so that we can free the memory when finished
        // playing.
        val selfRef = StableRef.create(this)
        val selfPtr = selfRef.asCPointer()
        saveSelfStableRef(selfRef)

        val playerRef = StableRef.create(ps)
        val playerPtr = playerRef.asCPointer()
        savePlayerStateStableRef(playerRef)

        // Create the output queue
        var status: OSStatus = AudioQueueNewOutput(
            inFormat = asbd.ptr,
            inCallbackProc = staticCFunction(::playingCallback).reinterpret(),
            inUserData = null,
            inCallbackRunLoop = null,
            inCallbackRunLoopMode = null,
            inFlags = 0u,
            outAQ = ps.queue
        )
        assertSuccess(status, tag = "AudioQueueNewOutput")

        println("Initialized the output audio queue")

        if (isFormatVBR(asbd)) {
            ps.packetDescs = nativeHeap.alloc<AudioStreamPacketDescription>().ptr
        } else {
            ps.packetDescs = null
        }

        // Initialize the audio buffers used by the audio queue.
        ps.isRunning = true
        ps.currentPacket = 0

        val seconds = 40 / 1000

        val queue: AudioQueueRef? = ps.queue?.getPointer(MemScope())?.pointed?.value
        if (queue == null) {
            println("Unable to initialize player. AudioQueueRef is null.")
            return
        }
        val bufferSize = deriveBufferSize(
            audioQueue = queue,
            asbd = asbd, //ps.dataFormat,
            seconds = seconds.toDouble()
        )
        println("Output buffer size is: $bufferSize")

        for (i in 0..numBuffers) {
            val bufferRef = nativeHeap.alloc<AudioQueueBufferRefVar>()
            ps.buffers.add(bufferRef)
            status = AudioQueueAllocateBuffer(
                queue,
                bufferSize,
                bufferRef.ptr
            )
            assertSuccess(status, tag = "AudioQueueAllocateBuffer")
            println("Allocated buffer for buffer ${i + 1}")

            status = AudioQueueAddPropertyListener(
                queue,
                kAudioQueueProperty_IsRunning,
                staticCFunction(::audioQueueRunningListener).reinterpret(),
                selfPtr
            )
            assertSuccess(status, tag = "AudioQueueAddPropertyListener")

            playingCallback(playerPtr, queue, bufferRef.pointed?.ptr)
        }
    }

    private fun deriveBufferSize(
        audioQueue: AudioQueueRef,
        asbd: AudioStreamBasicDescription,
        seconds: Double
    ): UInt {
        val maxBufferSize = 0x5000.toDouble()
        var maxPacketSize = asbd.mBytesPerPacket

        // If max packet size isn't set, then query the system for the max size.
        if (maxPacketSize == 0u) {
            memScoped {
                val packetSize = alloc<UInt32Var>()
                val maxVBRPacketSize = alloc<UInt32Var>()
                maxVBRPacketSize.value = Short.SIZE_BYTES.toUInt()
                AudioQueueGetProperty(
                    audioQueue,
                    kAudioQueueProperty_MaximumOutputPacketSize,
                    packetSize.ptr,
                    maxVBRPacketSize.ptr
                )
                maxPacketSize = packetSize.value
            }
        }

        val numBytesForTime = asbd.mSampleRate * maxPacketSize.toDouble() * seconds
        return if (numBytesForTime < maxBufferSize) numBytesForTime.toUInt() else maxBufferSize.toUInt()
    }

    /**
     * Return whether or not a variable bit rate is being used by inspecting.
     *
     * @param asbd AudioStreamBasicDescription A valid instance of object
     * @return Whether or not a variable bit rate is being used.
     */
    private fun isFormatVBR(asbd: AudioStreamBasicDescription): Boolean {
        return asbd.mBytesPerFrame == 0u || asbd.mFramesPerPacket == 0u
    }

    private fun saveSelfStableRef(ref: StableRef<AudioPlayer>) {
        selfStableRef?.dispose()
        selfStableRef = null
        selfStableRef = ref
    }

    private fun savePlayerStateStableRef(ref: StableRef<PlayerState>) {
        playerStateStableRef?.dispose()
        playerStateStableRef = null
        playerStateStableRef = ref
    }

}

private fun assertSuccess(status: OSStatus, tag: String) {
    if (noErr != status.toUInt()) {
        println("System call to $tag failed with error: ")
        when (status) {
            kAudioQueueErr_InvalidBuffer ->
                println("The specified audio queue buffer does not belong to the specified audio queue.\n" +
                        "kAudioQueueErr_BufferEmpty\n" +
                        "The audio queue buffer is empty (that is, the mAudioDataByteSize field = 0).")
            kAudioQueueErr_DisposalPending ->
                println("The function cannot act on the audio queue because it is being asynchronously disposed of.")
            kAudioQueueErr_InvalidProperty ->
                println("The specified property ID is invalid.")
            kAudioQueueErr_InvalidPropertySize ->
                println("The size of the specified property is invalid.")
            kAudioQueueErr_InvalidParameter ->
                println("The specified parameter ID is invalid.")
            kAudioQueueErr_CannotStart ->
                println("The audio queue has encountered a problem and cannot start.")
            kAudioQueueErr_InvalidDevice ->
                println("The specified audio hardware device could not be located.")
            kAudioQueueErr_BufferInQueue ->
                println("The audio queue buffer cannot be disposed of when it is enqueued.")
            kAudioQueueErr_InvalidRunState ->
                println("The queue is running but the function can only operate on the queue when it is stopped, or vice versa.")
            kAudioQueueErr_InvalidQueueType ->
                println("The queue is an input queue but the function can only operate on an output queue, or vice versa.")
            kAudioQueueErr_Permissions ->
                println("You do not have the required permissions to call the function.")
            kAudioQueueErr_InvalidPropertyValue ->
                println("The property value used is not valid.")
            kAudioQueueErr_PrimeTimedOut ->
                println("During a call to the AudioQueuePrime function, the audio queue’s audio converter failed to convert the requested number of sample frames.")
            kAudioQueueErr_CodecNotFound ->
                println("The requested codec was not found.")
            kAudioQueueErr_InvalidCodecAccess ->
                println("The codec could not be accessed.")
            kAudioQueueErr_QueueInvalidated ->
                println("In iOS, the audio server has exited, causing the audio queue to become invalid.")
            kAudioQueueErr_RecordUnderrun ->
                println("During recording, data was lost because there was no enqueued buffer to store it in.")
            kAudioQueueErr_EnqueueDuringReset ->
                println("During a call to the AudioQueueReset, AudioQueueStop, or AudioQueueDispose functions, the system does not allow you to enqueue buffers.")
            kAudioQueueErr_InvalidOfflineMode ->
                println("The operation requires the audio queue to be in offline mode but it isn’t, or vice versa.")
            kAudioFormatUnsupportedDataFormatError ->
                println("The playback data format is unsupported (declared in AudioFormat.h).")
            else ->
                println("Unknown code returned: $status (toUInt: ${status.toUInt()})")
        }
        assert(noErr == status.toUInt())
    }
}
fun playingCallback(aqDataPointer: CPointer<out CPointed>?,
                            inputAudioQueue: AudioQueueRef?,
                            inBuffer: AudioQueueBufferRef?) {
    println("playing callback called")
}

fun audioQueueRunningListener(clientData: CPointer<out CPointed>?,
                              inputAudioQueue: AudioQueueRef?,
                              propertyID: AudioQueuePropertyID) {
    println("In audioQueueRunningListener")
}

class PlayerState {
    var dataFormat: CValuesRef<AudioStreamBasicDescription>? = null
    var parent: WeakReference<AudioPlayer>? = null
    var numBuffers: Int = 3
    var queue: CValuesRef<AudioQueueRefVar>? = null //
    var buffers = mutableListOf<AudioQueueBufferRefVar>()
    var currentPacket: Long? = 0L
    var packetDescs: CValuesRef<AudioStreamPacketDescription>? = null
    var isRunning: Boolean = false
}