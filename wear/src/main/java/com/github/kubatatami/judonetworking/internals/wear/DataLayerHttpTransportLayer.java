package com.github.kubatatami.judonetworking.internals.wear;

import android.content.Context;

import com.github.kubatatami.judonetworking.Endpoint;
import com.github.kubatatami.judonetworking.controllers.ProtocolController;
import com.github.kubatatami.judonetworking.exceptions.CancelException;
import com.github.kubatatami.judonetworking.exceptions.ConnectionException;
import com.github.kubatatami.judonetworking.exceptions.JudoException;
import com.github.kubatatami.judonetworking.internals.executors.JudoExecutor;
import com.github.kubatatami.judonetworking.internals.stats.TimeStat;
import com.github.kubatatami.judonetworking.logs.JudoLogger;
import com.github.kubatatami.judonetworking.transports.HttpTransportLayer;
import com.github.kubatatami.judonetworking.utils.ReflectionCache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Kuba on 16/11/14.
 */
public class DataLayerHttpTransportLayer extends HttpTransportLayer {


    protected MessageUtils messageUtils;

    public DataLayerHttpTransportLayer(Context context) {
        messageUtils = new MessageUtils(context);
    }

    protected void initSetup(WearRequest request, ProtocolController.RequestInfo requestInfo,
                             int timeout, TimeStat timeStat) throws Exception {
        request.setFollowRedirects(followRedirection);
        if (requestInfo.mimeType != null) {
            request.addHeader("Content-Type", requestInfo.mimeType);
        }
        request.setConnectTimeout(connectTimeout);

        if (timeout == 0) {
            timeout = methodTimeout;
        }
        timeStat.setTimeout(timeout);
        request.setReadTimeout(timeout);


        if (requestInfo.customHeaders != null) {
            for (Map.Entry<String, String> entry : requestInfo.customHeaders.entrySet()) {
                request.addHeader(entry.getKey(), entry.getValue());
            }
        }
    }

