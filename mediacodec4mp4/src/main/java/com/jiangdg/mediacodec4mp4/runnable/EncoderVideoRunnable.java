package com.jiangdg.mediacodec4mp4.runnable;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.jiangdg.mediacodec4mp4.RecordMp4;
import com.jiangdg.yuvosd.YuvUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 对YUV视频流进行编码
 * Created by jiangdongguo on 2017/5/6.
 */

public class EncoderVideoRunnable implements Runnable {
    private static final String TAG = "EncoderVideoRunnable";
    private static final String MIME_TYPE = "video/avc";
    // 间隔1s插入一帧关键帧
    private static final int FRAME_INTERVAL = 1;
    // 绑定编码器缓存区超时时间为10s
    private static final int TIMES_OUT = 10000;

    // MP4混合器
    private WeakReference<RecordMp4> mRecordRf;
    // 硬编码器
    private MediaCodec mVideoEncodec;
    private int mColorFormat;
    private boolean isExit = false;
    private boolean isEncoderStart = false;
    private boolean isAddTimeOsd = true;

    private byte[] mFrameData;
    private long prevPresentationTimes;
    private MediaFormat mFormat;
    private static String path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/test1.h264";
//    private BufferedOutputStream outputStream;
    private boolean isAddKeyFrame = false;
    private EncoderParams mParams;

    // 码率等级
    public enum Quality{
        LOW, MIDDLE, HIGH
    }
    // 帧率
    public enum FrameRate{
        _20fps,_25fps,_30fps
    }

    public EncoderVideoRunnable(WeakReference<RecordMp4> mRecordRf) {
        this.mRecordRf = mRecordRf;
        mParams = this.mRecordRf.get().getRecordParams();
        mFrameData = new byte[mParams.getFrameWidth() * mParams.getFrameHeight() * 3 / 2];
        initMediaFormat();
    }

