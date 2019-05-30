package com.github.kubatatami.judonetworking.internals;

import com.github.kubatatami.judonetworking.CacheInfo;
import com.github.kubatatami.judonetworking.Endpoint;
import com.github.kubatatami.judonetworking.annotations.HandleException;
import com.github.kubatatami.judonetworking.batches.Batch;
import com.github.kubatatami.judonetworking.callbacks.AsyncResultCallback;
import com.github.kubatatami.judonetworking.callbacks.CacheInfoCallback;
import com.github.kubatatami.judonetworking.callbacks.Callback;
import com.github.kubatatami.judonetworking.exceptions.JudoException;
import com.github.kubatatami.judonetworking.internals.requests.RequestImpl;
import com.github.kubatatami.judonetworking.logs.JudoLogger;

import java.lang.reflect.Method;
import java.util.List;

public class AsyncResultSender implements Runnable {

    protected Callback<Object> callback;

    protected RequestProxy requestProxy;

    protected Object result = null;

    protected Object[] results = null;

    protected JudoException e = null;

    protected int progress = 0;

    protected final Type type;

    protected Integer methodId;

    protected EndpointImpl rpc;

    protected RequestImpl request;

    protected List<RequestImpl> requests;

    protected CacheInfo cacheInfo;

    enum Type {
        RESULT, ERROR, PROGRESS, START
    }

    public AsyncResultSender(EndpointImpl rpc, RequestProxy requestProxy) {
        this.requestProxy = requestProxy;
        this.rpc = rpc;
        this.type = Type.START;
    }

    public AsyncResultSender(EndpointImpl rpc, RequestProxy requestProxy, Object results[]) {
        this.results = results;
        this.requestProxy = requestProxy;
        this.rpc = rpc;
        this.type = Type.RESULT;
    }

    public AsyncResultSender(EndpointImpl rpc, RequestProxy requestProxy, int progress) {
        this.progress = progress;
        this.requestProxy = requestProxy;
        this.rpc = rpc;
        this.type = Type.PROGRESS;
    }

    public AsyncResultSender(EndpointImpl rpc, RequestProxy requestProxy, JudoException e) {
        this.e = e;
        this.requestProxy = requestProxy;
        this.rpc = rpc;
        this.type = Type.ERROR;
    }

    public AsyncResultSender(RequestImpl request, CacheInfo cacheInfo) {
        this.callback = request.getCallback();
        this.request = request;
        this.rpc = request.getRpc();
        this.type = Type.START;
        this.cacheInfo = cacheInfo;
    }

    public AsyncResultSender(RequestImpl request, Object result) {
        this.result = result;
        this.callback = request.getCallback();
        this.request = request;
        this.rpc = request.getRpc();
        this.methodId = request.getMethodId();
        this.type = Type.RESULT;
    }

    public AsyncResultSender(RequestImpl request, int progress) {
        this.progress = progress;
        this.callback = request.getCallback();
        this.request = request;
        this.rpc = request.getRpc();
        this.methodId = request.getMethodId();
        this.type = Type.PROGRESS;
    }

    public AsyncResultSender(List<RequestImpl> requests, int progress) {
        this.progress = progress;
        this.requests = requests;
        this.type = Type.PROGRESS;
    }

    public AsyncResultSender(List<RequestImpl> requests) {
        this.requests = requests;
        this.type = Type.START;
        this.cacheInfo = new CacheInfo(false, 0L);
    }

    public AsyncResultSender(RequestImpl request, JudoException e) {
        this.e = e;
        this.callback = request.getCallback();
        this.request = request;
        this.rpc = request.getRpc();
        this.methodId = request.getMethodId();
        this.type = Type.ERROR;
    }

    protected Method findHandleMethod(Class<?> callbackClass, Class<?> exceptionClass) {
        Method handleMethod = null;
        for (; callbackClass != null; callbackClass = callbackClass.getSuperclass()) {
            for (Method method : callbackClass.getMethods()) {
                HandleException handleException = method.getAnnotation(HandleException.class);
                if (handleException != null && handleException.enabled()) {
                    if (method.getParameterTypes().length != 1) {
                        throw new RuntimeException("Method " + method.getName() + " annotated HandleException must have one parameter.");
                    }
                    Class<?> handleExceptionClass = method.getParameterTypes()[0];
                    if (handleExceptionClass.isAssignableFrom(exceptionClass)) {
                        if (handleMethod == null || handleMethod.getParameterTypes()[0].isAssignableFrom(handleExceptionClass)) {
                            handleMethod = method;
                        }
                    }
                }
            }
        }

        return handleMethod;
    }

