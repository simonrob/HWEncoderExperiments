package net.openwatch.hwencoderexperiments;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by davidbrodsky on 9/12/13.
 * Enormous thanks to Andrew McFadden for his MediaCodec examples!
 * Adapted from http://bigflake.com/mediacodec/CameraToMpegTest.java.txt
 */
public class ChunkedAvcEncoder {
    private static final String TAG = "ChunkedAvcEncoder";
    private static final String MIME_TYPE = "video/avc";
    private static final boolean VERBOSE = false;
    MediaFormat mediaFormat;
    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private int mTrackIndex;
    private boolean mMuxerStarted;
    // allocate one of these up front so we don't need to do it every time
    private MediaCodec.BufferInfo mBufferInfo;
    boolean eosReceived = false;
    boolean eosSentToEncoder = false;
    boolean stopReceived = false;
    long startTime = 0;

    // Chunk state
    final int CHUNK_DURATION_SEC = 5;
    final int FRAMES_PER_SECOND = 30;
    int currentChunk = 1;
    int frameCount = 0;
    int framesPerChunk = 0;     // calculated
    int totalFrameCount = 0;


    Context c;

    private ExecutorService encodingService = Executors.newSingleThreadExecutor(); // re-use encodingService

    public ChunkedAvcEncoder(Context c) {
        this.c = c;
        prepare();
    }

    private void prepare(){
        frameCount = 0;
        eosReceived = false;
        eosSentToEncoder = false;
        framesPerChunk = CHUNK_DURATION_SEC * FRAMES_PER_SECOND;
        File f = FileUtils.createTempFileInRootAppStorage(c, "test_" + currentChunk + ".mp4");

        mBufferInfo = new MediaCodec.BufferInfo();

        mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, 640, 480);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 250000);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAMES_PER_SECOND);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // COLOR_TI_FormatYUV420PackedSemiPlanar works - wrong hue
        // COLOR_FormatYUV420PackedSemiPlanar - crash
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();

        try {
            mMuxer = new MediaMuxer(f.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }

        mTrackIndex = -1;
        mMuxerStarted = false;
    }

    public void stop(){
        if(!encodingService.isShutdown())
            encodingService.submit(new EncoderTask(this, EncoderTaskType.FINALIZE_ENCODER));
    }

    /**
     * Called from encodingService
     */
    public void _stop(){
        stopReceived = true;
        eosReceived = true;
        Log.i(TAG, "ChunkedAVEnc saw #frames: " + totalFrameCount);
    }

    public void close() {
        drainEncoder(true);
        try {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    long lastFrameTime = -1;
    /**
     * External method called by CameraPreviewCallback or similar
     * @param input
     */
    public void offerEncoder(byte[] input){
        if(!encodingService.isShutdown()){
            long thisFrameTime = System.nanoTime();
            encodingService.submit(new EncoderTask(this, input, null, thisFrameTime));
            lastFrameTime = System.nanoTime();
        }

    }

    /**
     * Internal method called by encodingService
     * @param input
     */
    private void _offerEncoder(byte[] input, long presentationTimeNs) {
        if(frameCount == 0)
            startTime = presentationTimeNs;
        if(eosSentToEncoder && stopReceived){
            return;
        }

        totalFrameCount ++;
        frameCount ++;

        if(frameCount % framesPerChunk == 0){
            Log.i(TAG, "Chunking...");
            eosReceived = true;
        }

        // transfer previously encoded data to muxer
        drainEncoder(false);
        // send current frame data to encoder
        try {
            ByteBuffer[] inputBuffers = mEncoder.getInputBuffers();
            int inputBufferIndex = mEncoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                inputBuffer.put(input);
                long presentationTimeUs = (presentationTimeNs - startTime) / 1000;
                //Log.i(TAG, "Attempt to set PTS: " + presentationTimeUs);
                if(eosReceived){
                    Log.i(TAG, "EOS received in offerEncoder");
                    mEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    close();
                    eosSentToEncoder = true;
                    if(!stopReceived){
                        // swap encoder
                        currentChunk++;
                        prepare();
                    }else{
                        Log.i(TAG, "Stopping Encoding Service");
                        encodingService.shutdown();
                    }
                }else
                    mEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, presentationTimeUs, 0);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     * <p/>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     * <p/>
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    private void drainEncoder(boolean endOfStream) {
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true) {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // now that we have the Magic Goodies, start the muxer
                mTrackIndex = mMuxer.addTrack(newFormat);
                mMuxer.start();
                mMuxerStarted = true;
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer with pts " + mBufferInfo.presentationTimeUs);
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }

    enum EncoderTaskType{
        ENCODE_FRAME, /*SHIFT_ENCODER,*/ FINALIZE_ENCODER;
    }

    private class EncoderTask implements Runnable{
        private static final String TAG = "encoderTask";

        private ChunkedAvcEncoder encoder;

        private EncoderTaskType type;
        boolean is_initialized = false;

        // vars for type ENCODE_FRAME
        private byte[] video_data;
        private short[] audio_data;
        long presentationTimeNs;

        public EncoderTask(ChunkedAvcEncoder encoder, EncoderTaskType type){
            setEncoder(encoder);
            this.type = type;
            switch(type){
                /*
                case SHIFT_ENCODER:
                    setShiftEncoderParams();
                    break;
                */
                case FINALIZE_ENCODER:
                    setFinalizeEncoderParams();
                    break;
            }
        }

        public EncoderTask(ChunkedAvcEncoder encoder, byte[] video_data, short[] audio_data, long pts){
            setEncoder(encoder);
            setEncodeFrameParams(video_data, audio_data, pts);
        }

        public EncoderTask(ChunkedAvcEncoder encoder){
            setEncoder(encoder);
            setFinalizeEncoderParams();
        }

        private void setEncoder(ChunkedAvcEncoder encoder){
            this.encoder = encoder;
        }

        private void setFinalizeEncoderParams(){
            is_initialized = true;
        }

        private void setEncodeFrameParams(byte[] video_data, short[] audio_data, long pts){
            this.video_data = video_data;
            this.audio_data = audio_data;
            this.presentationTimeNs = pts;

            is_initialized = true;
            this.type = EncoderTaskType.ENCODE_FRAME;
        }

        /*
        private void setShiftEncoderParams(){
            this.type = EncoderTaskType.SHIFT_ENCODER;
            is_initialized = true;
        }
        */

        private void encodeFrame(){
            if(encoder != null && video_data != null)
                encoder._offerEncoder(video_data, presentationTimeNs);
        }

        private void shiftEncoder(){
        }

        private void finalizeEncoder(){
            encoder._stop();
        }

        @Override
        public void run() {
            if(is_initialized){
                switch(type){
                    case ENCODE_FRAME:
                        encodeFrame();
                        break;
                    /*
                    case SHIFT_ENCODER:
                        shiftEncoder();
                        break;
                    */
                    case FINALIZE_ENCODER:
                        finalizeEncoder();
                        break;

                }
                // prevent multiple execution of same task
                is_initialized = false;
            }
            else{
                Log.e(TAG, "run() called but EncoderTask not initialized");
            }

        }

    }
}