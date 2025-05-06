/*
 * Copyright (c) 2018-2022 Taner Sener
 *
 * This file is part of FFmpegKit.
 *
 * FFmpegKit is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * FFmpegKit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with FFmpegKit.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.arthenica.ffmpegkit.flutter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.arthenica.ffmpegkit.AbiDetect;
import com.arthenica.ffmpegkit.AbstractSession;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.FFprobeSession;
import com.arthenica.ffmpegkit.Level;
import com.arthenica.ffmpegkit.LogRedirectionStrategy;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationJsonParser;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.Packages;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Session;
import com.arthenica.ffmpegkit.SessionState;
import com.arthenica.ffmpegkit.Signal;
import com.arthenica.ffmpegkit.Statistics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

public class FFmpegKitFlutterPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, EventChannel.StreamHandler, PluginRegistry.ActivityResultListener {

    public static final String LIBRARY_NAME = "ffmpeg-kit-flutter";
    public static final String PLATFORM_NAME = "android";

    private static final String METHOD_CHANNEL = "flutter.arthenica.com/ffmpeg_kit";
    private static final String EVENT_CHANNEL = "flutter.arthenica.com/ffmpeg_kit_event";

    // LOG CLASS
    public static final String KEY_LOG_SESSION_ID = "sessionId";
    public static final String KEY_LOG_LEVEL = "level";
    public static final String KEY_LOG_MESSAGE = "message";

    // STATISTICS CLASS
    public static final String KEY_STATISTICS_SESSION_ID = "sessionId";
    public static final String KEY_STATISTICS_VIDEO_FRAME_NUMBER = "videoFrameNumber";
    public static final String KEY_STATISTICS_VIDEO_FPS = "videoFps";
    public static final String KEY_STATISTICS_VIDEO_QUALITY = "videoQuality";
    public static final String KEY_STATISTICS_SIZE = "size";
    public static final String KEY_STATISTICS_TIME = "time";
    public static final String KEY_STATISTICS_BITRATE = "bitrate";
    public static final String KEY_STATISTICS_SPEED = "speed";

    // SESSION CLASS
    public static final String KEY_SESSION_ID = "sessionId";
    public static final String KEY_SESSION_CREATE_TIME = "createTime";
    public static final String KEY_SESSION_START_TIME = "startTime";
    public static final String KEY_SESSION_COMMAND = "command";
    public static final String KEY_SESSION_TYPE = "type";
    public static final String KEY_SESSION_MEDIA_INFORMATION = "mediaInformation";

    // SESSION TYPE
    public static final int SESSION_TYPE_FFMPEG = 1;
    public static final int SESSION_TYPE_FFPROBE = 2;
    public static final int SESSION_TYPE_MEDIA_INFORMATION = 3;

    // EVENTS
    public static final String EVENT_LOG_CALLBACK_EVENT = "FFmpegKitLogCallbackEvent";
    public static final String EVENT_STATISTICS_CALLBACK_EVENT = "FFmpegKitStatisticsCallbackEvent";
    public static final String EVENT_COMPLETE_CALLBACK_EVENT = "FFmpegKitCompleteCallbackEvent";

    // REQUEST CODES
    public static final int READABLE_REQUEST_CODE = 10000;
    public static final int WRITABLE_REQUEST_CODE = 20000;

    // ARGUMENT NAMES
    public static final String ARGUMENT_SESSION_ID = "sessionId";
    public static final String ARGUMENT_WAIT_TIMEOUT = "waitTimeout";
    public static final String ARGUMENT_ARGUMENTS = "arguments";
    public static final String ARGUMENT_FFPROBE_JSON_OUTPUT = "ffprobeJsonOutput";
    public static final String ARGUMENT_WRITABLE = "writable";

    private static final int asyncConcurrencyLimit = 10;

    private final AtomicBoolean logsEnabled;
    private final AtomicBoolean statisticsEnabled;
    private final ExecutorService asyncExecutorService;

    private MethodChannel methodChannel;
    private EventChannel eventChannel;
    private Result lastInitiatedIntentResult;
    private Context context;
    private Activity activity;
    private FlutterPluginBinding flutterPluginBinding;
    private ActivityPluginBinding activityPluginBinding;

    private EventChannel.EventSink eventSink;
    private final FFmpegKitFlutterMethodResultHandler resultHandler;

    public FFmpegKitFlutterPlugin() {
        this.logsEnabled = new AtomicBoolean(false);
        this.statisticsEnabled = new AtomicBoolean(false);
        this.asyncExecutorService = Executors.newFixedThreadPool(asyncConcurrencyLimit);
        this.resultHandler = new FFmpegKitFlutterMethodResultHandler();

        Log.d(LIBRARY_NAME, String.format("FFmpegKitFlutterPlugin created %s.", this));
    }

    protected void registerGlobalCallbacks() {
        FFmpegKitConfig.enableFFmpegSessionCompleteCallback(this::emitSession);
        FFmpegKitConfig.enableFFprobeSessionCompleteCallback(this::emitSession);
        FFmpegKitConfig.enableMediaInformationSessionCompleteCallback(this::emitSession);

        FFmpegKitConfig.enableLogCallback(log -> {
            if (logsEnabled.get()) {
                emitLog(log);
            }
        });

        FFmpegKitConfig.enableStatisticsCallback(statistics -> {
            if (statisticsEnabled.get()) {
                emitStatistics(statistics);
            }
        });
    }

    @Override
    public void onAttachedToEngine(@NonNull final FlutterPluginBinding flutterPluginBinding) {
        this.flutterPluginBinding = flutterPluginBinding;
    }

    @Override
    public void onDetachedFromEngine(@NonNull final FlutterPluginBinding binding) {
        this.flutterPluginBinding = null;
        uninit();
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding activityPluginBinding) {
        Log.d(LIBRARY_NAME, String.format("FFmpegKitFlutterPlugin %s attached to activity %s.", this, activityPluginBinding.getActivity()));
        init(flutterPluginBinding.getBinaryMessenger(), flutterPluginBinding.getApplicationContext(), activityPluginBinding.getActivity(), activityPluginBinding);
    }

    @Override
    public void onDetachedFromActivity() {
        Log.d(LIBRARY_NAME, String.format("FFmpegKitFlutterPlugin %s detached from activity.", this));
        uninit();
    }

    @SuppressWarnings("deprecation")
    protected void init(final BinaryMessenger messenger, final Context context, final Activity activity, final ActivityPluginBinding activityBinding) {
        registerGlobalCallbacks();

        if (methodChannel == null) {
            methodChannel = new MethodChannel(messenger, METHOD_CHANNEL);
            methodChannel.setMethodCallHandler(this);
        } else {
            Log.i(LIBRARY_NAME, "FFmpegKitFlutterPlugin method channel was already initialised.");
        }

        if (eventChannel == null) {
            eventChannel = new EventChannel(messenger, EVENT_CHANNEL);
            eventChannel.setStreamHandler(this);
        } else {
            Log.i(LIBRARY_NAME, "FFmpegKitFlutterPlugin event channel was already initialised.");
        }

        this.context = context;
        this.activity = activity;

        // Only V2 embedding setup for activity listeners.
        activityBinding.addActivityResultListener(this);

        Log.d(LIBRARY_NAME, String.format("FFmpegKitFlutterPlugin %s initialised with context %s and activity %s.", this, context, activity));
    }

    protected void uninit() {
        uninitMethodChannel();
        uninitEventChannel();

        if (this.activityPluginBinding != null) {
            this.activityPluginBinding.removeActivityResultListener(this);
        }

        this.context = null;
        this.activity = null;
        this.activityPluginBinding = null;

        Log.d(LIBRARY_NAME, "FFmpegKitFlutterPlugin uninitialized.");
    }

    protected void uninitMethodChannel() {
        if (methodChannel == null) {
            Log.i(LIBRARY_NAME, "FFmpegKitFlutterPlugin method channel was already uninitialised.");
            return;
        }

        methodChannel.setMethodCallHandler(null);
        methodChannel = null;
    }

    protected void uninitEventChannel() {
        if (eventChannel == null) {
            Log.i(LIBRARY_NAME, "FFmpegKitFlutterPlugin event channel was already uninitialised.");
            return;
        }

        eventChannel.setStreamHandler(null);
        eventChannel = null;
    }

    // AbstractSession

    protected void abstractSessionGetEndTime(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            final Date endTime = session.getEndTime();
            if (endTime == null) {
                resultHandler.successAsync(result, null);
            } else {
                resultHandler.successAsync(result, endTime.getTime());
            }
        }
    }

    protected void abstractSessionGetDuration(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            resultHandler.successAsync(result, session.getDuration());
        }
    }

    protected void abstractSessionGetAllLogs(@NonNull final Integer sessionId, @Nullable final Integer waitTimeout, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            final int timeout;
            if (isValidPositiveNumber(waitTimeout)) {
                timeout = waitTimeout;
            } else {
                timeout = AbstractSession.DEFAULT_TIMEOUT_FOR_ASYNCHRONOUS_MESSAGES_IN_TRANSMIT;
            }
            final List<com.arthenica.ffmpegkit.Log> allLogs = session.getAllLogs(timeout);
            resultHandler.successAsync(result, toLogMapList(allLogs));
        }
    }

    protected void abstractSessionGetLogs(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            final List<com.arthenica.ffmpegkit.Log> allLogs = session.getLogs();
            resultHandler.successAsync(result, toLogMapList(allLogs));
        }
    }

    protected void abstractSessionGetAllLogsAsString(@NonNull final Integer sessionId, @Nullable final Integer waitTimeout, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            final int timeout;
            if (isValidPositiveNumber(waitTimeout)) {
                timeout = waitTimeout;
            } else {
                timeout = AbstractSession.DEFAULT_TIMEOUT_FOR_ASYNCHRONOUS_MESSAGES_IN_TRANSMIT;
            }
            final String allLogsAsString = session.getAllLogsAsString(timeout);
            resultHandler.successAsync(result, allLogsAsString);
        }
    }

    protected void abstractSessionGetState(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            resultHandler.successAsync(result, session.getState().ordinal());
        }
    }

    protected void abstractSessionGetReturnCode(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            final ReturnCode returnCode = session.getReturnCode();
            if (returnCode == null) {
                resultHandler.successAsync(result, null);
            } else {
                resultHandler.successAsync(result, returnCode.getValue());
            }
        }
    }

    protected void abstractSessionGetFailStackTrace(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            resultHandler.successAsync(result, session.getFailStackTrace());
        }
    }

    protected void abstractSessionThereAreAsynchronousMessagesInTransmit(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            resultHandler.successAsync(result, session.thereAreAsynchronousMessagesInTransmit());
        }
    }

    // ArchDetect

    protected void getArch(@NonNull final Result result) {
        resultHandler.successAsync(result, AbiDetect.getAbi());
    }

    // FFmpegSession

    protected void ffmpegSession(@NonNull final List<String> arguments, @NonNull final Result result) {
        final FFmpegSession session = FFmpegSession.create(arguments.toArray(new String[0]), null, null, null, LogRedirectionStrategy.NEVER_PRINT_LOGS);
        resultHandler.successAsync(result, toMap(session));
    }

    protected void ffmpegSessionGetAllStatistics(@NonNull final Integer sessionId, @Nullable final Integer waitTimeout, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            if (session.isFFmpeg()) {
                final int timeout;
                if (isValidPositiveNumber(waitTimeout)) {
                    timeout = waitTimeout;
                } else {
                    timeout = AbstractSession.DEFAULT_TIMEOUT_FOR_ASYNCHRONOUS_MESSAGES_IN_TRANSMIT;
                }
                final List<Statistics> allStatistics = ((FFmpegSession) session).getAllStatistics(timeout);
                resultHandler.successAsync(result, toStatisticsMapList(allStatistics));
            } else {
                resultHandler.errorAsync(result, "NOT_FFMPEG_SESSION", "A session is found but it does not have the correct type.");
            }
        }
    }

    protected void ffmpegSessionGetStatistics(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            if (session.isFFmpeg()) {
                final List<Statistics> statistics = ((FFmpegSession) session).getStatistics();
                resultHandler.successAsync(result, toStatisticsMapList(statistics));
            } else {
                resultHandler.errorAsync(result, "NOT_FFMPEG_SESSION", "A session is found but it does not have the correct type.");
            }
        }
    }

    // FFprobeSession

    protected void ffprobeSession(@NonNull final List<String> arguments, @NonNull final Result result) {
        final FFprobeSession session = FFprobeSession.create(arguments.toArray(new String[0]), null, null, LogRedirectionStrategy.NEVER_PRINT_LOGS);
        resultHandler.successAsync(result, toMap(session));
    }

    // MediaInformationSession

    protected void mediaInformationSession(@NonNull final List<String> arguments, @NonNull final Result result) {
        final MediaInformationSession session = MediaInformationSession.create(arguments.toArray(new String[0]), null, null);
        resultHandler.successAsync(result, toMap(session));
    }

    protected void getMediaInformation(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            if (session.isMediaInformation()) {
                final MediaInformationSession mediaInformationSession = (MediaInformationSession) session;
                final MediaInformation mediaInformation = mediaInformationSession.getMediaInformation();
                resultHandler.successAsync(result, toMap(mediaInformation));
            } else {
                resultHandler.errorAsync(result, "NOT_MEDIA_INFORMATION_SESSION", "A session is found but it does not have the correct type.");
            }
        }
    }

    // MediaInformationJsonParser

    protected void mediaInformationJsonParserFrom(@NonNull final String ffprobeJsonOutput, @NonNull final Result result) {
        try {
            final MediaInformation mediaInformation = MediaInformationJsonParser.fromWithError(ffprobeJsonOutput);
            resultHandler.successAsync(result, toMap(mediaInformation));
        } catch (final JSONException e) {
            Log.i(LIBRARY_NAME, "Parsing MediaInformation failed.", e);
            resultHandler.successAsync(result, null);
        }
    }

    protected void mediaInformationJsonParserFromWithError(@NonNull final String ffprobeJsonOutput, @NonNull final Result result) {
        try {
            final MediaInformation mediaInformation = MediaInformationJsonParser.fromWithError(ffprobeJsonOutput);
            resultHandler.successAsync(result, toMap(mediaInformation));
        } catch (JSONException e) {
            Log.i(LIBRARY_NAME, "Parsing MediaInformation failed.", e);
            resultHandler.errorAsync(result, "PARSE_FAILED", "Parsing MediaInformation failed with JSON error.");
        }
    }

    // FFmpegKitConfig

    protected void enableRedirection(@NonNull final Result result) {
        enableLogs();
        enableStatistics();
        FFmpegKitConfig.enableRedirection();

        resultHandler.successAsync(result, null);
    }

    protected void disableRedirection(@NonNull final Result result) {
        FFmpegKitConfig.disableRedirection();

        resultHandler.successAsync(result, null);
    }

    protected void enableLogs(@NonNull final Result result) {
        enableLogs();

        resultHandler.successAsync(result, null);
    }

    protected void disableLogs(@NonNull final Result result) {
        disableLogs();

        resultHandler.successAsync(result, null);
    }

    protected void enableStatistics(@NonNull final Result result) {
        enableStatistics();

        resultHandler.successAsync(result, null);
    }

    protected void disableStatistics(@NonNull final Result result) {
        disableStatistics();

        resultHandler.successAsync(result, null);
    }

    protected void setFontconfigConfigurationPath(@NonNull final String path, @NonNull final Result result) {
        FFmpegKitConfig.setFontconfigConfigurationPath(path);

        resultHandler.successAsync(result, null);
    }

    protected void setFontDirectory(@NonNull final String fontDirectoryPath, @Nullable final Map<String, String> fontNameMapping, @NonNull final Result result) {
        if (context != null) {
            FFmpegKitConfig.setFontDirectory(context, fontDirectoryPath, fontNameMapping);
            resultHandler.successAsync(result, null);
        } else {
            Log.w(LIBRARY_NAME, "Cannot setFontDirectory. Context is null.");
            resultHandler.errorAsync(result, "INVALID_CONTEXT", "Context is null.");
        }
    }

    protected void setFontDirectoryList(@NonNull final List<String> fontDirectoryList, @Nullable final Map<String, String> fontNameMapping, @NonNull final Result result) {
        if (context != null) {
            FFmpegKitConfig.setFontDirectoryList(context, fontDirectoryList, fontNameMapping);
            resultHandler.successAsync(result, null);
        } else {
            Log.w(LIBRARY_NAME, "Cannot setFontDirectoryList. Context is null.");
            resultHandler.errorAsync(result, "INVALID_CONTEXT", "Context is null.");
        }
    }

    protected void registerNewFFmpegPipe(@NonNull final Result result) {
        if (context != null) {
            resultHandler.successAsync(result, FFmpegKitConfig.registerNewFFmpegPipe(context));
        } else {
            Log.w(LIBRARY_NAME, "Cannot registerNewFFmpegPipe. Context is null.");
            resultHandler.errorAsync(result, "INVALID_CONTEXT", "Context is null.");
        }
    }

    protected void closeFFmpegPipe(@NonNull final String ffmpegPipePath, @NonNull final Result result) {
        FFmpegKitConfig.closeFFmpegPipe(ffmpegPipePath);

        resultHandler.successAsync(result, null);
    }

    protected void getFFmpegVersion(@NonNull final Result result) {
        resultHandler.successAsync(result, FFmpegKitConfig.getFFmpegVersion());
    }

    protected void isLTSBuild(@NonNull final Result result) {
        resultHandler.successAsync(result, FFmpegKitConfig.isLTSBuild());
    }

    protected void getBuildDate(@NonNull final Result result) {
        resultHandler.successAsync(result, FFmpegKitConfig.getBuildDate());
    }

    protected void setEnvironmentVariable(@NonNull final String variableName, @NonNull final String variableValue, @NonNull final Result result) {
        FFmpegKitConfig.setEnvironmentVariable(variableName, variableValue);

        resultHandler.successAsync(result, null);
    }

    protected void ignoreSignal(@NonNull final Integer signalIndex, @NonNull final Result result) {
        Signal signal = null;

        if (signalIndex == Signal.SIGINT.ordinal()) {
            signal = Signal.SIGINT;
        } else if (signalIndex == Signal.SIGQUIT.ordinal()) {
            signal = Signal.SIGQUIT;
        } else if (signalIndex == Signal.SIGPIPE.ordinal()) {
            signal = Signal.SIGPIPE;
        } else if (signalIndex == Signal.SIGTERM.ordinal()) {
            signal = Signal.SIGTERM;
        } else if (signalIndex == Signal.SIGXCPU.ordinal()) {
            signal = Signal.SIGXCPU;
        }

        if (signal != null) {
            FFmpegKitConfig.ignoreSignal(signal);

            resultHandler.successAsync(result, null);
        } else {
            resultHandler.errorAsync(result, "INVALID_SIGNAL", "Signal value not supported.");
        }
    }

    protected void ffmpegSessionExecute(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            if (session.isFFmpeg()) {
                final FFmpegSessionExecuteTask ffmpegSessionExecuteTask = new FFmpegSessionExecuteTask((FFmpegSession) session, resultHandler, result);
                asyncExecutorService.submit(ffmpegSessionExecuteTask);
            } else {
                resultHandler.errorAsync(result, "NOT_FFMPEG_SESSION", "A session is found but it does not have the correct type.");
            }
        }
    }

    protected void ffprobeSessionExecute(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            if (session.isFFprobe()) {
                final FFprobeSessionExecuteTask ffprobeSessionExecuteTask = new FFprobeSessionExecuteTask((FFprobeSession) session, resultHandler, result);
                asyncExecutorService.submit(ffprobeSessionExecuteTask);
            } else {
                resultHandler.errorAsync(result, "NOT_FFPROBE_SESSION", "A session is found but it does not have the correct type.");
            }
        }
    }

    protected void mediaInformationSessionExecute(@NonNull final Integer sessionId, @Nullable final Integer waitTimeout, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            if (session.isMediaInformation()) {
                final int timeout;
                if (isValidPositiveNumber(waitTimeout)) {
                    timeout = waitTimeout;
                } else {
                    timeout = AbstractSession.DEFAULT_TIMEOUT_FOR_ASYNCHRONOUS_MESSAGES_IN_TRANSMIT;
                }
                final MediaInformationSessionExecuteTask mediaInformationSessionExecuteTask = new MediaInformationSessionExecuteTask((MediaInformationSession) session, timeout, resultHandler, result);
                asyncExecutorService.submit(mediaInformationSessionExecuteTask);
            } else {
                resultHandler.errorAsync(result, "NOT_MEDIA_INFORMATION_SESSION", "A session is found but it does not have the correct type.");
            }
        }
    }

    protected void asyncFFmpegSessionExecute(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            if (session.isFFmpeg()) {
                FFmpegKitConfig.asyncFFmpegExecute((FFmpegSession) session);
                resultHandler.successAsync(result, null);
            } else {
                resultHandler.errorAsync(result, "NOT_FFMPEG_SESSION", "A session is found but it does not have the correct type.");
            }
        }
    }

    protected void asyncFFprobeSessionExecute(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            if (session.isFFprobe()) {
                FFmpegKitConfig.asyncFFprobeExecute((FFprobeSession) session);
                resultHandler.successAsync(result, null);
            } else {
                resultHandler.errorAsync(result, "NOT_FFPROBE_SESSION", "A session is found but it does not have the correct type.");
            }
        }
    }

    protected void asyncMediaInformationSessionExecute(@NonNull final Integer sessionId, @Nullable final Integer waitTimeout, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            if (session.isMediaInformation()) {
                final int timeout;
                if (isValidPositiveNumber(waitTimeout)) {
                    timeout = waitTimeout;
                } else {
                    timeout = AbstractSession.DEFAULT_TIMEOUT_FOR_ASYNCHRONOUS_MESSAGES_IN_TRANSMIT;
                }
                FFmpegKitConfig.asyncGetMediaInformationExecute((MediaInformationSession) session, timeout);
                resultHandler.successAsync(result, null);
            } else {
                resultHandler.errorAsync(result, "NOT_MEDIA_INFORMATION_SESSION", "A session is found but it does not have the correct type.");
            }
        }
    }

    protected void getLogLevel(@NonNull final Result result) {
        resultHandler.successAsync(result, toInt(FFmpegKitConfig.getLogLevel()));
    }

    protected void setLogLevel(@NonNull final Integer level, @NonNull final Result result) {
        FFmpegKitConfig.setLogLevel(Level.from(level));
        resultHandler.successAsync(result, null);
    }

    protected void getSessionHistorySize(@NonNull final Result result) {
        resultHandler.successAsync(result, FFmpegKitConfig.getSessionHistorySize());
    }

    protected void setSessionHistorySize(@NonNull final Integer sessionHistorySize, @NonNull final Result result) {
        FFmpegKitConfig.setSessionHistorySize(sessionHistorySize);
        resultHandler.successAsync(result, null);
    }

    protected void getSession(@NonNull final Integer sessionId, @NonNull final Result result) {
        final Session session = FFmpegKitConfig.getSession(sessionId.longValue());
        if (session == null) {
            resultHandler.errorAsync(result, "SESSION_NOT_FOUND", "Session not found.");
        } else {
            resultHandler.successAsync(result, toMap(session));
        }
    }

    protected void getLastSession(@NonNull final Result result) {
        final Session session = FFmpegKitConfig.getLastSession();
        resultHandler.successAsync(result, toMap(session));
    }

    protected void getLastCompletedSession(@NonNull final Result result) {
        final Session session = FFmpegKitConfig.getLastCompletedSession();
        resultHandler.successAsync(result, toMap(session));
    }

    protected void getSessions(@NonNull final Result result) {
        resultHandler.successAsync(result, toSessionMapList(FFmpegKitConfig.getSessions()));
    }

    protected void clearSessions(@NonNull final Result result) {
        FFmpegKitConfig.clearSessions();
        resultHandler.successAsync(result, null);
    }

    protected void getSessionsByState(@NonNull final Integer sessionState, @NonNull final Result result) {
        resultHandler.successAsync(result, toSessionMapList(FFmpegKitConfig.getSessionsByState(toSessionState(sessionState))));
    }

    protected void getLogRedirectionStrategy(@NonNull final Result result) {
        resultHandler.successAsync(result, toInt(FFmpegKitConfig.getLogRedirectionStrategy()));
    }

    protected void setLogRedirectionStrategy(@NonNull final Integer logRedirectionStrategy, @NonNull final Result result) {
        FFmpegKitConfig.setLogRedirectionStrategy(toLogRedirectionStrategy(logRedirectionStrategy));
        resultHandler.successAsync(result, null);
    }

    protected void messagesInTransmit(@NonNull final Integer sessionId, @NonNull final Result result) {
        resultHandler.successAsync(result, FFmpegKitConfig.messagesInTransmit(sessionId.longValue()));
    }

    protected void getPlatform(@NonNull final Result result) {
        resultHandler.successAsync(result, PLATFORM_NAME);
    }

    protected void writeToPipe(@NonNull final String inputPath, @NonNull final String namedPipePath, @NonNull final Result result) {
        final WriteToPipeTask asyncTask = new WriteToPipeTask(inputPath, namedPipePath, resultHandler, result);
        asyncExecutorService.submit(asyncTask);
    }

    protected void selectDocument(@NonNull final Boolean writable, @Nullable final String title, @Nullable final String type, @Nullable final String[] extraTypes, @NonNull final Result result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            android.util.Log.i(LIBRARY_NAME, String.format(Locale.getDefault(), "selectDocument is not supported on API Level %d", Build.VERSION.SDK_INT));
            resultHandler.errorAsync(result, "SELECT_FAILED", String.format(Locale.getDefault(), "selectDocument is not supported on API Level %d", Build.VERSION.SDK_INT));
            return;
        }

        final Intent intent;
        if (writable) {
            intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        if (type != null) {
            intent.setType(type);
        } else {
            intent.setType("*/*");
        }

        if (title != null) {
            intent.putExtra(Intent.EXTRA_TITLE, title);
        }

        if (extraTypes != null) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, extraTypes);
        }

        if (context != null) {
            if (activity != null) {
                try {
                    lastInitiatedIntentResult = result;
                    activity.startActivityForResult(intent, writable ? WRITABLE_REQUEST_CODE : READABLE_REQUEST_CODE);
                } catch (final Exception e) {
                    Log.i(LIBRARY_NAME, String.format("Failed to selectDocument using parameters writable: %s, type: %s, title: %s and extra types: %s!", writable, type, title, extraTypes == null ? null : Arrays.toString(extraTypes)), e);
                    resultHandler.errorAsync(result, "SELECT_FAILED", e.getMessage());
                }
            } else {
                Log.w(LIBRARY_NAME, String.format("Cannot selectDocument using parameters writable: %s, type: %s, title: %s and extra types: %s. Activity is null.", writable, type, title, extraTypes == null ? null : Arrays.toString(extraTypes)));
                resultHandler.errorAsync(result, "INVALID_ACTIVITY", "Activity is null.");
            }
        } else {
            Log.w(LIBRARY_NAME, String.format("Cannot selectDocument using parameters writable: %s, type: %s, title: %s and extra types: %s. Context is null.", writable, type, title, extraTypes == null ? null : Arrays.toString(extraTypes)));
            resultHandler.errorAsync(result, "INVALID_CONTEXT", "Context is null.");
        }
    }

    protected void getSafParameter(@NonNull final String uriString, @NonNull final String openMode, @NonNull final Result result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            android.util.Log.i(LIBRARY_NAME, String.format(Locale.getDefault(), "getSafParameter is not supported on API Level %d", Build.VERSION.SDK_INT));
            resultHandler.errorAsync(result, "GET_SAF_PARAMETER_FAILED", String.format(Locale.getDefault(), "getSafParameter is not supported on API Level %d", Build.VERSION.SDK_INT));
            return;
        }

        if (context != null) {
            final Uri uri = Uri.parse(uriString);
            if (uri == null) {
                Log.w(LIBRARY_NAME, String.format("Cannot getSafParameter using parameters uriString: %s, openMode: %s. Uri string cannot be parsed.", uriString, openMode));
                resultHandler.errorAsync(result, "GET_SAF_PARAMETER_FAILED", "Uri string cannot be parsed.");
            } else {
                final String safParameter = FFmpegKitConfig.getSafParameter(context, uri, openMode);

                Log.d(LIBRARY_NAME, String.format("getSafParameter using parameters uriString: %s, openMode: %s completed with saf parameter: %s.", uriString, openMode, safParameter));

                resultHandler.successAsync(result, safParameter);
            }
        } else {
            Log.w(LIBRARY_NAME, String.format("Cannot getSafParameter using parameters uriString: %s, openMode: %s. Context is null.", uriString, openMode));
            resultHandler.errorAsync(result, "INVALID_CONTEXT", "Context is null.");
        }
    }

    // FFmpegKit

    protected void cancel(@NonNull final Result result) {
        FFmpegKit.cancel();
        resultHandler.successAsync(result, null);
    }

    protected void cancelSession(@NonNull final Integer sessionId, @NonNull final Result result) {
        FFmpegKit.cancel(sessionId.longValue());
        resultHandler.successAsync(result, null);
    }

    protected void getFFmpegSessions(@NonNull final Result result) {
        resultHandler.successAsync(result, toSessionMapList(FFmpegKit.listSessions()));
    }

    // FFprobeKit

    protected void getFFprobeSessions(@NonNull final Result result) {
        resultHandler.successAsync(result, toSessionMapList(FFprobeKit.listFFprobeSessions()));
    }

    protected void getMediaInformationSessions(@NonNull final Result result) {
        resultHandler.successAsync(result, toSessionMapList(FFprobeKit.listMediaInformationSessions()));
    }

    // Packages

    protected void getPackageName(@NonNull final Result result) {
        resultHandler.successAsync(result, Packages.getPackageName());
    }

    protected void getExternalLibraries(@NonNull final Result result) {
        resultHandler.successAsync(result, Packages.getExternalLibraries());
    }

    protected void enableLogs() {
        logsEnabled.compareAndSet(false, true);
    }

    protected void disableLogs() {
        logsEnabled.compareAndSet(true, false);
    }

    protected void enableStatistics() {
        statisticsEnabled.compareAndSet(false, true);
    }

    protected void disableStatistics() {
        statisticsEnabled.compareAndSet(true, false);
    }

    protected static int toInt(final Level level) {
        return (level == null) ? Level.AV_LOG_TRACE.getValue() : level.getValue();
    }

    protected static Map<String, Object> toMap(final Session session) {
        if (session == null) {
            return null;
        }

        final Map<String, Object> sessionMap = new HashMap<>();

        sessionMap.put(KEY_SESSION_ID, session.getSessionId());
        sessionMap.put(KEY_SESSION_CREATE_TIME, toLong(session.getCreateTime()));
        sessionMap.put(KEY_SESSION_START_TIME, toLong(session.getStartTime()));
        sessionMap.put(KEY_SESSION_COMMAND, session.getCommand());

        if (session.isFFmpeg()) {
            sessionMap.put(KEY_SESSION_TYPE, SESSION_TYPE_FFMPEG);
        } else if (session.isFFprobe()) {
            sessionMap.put(KEY_SESSION_TYPE, SESSION_TYPE_FFPROBE);
        } else if (session.isMediaInformation()) {
            final MediaInformationSession mediaInformationSession = (MediaInformationSession) session;
            final MediaInformation mediaInformation = mediaInformationSession.getMediaInformation();
            if (mediaInformation != null) {
                sessionMap.put(KEY_SESSION_MEDIA_INFORMATION, toMap(mediaInformation));
            }
            sessionMap.put(KEY_SESSION_TYPE, SESSION_TYPE_MEDIA_INFORMATION);
        }

        return sessionMap;
    }

    protected static long toLong(final Date date) {
        if (date != null) {
            return date.getTime();
        } else {
            return 0;
        }
    }

    protected static int toInt(final LogRedirectionStrategy logRedirectionStrategy) {
        switch (logRedirectionStrategy) {
            case ALWAYS_PRINT_LOGS:
                return 0;
            case PRINT_LOGS_WHEN_NO_CALLBACKS_DEFINED:
                return 1;
            case PRINT_LOGS_WHEN_GLOBAL_CALLBACK_NOT_DEFINED:
                return 2;
            case PRINT_LOGS_WHEN_SESSION_CALLBACK_NOT_DEFINED:
                return 3;
            case NEVER_PRINT_LOGS:
            default:
                return 4;
        }
    }

    protected static LogRedirectionStrategy toLogRedirectionStrategy(final int value) {
        switch (value) {
            case 0:
                return LogRedirectionStrategy.ALWAYS_PRINT_LOGS;
            case 1:
                return LogRedirectionStrategy.PRINT_LOGS_WHEN_NO_CALLBACKS_DEFINED;
            case 2:
                return LogRedirectionStrategy.PRINT_LOGS_WHEN_GLOBAL_CALLBACK_NOT_DEFINED;
            case 3:
                return LogRedirectionStrategy.PRINT_LOGS_WHEN_SESSION_CALLBACK_NOT_DEFINED;
            case 4:
            default:
                return LogRedirectionStrategy.NEVER_PRINT_LOGS;
        }
    }

    protected static SessionState toSessionState(final int value) {
        switch (value) {
            case 0:
                return SessionState.CREATED;
            case 1:
                return SessionState.RUNNING;
            case 2:
                return SessionState.FAILED;
            case 3:
            default:
                return SessionState.COMPLETED;
        }
    }

    protected static Map<String, Object> toMap(final com.arthenica.ffmpegkit.Log log) {
        final HashMap<String, Object> logMap = new HashMap<>();

        logMap.put(KEY_LOG_SESSION_ID, log.getSessionId());
        logMap.put(KEY_LOG_LEVEL, toInt(log.getLevel()));
        logMap.put(KEY_LOG_MESSAGE, log.getMessage());

        return logMap;
    }

    protected static Map<String, Object> toMap(final Statistics statistics) {
        final HashMap<String, Object> statisticsMap = new HashMap<>();

        if (statistics != null) {
            statisticsMap.put(KEY_STATISTICS_SESSION_ID, statistics.getSessionId());
            statisticsMap.put(KEY_STATISTICS_VIDEO_FRAME_NUMBER, statistics.getVideoFrameNumber());
            statisticsMap.put(KEY_STATISTICS_VIDEO_FPS, statistics.getVideoFps());
            statisticsMap.put(KEY_STATISTICS_VIDEO_QUALITY, statistics.getVideoQuality());
            statisticsMap.put(KEY_STATISTICS_SIZE, (statistics.getSize() < Integer.MAX_VALUE) ? (int) statistics.getSize() : (int) (statistics.getSize() % Integer.MAX_VALUE));
            statisticsMap.put(KEY_STATISTICS_TIME, statistics.getTime());
            statisticsMap.put(KEY_STATISTICS_BITRATE, statistics.getBitrate());
            statisticsMap.put(KEY_STATISTICS_SPEED, statistics.getSpeed());
        }

        return statisticsMap;
    }

    protected static Map<String, Object> toMap(final MediaInformation mediaInformation) {
        if (mediaInformation != null) {
            Map<String, Object> map = new HashMap<>();

            if (mediaInformation.getAllProperties() != null) {
                JSONObject allProperties = mediaInformation.getAllProperties();
                if (allProperties != null) {
                    map = toMap(allProperties);
                }
            }

            return map;
        } else {
            return null;
        }
    }

    protected static Map<String, Object> toMap(final JSONObject jsonObject) {
        final HashMap<String, Object> map = new HashMap<>();

        if (jsonObject != null) {
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = jsonObject.opt(key);
                if (value != null) {
                    if (value instanceof JSONArray) {
                        value = toList((JSONArray) value);
                    } else if (value instanceof JSONObject) {
                        value = toMap((JSONObject) value);
                    }
                    map.put(key, value);
                }
            }
        }

        return map;
    }

    protected static List<Object> toList(final JSONArray array) {
        final List<Object> list = new ArrayList<>();

        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value != null) {
                if (value instanceof JSONArray) {
                    value = toList((JSONArray) value);
                } else if (value instanceof JSONObject) {
                    value = toMap((JSONObject) value);
                }
                list.add(value);
            }
        }

        return list;
    }

    protected static List<Map<String, Object>> toSessionMapList(final List<? extends Session> sessionList) {
        final List<Map<String, Object>> list = new ArrayList<>();

        for (int i = 0; i < sessionList.size(); i++) {
            list.add(toMap(sessionList.get(i)));
        }

        return list;
    }

    protected static List<Map<String, Object>> toLogMapList(final List<com.arthenica.ffmpegkit.Log> logList) {
        final List<Map<String, Object>> list = new ArrayList<>();

        for (int i = 0; i < logList.size(); i++) {
            list.add(toMap(logList.get(i)));
        }

        return list;
    }

    protected static List<Map<String, Object>> toStatisticsMapList(final List<com.arthenica.ffmpegkit.Statistics> statisticsList) {
        final List<Map<String, Object>> list = new ArrayList<>();

        for (int i = 0; i < statisticsList.size(); i++) {
            list.add(toMap(statisticsList.get(i)));
        }

        return list;
    }

    protected static boolean isValidPositiveNumber(final Integer value) {
        return (value != null) && (value >= 0);
    }

    protected void emitLog(final com.arthenica.ffmpegkit.Log log) {
        final HashMap<String, Object> logMap = new HashMap<>();
        logMap.put(EVENT_LOG_CALLBACK_EVENT, toMap(log));
        resultHandler.successAsync(eventSink, logMap);
    }

    protected void emitStatistics(final Statistics statistics) {
        final HashMap<String, Object> statisticsMap = new HashMap<>();
        statisticsMap.put(EVENT_STATISTICS_CALLBACK_EVENT, toMap(statistics));
        resultHandler.successAsync(eventSink, statisticsMap);
    }

    protected void emitSession(final Session session) {
        final HashMap<String, Object> sessionMap = new HashMap<>();
        sessionMap.put(EVENT_COMPLETE_CALLBACK_EVENT, toMap(session));
        resultHandler.successAsync(eventSink, sessionMap);
    }

}