    @Override
    public void run() {
        if (callback != null) {
            if (request.isCancelled()) {
                return;
            }
            sendRequestEvent();
        } else if (requestProxy != null && requestProxy.getBatchCallback() != null) {
            Batch<?> transaction = requestProxy.getBatchCallback();
            if (requestProxy.isCancelled()) {
                return;
            }
            sendBatchEvent(transaction);
        } else if (requests != null) {
            sendRequestsEvent();
        }
    }

    private void sendRequestsEvent() {
        for (RequestImpl batchRequest : requests) {
            if (batchRequest.getCallback() != null) {
                if (type == Type.PROGRESS) {
                    batchRequest.getCallback().onProgress(progress);
                } else if (type == Type.START) {
                    batchRequest.getCallback().onStart(cacheInfo, batchRequest);
                }
            }
        }
    }

    private void sendStopRequest() {
        JudoLogger.log("Send stop request event(" + request.getName() + ":" + request.getId() + ")", JudoLogger.LogLevel.VERBOSE);
        rpc.stopRequest(request);

    }

    private void removeFromSingleCallMethods() {
        if (methodId != null) {
            synchronized (rpc.getSingleCallMethods()) {
                boolean result = rpc.getSingleCallMethods().remove(methodId) != null;
                if (result && (rpc.getDebugFlags() & Endpoint.REQUEST_LINE_DEBUG) > 0) {
                    JudoLogger.log("Request " + request.getName() + "(" + methodId + ")" + " removed from SingleCall queue.", JudoLogger.LogLevel.VERBOSE);
                }
            }
        }
    }

    private void sendBatchEvent(Batch<?> transaction) {
        JudoLogger.log("Send batch event:" + type, JudoLogger.LogLevel.VERBOSE);
        switch (type) {
            case START:
                requestProxy.start();
                if (callback instanceof AsyncResultCallback) {
                    ((AsyncResultCallback) callback).setAsyncResult(request);
                }
                transaction.onStart(requestProxy);
                break;
            case RESULT:
                requestProxy.calcTime();
                transaction.onSuccess(results);
                doneBatch(transaction);
                break;
            case ERROR:
                Method handleMethod = findHandleMethod(transaction.getClass(), e.getClass());
                logError("Batch", e);
                requestProxy.calcTime();
                if (handleMethod != null) {
                    try {
                        handleMethod.invoke(transaction, e);
                    } catch (Exception invokeException) {
                        throw new RuntimeException(invokeException);
                    }
                } else {
                    transaction.onError(e);
                }
                doneBatch(transaction);
                break;
            case PROGRESS:
                transaction.onProgress(progress);
                break;
        }
    }

    private void doneBatch(Batch<?> transaction) {
        transaction.onFinish();
        requestProxy.done();
        requestProxy.clearBatchCallback();
    }

    private void sendRequestEvent() {
        JudoLogger.log("Send request event(" + request.getName() + ":" + request.getId() + "):" + type, JudoLogger.LogLevel.VERBOSE);
        switch (type) {
            case START:
                request.start();
                if (callback instanceof AsyncResultCallback) {
                    ((AsyncResultCallback) callback).setAsyncResult(request);
                }
                if (callback instanceof CacheInfoCallback) {
                    ((CacheInfoCallback) callback).setCacheInfo(cacheInfo);
                }
                callback.onStart(cacheInfo, request);
                break;
            case RESULT:
                callback.onSuccess(result);
                doneRequest();
                break;
            case ERROR:
                Method handleMethod = findHandleMethod(callback.getClass(), e.getClass());
                logError(request.getName(), e);
                if (handleMethod != null) {
                    try {
                        handleMethod.invoke(callback, e);
                    } catch (Exception invokeException) {
                        throw new RuntimeException(invokeException);
                    }
                } else {
                    callback.onError(e);
                }
                doneRequest();
                break;
            case PROGRESS:
                callback.onProgress(progress);
                break;
        }
    }

    private void doneRequest() {
        callback.onFinish();
        request.done();
        removeFromSingleCallMethods();
    }

    protected void logError(String requestName, Exception ex) {
        if ((rpc.getDebugFlags() & Endpoint.ERROR_DEBUG) > 0) {
            if (requestName != null) {
                JudoLogger.log("Error on: " + requestName, JudoLogger.LogLevel.ERROR);
            }
            JudoLogger.log(ex);
        }
    }
}