    private void initMediaFormat() {
        try {
            MediaCodecInfo mCodecInfo = selectSupportCodec(MIME_TYPE);
            if (mCodecInfo == null) {
                Log.d(TAG, "匹配编码器失败" + MIME_TYPE);
                return;
            }
            mColorFormat = selectSupportColorFormat(mCodecInfo, MIME_TYPE);
            mVideoEncodec = MediaCodec.createByCodecName(mCodecInfo.getName());
        } catch (IOException e) {
            Log.e(TAG, "创建编码器失败" + e.getMessage());
            e.printStackTrace();
        }
        if (mParams.isPhoneHorizontal()) {
            // 手机水平拍摄
            mFormat = MediaFormat.createVideoFormat(MIME_TYPE, mParams.getFrameWidth(), mParams.getFrameHeight());
        } else {
            // 手机垂直拍摄
            mFormat = MediaFormat.createVideoFormat(MIME_TYPE, mParams.getFrameHeight(), mParams.getFrameWidth());
        }
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, getBitrate());
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, getFrameRate());
        mFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, mColorFormat);         // 颜色格式
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, FRAME_INTERVAL);
    }

    private int getFrameRate() {
        return mParams.getFrameRateDegree() == FrameRate._20fps ? 20 :
                (mParams.getFrameRateDegree()== FrameRate._25fps ? 25 : 30);
    }

    private int getBitrate() {
        int mWidth = mParams.getFrameWidth();
        int mHeight = mParams.getFrameHeight();
        int bitRate = (int)(mWidth * mHeight * 20 * 2 *0.07f);
        if(mWidth >= 1920 || mHeight >= 1920){
            switch (mParams.getBitRateQuality()){
                case LOW:
                    bitRate *= 0.75;// 4354Kbps
                    break;
                case MIDDLE:
                    bitRate *= 1.1;// 6386Kbps
                    break;
                case HIGH:
                    bitRate *= 1.5;// 8709Kbps
                    break;
            }
        }else if(mWidth >= 1280 || mHeight >= 1280){
            switch (mParams.getBitRateQuality()){
                case LOW:
                    bitRate *= 1.0;// 2580Kbps
                    break;
                case MIDDLE:
                    bitRate *= 1.4;// 3612Kbps
                    break;
                case HIGH:
                    bitRate *= 1.9;// 4902Kbps
                    break;
            }
        }else if(mWidth >= 640 || mHeight >= 640){
            switch (mParams.getBitRateQuality()){
                case LOW:
                    bitRate *= 1.4;// 1204Kbps
                    break;
                case MIDDLE:
                    bitRate *= 2.1;// 1806Kbps
                    break;
                case HIGH:
                    bitRate *= 3;// 2580Kbps
                    break;
            }
        }
        return bitRate;
    }

    private void startCodec() {
        isExit = false;
        if (mVideoEncodec != null) {
            mVideoEncodec.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mVideoEncodec.start();
            isEncoderStart = true;
            Log.d(TAG, "配置、启动视频编码器");
        }
    }

    private void stopCodec() {
        if (mVideoEncodec != null) {
            mVideoEncodec.stop();
            mVideoEncodec.release();
            mVideoEncodec = null;
            isAddKeyFrame = false;
            isEncoderStart = false;
            Log.d(TAG, "关闭视频编码器");
        }
    }

    long millisPerframe = 1000 / 20;
    long lastPush = 0;

    @TargetApi(21)
    public void addData(byte[] yuvData) {
        if(! isEncoderStart)
            return;
        try {
            if (lastPush == 0) {
                lastPush = System.currentTimeMillis();
            }
            long time = System.currentTimeMillis() - lastPush;
            if (time >= 0) {
                time = millisPerframe - time;
                if (time > 0)
                    Thread.sleep(time / 2);
            }
            ByteBuffer[] inputBuffers = mVideoEncodec.getInputBuffers();
            //前置摄像头旋转270度，后置摄像头旋转90度
            int mWidth = mParams.getFrameWidth();
            int mHeight = mParams.getFrameHeight();
            byte[] rotateNv21 = new byte[mWidth * mHeight * 3 / 2];
            if (mParams.isFrontCamera()) {
                // 前置旋转270度(即竖屏采集，此时isPhoneHorizontal=false)
                YuvUtils.Yuv420spRotateOfFront(yuvData, rotateNv21, mWidth, mHeight, 270);
            } else {
                // 后置旋转90度(即竖直采集，此时isPhoneHorizontal=false)
                YuvUtils.YUV420spRotateOfBack(yuvData, rotateNv21, mWidth, mHeight, 90);
                // 后置旋转270度(即倒立采集，此时isPhoneHorizontal=false)
//			YuvUtils.YUV420spRotateOfBack(rawFrame, rotateNv21, mWidth, mHeight, 270);
                // 后置旋转180度(即反向横屏采集，此时isPhoneHorizontal=true)
//			YuvUtils.YUV420spRotateOfBack(rawFrame, rotateNv21, mWidth, mHeight, 180);
                // 如果是正向横屏，则无需旋转YUV，此时isPhoneHorizontal=true
            }
            // 将NV21转换为编码器支持的颜色格式I420，添加时间水印
            if (isAddTimeOsd) {
                YuvUtils.AddYuvOsd(rotateNv21, mWidth, mHeight, mFrameData,
                        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()),
                        mColorFormat, mParams.isPhoneHorizontal());
            } else {
                YuvUtils.transferColorFormat(rotateNv21, mWidth, mHeight, mFrameData, mColorFormat);
            }
            //返回编码器的一个输入缓存区句柄，-1表示当前没有可用的输入缓存区
            int inputBufferIndex = mVideoEncodec.dequeueInputBuffer(TIMES_OUT);
            if (inputBufferIndex >= 0) {
                // 绑定一个被空的、可写的输入缓存区inputBuffer到客户端
                ByteBuffer inputBuffer = null;
                if (!isLollipop()) {
                    inputBuffer = inputBuffers[inputBufferIndex];
                } else {
                    inputBuffer = mVideoEncodec.getInputBuffer(inputBufferIndex);
                }
                // 向输入缓存区写入有效原始数据，并提交到编码器中进行编码处理
                inputBuffer.clear();
                inputBuffer.put(mFrameData);
                inputBuffer.clear();
                mVideoEncodec.queueInputBuffer(inputBufferIndex, 0, mFrameData.length, getPTSUs(), MediaCodec.BUFFER_FLAG_KEY_FRAME);

                if (time > 0)
                    Thread.sleep(time / 2);
                lastPush = System.currentTimeMillis();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void run() {
        // 初始化编码器
        if (!isEncoderStart) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            startCodec();
        }
        // 如果编码器没有启动或者没有图像数据，线程阻塞等待
        while (!isExit) {
            ByteBuffer[] outputBuffers = mVideoEncodec.getOutputBuffers();

            // 返回一个输出缓存区句柄，当为-1时表示当前没有可用的输出缓存区
            // mBufferInfo参数包含被编码好的数据，timesOut参数为超时等待的时间
            MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = -1;
            do {
                outputBufferIndex = mVideoEncodec.dequeueOutputBuffer(mBufferInfo, TIMES_OUT);
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.i(TAG, "获得编码器输出缓存区超时");
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // 如果API小于21，APP需要重新绑定编码器的输入缓存区；
                    // 如果API大于21，则无需处理INFO_OUTPUT_BUFFERS_CHANGED
                    if (!isLollipop()) {
                        outputBuffers = mVideoEncodec.getOutputBuffers();
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 编码器输出缓存区格式改变，通常在存储数据之前且只会改变一次
                    // 这里设置混合器视频轨道，如果音频已经添加则启动混合器（保证音视频同步）
                    MediaFormat newFormat = mVideoEncodec.getOutputFormat();
                    RecordMp4 mMuxerUtils = mRecordRf.get();
                    if (mMuxerUtils != null) {
                        mMuxerUtils.setMediaFormat(RecordMp4.TRACK_VIDEO, newFormat);
                    }
                    Log.i(TAG, "编码器输出缓存区格式改变，添加视频轨道到混合器");
                } else {
                    // 获取一个只读的输出缓存区inputBuffer ，它包含被编码好的数据
                    ByteBuffer outputBuffer = null;
                    if (!isLollipop()) {
                        outputBuffer = outputBuffers[outputBufferIndex];
                    } else {
                        outputBuffer = mVideoEncodec.getOutputBuffer(outputBufferIndex);
                    }
                    // 如果API<=19，需要根据BufferInfo的offset偏移量调整ByteBuffer的位置
                    // 并且限定将要读取缓存区数据的长度，否则输出数据会混乱
                    if (isKITKAT()) {
                        outputBuffer.position(mBufferInfo.offset);
                        outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size);
                    }
                    // 根据NALU类型判断帧类型
                    RecordMp4 mMuxerUtils = mRecordRf.get();
                    int type = outputBuffer.get(4) & 0x1F;
                    Log.d(TAG, "------还有数据---->" + type);
                    if (type == 7 || type == 8) {
                        Log.e(TAG, "------PPS、SPS帧(非图像数据)，忽略-------");
                        mBufferInfo.size = 0;
                    } else if (type == 5) {
                        // 录像时，第1秒画面会静止，这是由于音视轨没有完全被添加
                        // Muxer没有启动
                        Log.e(TAG, "------I帧(关键帧)-------");
                        if (mMuxerUtils != null && mMuxerUtils.isMuxerStarted()) {
                            mMuxerUtils.addMuxerData(new RecordMp4.MuxerData(
                                    RecordMp4.TRACK_VIDEO, outputBuffer,
                                    mBufferInfo));
                            prevPresentationTimes = mBufferInfo.presentationTimeUs;
                            isAddKeyFrame = true;
                            Log.e(TAG, "----------->添加关键帧到混合器");
                        }
                    } else {
                        if (isAddKeyFrame) {
                            Log.d(TAG, "------非I帧(type=1)，添加到混合器-------");
                            if (mMuxerUtils != null && mMuxerUtils.isMuxerStarted()) {
                                mMuxerUtils.addMuxerData(new RecordMp4.MuxerData(
                                        RecordMp4.TRACK_VIDEO, outputBuffer,
                                        mBufferInfo));
                                prevPresentationTimes = mBufferInfo.presentationTimeUs;
                                Log.d(TAG, "------添加到混合器");
                            }
                        }
                    }
                    // 处理结束，释放输出缓存区资源
                    mVideoEncodec.releaseOutputBuffer(outputBufferIndex, false);
                }
            } while (outputBufferIndex >= 0);
        }
        stopCodec();
    }

    public void exit() {
        isExit = true;
    }

    /**
     * 遍历所有编解码器，返回第一个与指定MIME类型匹配的编码器
     * 判断是否有支持指定mime类型的编码器
     */
    private MediaCodecInfo selectSupportCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            // 判断是否为编码器，否则直接进入下一次循环
            if (!codecInfo.isEncoder()) {
                continue;
            }
            // 如果是编码器，判断是否支持Mime类型
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    /**
     * 根据mime类型匹配编码器支持的颜色格式
     */
    private int selectSupportColorFormat(MediaCodecInfo mCodecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = mCodecInfo.getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isCodecRecognizedFormat(colorFormat)) {
                return colorFormat;
            }
        }
        return 0;
    }

    private boolean isCodecRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
                //video/avc编码器支持COLOR_FormatYUV420SemiPlanar格式
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }

    private boolean isLollipop() {
        // API>=21
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    private boolean isKITKAT() {
        // API<=19
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT;
    }

    private long getPTSUs() {
        long result = System.nanoTime() / 1000;
        if (result < prevPresentationTimes) {
            result = (prevPresentationTimes - result) + result;
        }
        return result;
    }
}