    protected WearResponse sendRequest(WearRequest request, final ProtocolController.RequestInfo requestInfo,
                                       final TimeStat timeStat, Method method, int debugFlags) throws Exception {
        String methodName = "GET";
        try {

            if (requestInfo.entity != null) {
                methodName = "POST";
                request.setMimeType(requestInfo.mimeType);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                requestInfo.entity.writeTo(outputStream);
                request.setBody(outputStream.toByteArray());
            }
            if ((debugFlags & Endpoint.REQUEST_DEBUG) > 0) {
                if (request.getBody() != null && request.getBody().length > 0 && requestInfo.entity != null) {
                    longLog("Request(" + requestInfo.url + ")", requestInfo.entity.getLog(), JudoLogger.LogLevel.INFO);
                } else {
                    longLog("Request", requestInfo.url, JudoLogger.LogLevel.INFO);
                }
            }

            if (method != null) {
                HttpMethod ann = ReflectionCache.getAnnotationInherited(method, HttpMethod.class);
                if (ann != null) {
                    methodName = ann.value();
                }
            }
            request.setMethodName(methodName);

            if (Thread.currentThread() instanceof JudoExecutor.ConnectionThread) {
                JudoExecutor.ConnectionThread connectionThread = (JudoExecutor.ConnectionThread) Thread.currentThread();
                connectionThread.setCanceller(new JudoExecutor.ConnectionThread.Canceller() {
                    @Override
                    public void cancel() {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                //TODO
                            }
                        }).start();
                    }
                });
            }
            WearResponse response = makeCallAndGetResponse(request);

            timeStat.tickConnectionTime();
            timeStat.tickSendTime();
            return response;
        } finally {

            if (requestInfo.entity != null) {
                requestInfo.entity.close();
            }
        }

    }


    protected WearResponse makeCallAndGetResponse(WearRequest request) throws IOException {
        try {
            int timeoutSum = request.getConnectTimeout() + request.getReadTimeout();
            return messageUtils.sendMessageAndReceive(request, timeoutSum, WearResponse.class);
        } catch (IOException ex) {
            if (Thread.currentThread() instanceof JudoExecutor.ConnectionThread) {
                JudoExecutor.ConnectionThread thread = (JudoExecutor.ConnectionThread) Thread.currentThread();
                if (thread.isCanceled()) {
                    thread.resetCanceled();
                    throw new CancelException(thread.getName());
                } else {
                    throw ex;
                }
            } else {
                throw ex;
            }
        }
    }

    @Override
    public Connection send(String requestName, ProtocolController protocolController, ProtocolController.RequestInfo requestInfo,
                           int timeout, TimeStat timeStat, int debugFlags, Method method) throws JudoException {
        WearRequest request = new WearRequest();
        try {
            request.setUrl(requestInfo.url);
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            initSetup(request, requestInfo, timeout, timeStat);

            logRequestHeaders(requestName, debugFlags, request);

            long wearTime = System.currentTimeMillis();

            WearResponse response = sendRequest(request, requestInfo, timeStat, method, debugFlags);

            wearTime = System.currentTimeMillis() - wearTime - response.getRealRequestTime();

            logResponseHeaders(requestName, debugFlags, response);

            JudoLogger.log("Wear send and read sum time: " + wearTime + "ms", JudoLogger.LogLevel.INFO);

            if (!response.isSuccessful()) {
                int code = response.getCode();
                String message = response.getMessage();
                String body = new String(response.getBody());
                handleHttpException(protocolController, code, message, body);
            }

            if ((debugFlags & Endpoint.RESPONSE_DEBUG) > 0) {
                longLog("Response code(" + requestName + ")", response.getCode() + "", JudoLogger.LogLevel.DEBUG);
            }

            return new DataLayerConnection(response);
        } catch (Exception ex) {
            if (!(ex instanceof JudoException)) {
                throw new ConnectionException(ex);
            } else {
                throw (JudoException) ex;
            }
        }
    }

    static class DataLayerConnection implements Connection{
        InputStream stream;
        WearResponse response;

        public DataLayerConnection(WearResponse response) {
            this.response = response;
        }

        @Override
        public InputStream getStream() throws ConnectionException {
            if (stream == null) {
                stream = new ByteArrayInputStream(response.getBody());
            }
            return stream;
        }

        @Override
        public int getContentLength() {
            return response.getBody().length;
        }

        public Map<String, List<String>> getHeaders() {
            Map<String, List<String>> map = new HashMap<>();
            for (String name : response.getHeaders().keySet()) {
                map.put(name, response.getHeaders(name));
            }
            return map;
        }

        @Override
        public Long getDate() {
            String lastModified = response.getHeader("Last-Modified");
            if (lastModified != null) {

                try {
                    Date date = format.parse(lastModified);
                    return date.getTime();
                } catch (ParseException e) {
                    return null;
                }

            } else {
                return null;
            }
        }

        @Override
        public void close() {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    protected void logResponseHeaders(String requestName, int debugFlags, WearResponse response) {
        if ((debugFlags & Endpoint.HEADERS_DEBUG) > 0) {
            String headers = "";
            for (String key : response.getHeaders().keySet()) {
                if (key != null) {
                    headers += key + ":" + response.getHeader(key) + " ";
                }
            }
            longLog("Response headers(" + requestName + ")", headers, JudoLogger.LogLevel.DEBUG);
        }
    }

    protected void logRequestHeaders(String requestName, int debugFlags, WearRequest request) {
        if ((debugFlags & Endpoint.HEADERS_DEBUG) > 0) {
            String headers = "";
            for (String key : request.getHeaders().keySet()) {
                headers += key + ":" + request.getHeaders().get(key) + " ";
            }
            longLog("Request headers(" + requestName + ")", headers, JudoLogger.LogLevel.DEBUG);
        }

    }

    public void setWearConnectionTimeout(long wearConnectionTimeout) {
        messageUtils.setConnectionTimeout(wearConnectionTimeout);
    }

    public void setWearSendTimeout(long wearSendTimeout) {
        messageUtils.setSendTimeout(wearSendTimeout);
    }

    public void setWearReadTimeout(long wearReadTimeout) {
        messageUtils.setReadTimeout(wearReadTimeout);
    }

    @Override
    public void setMaxConnections(int max) {

    }

    /**
     * Created by Kuba on 28/09/15.
     */
    public static class WearResponse {

        private Map<String, List<String>> headers;

        private int code;

        private byte[] body;

        private boolean successful;

        private String message;

        private long realRequestTime;

        public WearResponse() {
        }

        public WearResponse(Exception exception) {
            this.message = exception.getMessage();
            this.headers = new HashMap<>();
            this.code = 0;
            this.body = new byte[0];
            this.successful = false;
        }

        public Map<String, List<String>> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, List<String>> headers) {
            this.headers = headers;
        }

        public int getCode() {
            return code;
        }

        public String getHeader(String key) {
            if (headers.containsKey(key)) {
                return headers.get(key).get(0);
            } else {
                return null;
            }
        }

        public void setCode(int code) {
            this.code = code;
        }

        public byte[] getBody() {
            return body;
        }

        public void setBody(byte[] body) {
            this.body = body;
        }

        public boolean isSuccessful() {
            return successful;
        }

        public String getMessage() {
            return message;
        }

        public List<String> getHeaders(String name) {
            return headers.get(name);
        }

        public void setSuccessful(boolean successful) {
            this.successful = successful;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public long getRealRequestTime() {
            return realRequestTime;
        }

        public void setRealRequestTime(long realRequestTime) {
            this.realRequestTime = realRequestTime;
        }

        @Override
        public String toString() {
            return "WearResponse{" +
                    "headers=" + headers +
                    ", code=" + code +
                    ", body=" + MessageUtils.getBodyString(body) +
                    ", successful=" + successful +
                    ", message='" + message + '\'' +
                    ", realRequestTime=" + realRequestTime +
                    '}';
        }
    }

    public static class WearRequest {

        private byte[] body;

        private boolean followRedirects;

        private Map<String, String> headers = new HashMap<>();

        private int connectTimeout;

        private int readTimeout;

        private String url;

        private String mimeType;

        private String methodName;

        public void setFollowRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
        }

        public void addHeader(String key, String value) {
            headers.put(key, value);
        }

        public void setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public void setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public boolean isFollowRedirects() {
            return followRedirects;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public int getConnectTimeout() {
            return connectTimeout;
        }

        public int getReadTimeout() {
            return readTimeout;
        }

        public String getUrl() {
            return url;
        }

        public byte[] getBody() {
            return body;
        }

        public void setBody(byte[] body) {
            this.body = body;
        }

        public String getMimeType() {
            return mimeType;
        }

        public void setMimeType(String mimeType) {
            this.mimeType = mimeType;
        }

        public void setMethodName(String methodName) {
            this.methodName = methodName;
        }

        public String getMethodName() {
            return methodName;
        }

        @Override
        public String toString() {
            return "WearRequest{" +
                    "body=" + MessageUtils.getBodyString(body) +
                    ", followRedirects=" + followRedirects +
                    ", headers=" + headers +
                    ", connectTimeout=" + connectTimeout +
                    ", readTimeout=" + readTimeout +
                    ", url='" + url + '\'' +
                    ", mimeType='" + mimeType + '\'' +
                    ", methodName='" + methodName + '\'' +
                    '}';
        }
    }
}
