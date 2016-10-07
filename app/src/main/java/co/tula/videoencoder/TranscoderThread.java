package co.tula.videoencoder;

import android.media.*;
import android.support.annotation.NonNull;
import android.util.Log;
import co.tula.videoencoder.encoder.InputSurface;
import co.tula.videoencoder.encoder.OutputSurface;

import java.nio.ByteBuffer;

/**
 * Created by nephe on 07.10.2016.
 */
public class TranscoderThread extends Thread {

    private static final Object queueLock = new Object();

    private String inputPath, outputPath, fragmentShader;
    private int width, height;
    private Runnable onComplete = () -> {};

    public TranscoderThread(String inputPath, String outputPath, String fragmentShader, int width, int height,
                            Runnable onComplete) {
        this.inputPath = inputPath;
        this.outputPath = outputPath;
        this.fragmentShader = fragmentShader;
        this.width = width;
        this.height = height;
        this.onComplete = onComplete;
    }

    @Override
    public void run() {
        synchronized (queueLock) {
            startEncode(inputPath, outputPath, width, height, fragmentShader);
            onComplete.run();
        }
    }

    private MediaFormat makeEncoderFormat(MediaFormat inputFormat, int width, int height) {
        MediaFormat outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_MPEG4, width, height);
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000);
        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        return outputFormat;
    }

    private int getVideoTrack(@NonNull MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private int getAudioTrack(@NonNull MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private void startEncode(String inputPath, String outputPath, int width, int height, String fragmentShader) {
        OutputSurface  decoderSurface = null;
        InputSurface   encoderSurface = null;
        MediaCodec     decoder        = null;
        MediaCodec     encoder        = null;
        MediaMuxer     muxer          = null;
        MediaExtractor extractor      = null;
        int            storedRotation = 0;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);
            int videoIndex = getVideoTrack(extractor);
            if (videoIndex < 0) return;
            extractor.selectTrack(videoIndex);
            MediaFormat decoderFormat = extractor.getTrackFormat(videoIndex);
            if (decoderFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                storedRotation = decoderFormat.getInteger(MediaFormat.KEY_ROTATION);
                decoderFormat.setInteger(MediaFormat.KEY_ROTATION, 0);
            }
            MediaFormat encoderFormat = makeEncoderFormat(decoderFormat, width, height);
            encoder = MediaCodec.createEncoderByType(encoderFormat.getString(MediaFormat.KEY_MIME));
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = new InputSurface(encoder.createInputSurface());
            // Make encoder surface's GL context global
            encoderSurface.makeCurrent();
            decoder = MediaCodec.createDecoderByType(decoderFormat.getString(MediaFormat.KEY_MIME));
            int   decW          = decoderFormat.getInteger(MediaFormat.KEY_WIDTH);
            int   decH          = decoderFormat.getInteger(MediaFormat.KEY_HEIGHT);
            float decoderAspect = (float) decW / (float) decH;
            float encoderAspect = (float) width / (float) height;
            float distortion    = encoderAspect / decoderAspect;
            float corr          = distortion < 1 ? 1f / distortion : distortion;
            float corrX         = encoderAspect < 1 ? 1 : corr;
            float corrY         = encoderAspect < 1 ? corr : 1;
            // Init decoder surface renderer with global encoder surface
            decoderSurface = new OutputSurface(-storedRotation, corrX, corrY);
            decoderSurface.changeFragmentShader(fragmentShader);
            decoder.configure(decoderFormat, decoderSurface.getSurface(), null, 0);
            decoder.start();
            encoder.start();
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Log.d(this.getClass().getSimpleName(), "Decoder started");
            int audioIndex = getAudioTrack(extractor);
            int audioTrack = -1;
            if (audioIndex >= 0) {
                audioTrack = muxer.addTrack(extractor.getTrackFormat(audioIndex));
            }
            // video decode-modify-encode loop
            runDecode(extractor,
                      decoder, decoderSurface,
                      encoder, encoderSurface,
                      muxer);
            // audio copy loop
            if (audioTrack >= 0) {
                extractor.unselectTrack(videoIndex);
                extractor.selectTrack(audioIndex);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                copyAudio(extractor, muxer, audioTrack);
            }
        } catch (InterruptedException interrupted) {
            muxer.writeSampleData(0, ByteBuffer.allocate(256), new MediaCodec.BufferInfo());
            muxer.writeSampleData(1, ByteBuffer.allocate(256), new MediaCodec.BufferInfo());
        } catch (Exception ex) {
            Log.d(this.getClass().getSimpleName(), "Message: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            if (decoderSurface != null) {
                decoderSurface.release();
            }
            if (encoderSurface != null) {
                encoderSurface.release();
            }
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            if (muxer != null) {
                muxer.release();
            }
            if (extractor != null) {
                extractor.release();
            }
            Log.d(this.getClass().getSimpleName(), "Decoder finished");
        }
    }

    private void copyAudio(MediaExtractor extractor, MediaMuxer muxer, int audioTrack) {
        boolean               audioDone = false;
        ByteBuffer            buffer    = ByteBuffer.allocateDirect(16000);
        MediaCodec.BufferInfo info      = new MediaCodec.BufferInfo();
        int                   offset    = 100;
        while (!audioDone) {
            int chunkSize = extractor.readSampleData(buffer, offset);
            info.presentationTimeUs = extractor.getSampleTime();
            //noinspection WrongConstant
            info.flags = extractor.getSampleFlags();
            info.size = chunkSize;
            info.offset = offset;
            if (chunkSize < 0) {
                audioDone = true;
            } else {
                muxer.writeSampleData(audioTrack, buffer, info);
                buffer.clear();
                extractor.advance();
            }
            Log.d(this.getClass().getSimpleName(), "Audio copied [" + chunkSize + "]");
        }
    }

    private void runDecode(MediaExtractor extractor,
                           MediaCodec decoder, OutputSurface decoderSurface,
                           MediaCodec encoder, InputSurface encoderSurface,
                           MediaMuxer muxer) throws InterruptedException {
        boolean               inputDone  = false;
        boolean               outputDone = false;
        MediaCodec.BufferInfo info       = new MediaCodec.BufferInfo();
        int                   frame      = 0;
        int                   videoIndex = -1;
        while (!outputDone) {
            if (!inputDone) {
                int inputIndex = decoder.dequeueInputBuffer(10000); //10ms
                if (inputIndex >= 0) {
                    ByteBuffer decoderInput = decoder.getInputBuffer(inputIndex);
                    if (decoderInput != null) {
                        int chunkSize = extractor.readSampleData(decoderInput, 0);
                        if (chunkSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTime = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputIndex, 0, chunkSize, presentationTime, 0);
                            extractor.advance();
                        }
                    }
                }
            }

            // Assume output is available.  Loop until both assumptions are false.
            boolean decoderOutputAvailable = true;
            while (decoderOutputAvailable) {
                // Start by draining any pending output from the encoder.  It's important to
                // do this before we try to stuff any more data in.
                int encoderStatus = encoder.dequeueOutputBuffer(info, 10000);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    Log.d(this.getClass().getSimpleName(), "no output from encoder available");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    videoIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    Log.d(this.getClass().getSimpleName(), "encoder output format changed: " + newFormat);
                } else if (encoderStatus < 0) {
                    Log.d(this.getClass().getSimpleName(),
                          "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                } else { // encoderStatus >= 0
                    ByteBuffer encodedData = encoder.getOutputBuffer(encoderStatus);
                    if (encodedData == null) {
                        Log.d(this.getClass().getSimpleName(), "encoderOutputBuffer " + encoderStatus + " was null");
                        return;
                    }
                    if (info.size != 0) {
                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);
                        if (videoIndex >= 0) {
                            muxer.writeSampleData(videoIndex, encodedData, info);
                        }
                        Log.d(this.getClass().getSimpleName(), "encoder output " + info.size + " bytes");
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(encoderStatus, false);
                }
                if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Continue attempts to drain output.
                    continue;
                }
                int decoderStatus = decoder.dequeueOutputBuffer(info, 10000);
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.d(this.getClass().getSimpleName(), "Decoder isn't ready");
                    decoderOutputAvailable = false;
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = decoder.getOutputFormat();
                    Log.d(this.getClass().getSimpleName(), "Decoder format changed to " + newFormat.toString());
                } else if (decoderStatus < 0) {
                    Log.d(this.getClass().getSimpleName(), "Decoder unexpected status " + decoderStatus);
                } else {
                    boolean doRender = info.size != 0;
                    decoder.releaseOutputBuffer(decoderStatus, doRender);
                    if (doRender) {
                        decoderSurface.awaitNewImage();
                        decoderSurface.drawImage();
                        encoderSurface.setPresentationTime(info.presentationTimeUs * 1000);
                        encoderSurface.swapBuffers();
                        Log.d(this.getClass().getSimpleName(), "Decoded frame #" + frame);
                        frame++;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.signalEndOfInputStream();
                    }
                }
            }
        }
    }

}
