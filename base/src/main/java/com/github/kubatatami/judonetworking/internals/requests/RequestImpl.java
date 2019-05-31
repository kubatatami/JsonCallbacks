package com.github.kubatatami.judonetworking.internals.requests;

import com.github.kubatatami.judonetworking.AsyncResult;
import com.github.kubatatami.judonetworking.CacheInfo;
import com.github.kubatatami.judonetworking.Endpoint;
import com.github.kubatatami.judonetworking.Request;
import com.github.kubatatami.judonetworking.annotations.ApiKeyRequired;
import com.github.kubatatami.judonetworking.annotations.Delay;
import com.github.kubatatami.judonetworking.annotations.LocalCache;
import com.github.kubatatami.judonetworking.annotations.RejectOnMonkeyTest;
import com.github.kubatatami.judonetworking.annotations.RequestMethod;
import com.github.kubatatami.judonetworking.annotations.SingleCall;
import com.github.kubatatami.judonetworking.callbacks.Callback;
import com.github.kubatatami.judonetworking.callbacks.DefaultCallback;
import com.github.kubatatami.judonetworking.exceptions.CancelException;
import com.github.kubatatami.judonetworking.exceptions.JudoException;
import com.github.kubatatami.judonetworking.internals.AsyncResultSender;
import com.github.kubatatami.judonetworking.internals.EndpointImpl;
import com.github.kubatatami.judonetworking.internals.ProgressObserver;
import com.github.kubatatami.judonetworking.internals.RequestProxy;
import com.github.kubatatami.judonetworking.internals.cache.CacheMethod;
import com.github.kubatatami.judonetworking.internals.stats.TimeStat;
import com.github.kubatatami.judonetworking.logs.ErrorLogger;
import com.github.kubatatami.judonetworking.logs.JudoLogger;
import com.github.kubatatami.judonetworking.utils.ReflectionCache;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

public class RequestImpl implements Runnable, Comparable<RequestImpl>, ProgressObserver, Request, AsyncResult {

    private Integer id;

    private final EndpointImpl rpc;

    private Callback<?> callback;

    private final String name;

    private final int timeout;

    private RequestMethod ann;

    private float progress = 0;

    private int max = TimeStat.TICKS;

    private Object[] args;

    private String[] paramNames;

    private Type returnType;

    private Method method;

    private Class<?> apiInterface;

    private boolean batchFatal = true;

    private Serializable additionalControllerData = null;

    private boolean cancelled, done, running;

    private boolean isApiKeyRequired;

    private String customUrl;

    private Future<?> future;

    private Map<String, List<String>> headers;

    private long startTimeMillis;

    private long endTimeMillis;

    private long totalTimeMillis;

    public RequestImpl(EndpointImpl rpc, Method method, String name, RequestMethod ann,
                       Object[] args, Type returnType, int timeout, Callback<?> callback,
                       Serializable additionalControllerData) {
        this.id = rpc.getNextId();
        this.name = name;
        this.timeout = timeout;
        this.method = method;
        if (method != null) {
            this.apiInterface = method.getDeclaringClass();
        }
        this.rpc = rpc;
        this.ann = ann;
        this.args = args;
        this.returnType = returnType;
        this.callback = callback == null ? new DefaultCallback<>() : callback;
        this.additionalControllerData = additionalControllerData;
        this.paramNames = ann.paramNames();
    }

    public RequestImpl(EndpointImpl rpc, Method method, String name, RequestMethod ann, int timeout, Serializable additionalControllerData) {
        this.id = rpc.getNextId();
        this.name = name;
        this.timeout = timeout;
        this.method = method;
        if (method != null) {
            this.apiInterface = method.getDeclaringClass();
        }
        this.rpc = rpc;
        this.ann = ann;
        this.callback = new DefaultCallback<>();
        this.additionalControllerData = additionalControllerData;
        this.paramNames = ann.paramNames();
    }



    @Override
    public void run() {
        try {
            if (!cancelled) {
                Object result = rpc.getRequestConnector().call(this);
                invokeCallback(result);
            }
        } catch (final JudoException e) {
            invokeCallbackException(e);
            if (rpc.getErrorLoggers().size() != 0 && !(e instanceof CancelException) && !cancelled) {
                rpc.getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        for (ErrorLogger errorLogger : rpc.getErrorLoggers()) {
                            errorLogger.onError(e, RequestImpl.this);
                        }
                    }
                });
            }
        }
    }

    public void invokeStart(CacheInfo cacheInfo) {
        startTimeMillis = System.currentTimeMillis();
        if (callback != null) {
            rpc.getHandler().post(new AsyncResultSender(this, cacheInfo));
        }
    }

    public void invokeCallbackException(JudoException e) {
        calcTime();
        rpc.getHandler().post(new AsyncResultSender(this, e));
    }

    public void invokeCallback(Object result) {
        calcTime();
        rpc.getHandler().post(new AsyncResultSender(this, result));
    }

    private void calcTime() {
        endTimeMillis = System.currentTimeMillis();
        totalTimeMillis = endTimeMillis - startTimeMillis;
    }

    public static void invokeBatchCallbackStart(final EndpointImpl rpc, RequestProxy requestProxy) {
        rpc.getHandler().post(new AsyncResultSender(rpc, requestProxy));
    }

    public static void invokeBatchCallbackProgress(final EndpointImpl rpc, RequestProxy requestProxy, int progress) {
        rpc.getHandler().post(new AsyncResultSender(rpc, requestProxy, progress));
    }

    public static void invokeBatchCallbackException(final EndpointImpl rpc, RequestProxy requestProxy, final JudoException e) {
        rpc.getHandler().post(new AsyncResultSender(rpc, requestProxy, e));
    }

    public static void invokeBatchCallback(EndpointImpl rpc, RequestProxy requestProxy, Object[] results) {
        rpc.getHandler().post(new AsyncResultSender(rpc, requestProxy, results));
    }

    @Override
    public Integer getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object[] getArgs() {
        return args;
    }

    @Override
    public Type getReturnType() {
        return returnType;
    }

    @Override
    public boolean isVoidResult() {
        return returnType.equals(Void.TYPE) || returnType.equals(Void.class);
    }

    @Override
    public boolean isStringResult() {
        return returnType.equals(String.class);
    }

    @Override
    public String[] getParamNames() {
        return paramNames;
    }

    @Override
    public int getMethodId() {
        return CacheMethod.getMethodId(method);
    }

    @Override
    public Method getMethod() {
        return method;
    }

    @Override
    public boolean isAllowEmptyResult() {
        return ann.allowEmptyResult();
    }

    @Override
    public boolean isApiKeyRequired() {
        if (method != null) {
            ApiKeyRequired ann = ReflectionCache.getAnnotation(method, ApiKeyRequired.class);
            if (ann == null) {
                ann = ReflectionCache.getAnnotation(apiInterface, ApiKeyRequired.class);
            }
            return ann != null && ann.enabled();
        } else {
            return isApiKeyRequired;
        }
    }

    @Override
    public void setArgs(Object[] args) {
        this.args = args;
    }

    public void setCallback(Callback<?> callback) {
        this.callback = callback;
    }

    public void setReturnType(Type returnType) {
        this.returnType = returnType;
    }

    @Override
    public void setParamNames(String[] paramNames) {
        this.paramNames = paramNames;
    }

    @Override
    public Serializable getAdditionalData() {
        return additionalControllerData;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public LocalCache getLocalCache() {
        if (method != null) {
            LocalCache ann = ReflectionCache.getAnnotationInherited(method, LocalCache.class);
            if (ann != null && !ann.enabled()) {
                ann = null;
            }
            return ann;
        } else {
            return null;
        }
    }

    public int getDelay() {
        if (method != null) {
            Delay ann = ReflectionCache.getAnnotationInherited(method, Delay.class);
            if (ann != null && !ann.enabled()) {
                ann = null;
            }
            return ann != null ? ann.value() : 0;
        } else {
            return 0;
        }
    }


    public SingleCall getSingleCall() {
        if (method != null) {
            SingleCall ann = ReflectionCache.getAnnotationInherited(method, SingleCall.class);
            if (ann != null && !ann.enabled()) {
                ann = null;
            }
            return ann;
        } else {
            return null;
        }
    }

    public int getLocalCacheLifeTime() {
        int lifeTime = getLocalCache().lifeTime();
        if (lifeTime == LocalCache.DEFAULT) {
            return rpc.getDefaultMethodCacheLifeTime();
        } else {
            return lifeTime;
        }
    }

    public boolean isLocalCacheable() {
        return getLocalCache() != null;
    }

    public int getLocalCacheSize() {
        int size = getLocalCache().size();
        if (size == LocalCache.DEFAULT) {
            return rpc.getDefaultMethodCacheSize();
        } else {
            return size;
        }
    }

    public LocalCache.CacheLevel getLocalCacheLevel() {
        LocalCache.CacheLevel level = getLocalCache().cacheLevel();
        if (level.equals(LocalCache.CacheLevel.DEFAULT)) {
            return rpc.getDefaultMethodCacheLevel();
        } else {
            return level;
        }
    }

    public LocalCache.OnlyOnError getLocalCacheOnlyOnErrorMode() {
        LocalCache localCache = getLocalCache();
        if (localCache == null) {
            return LocalCache.OnlyOnError.NO;
        }
        LocalCache.OnlyOnError onlyOnError = localCache.onlyOnError();
        if (onlyOnError.equals(LocalCache.OnlyOnError.DEFAULT)) {
            return rpc.getDefaultMethodCacheOnlyOnErrorMode();
        } else {
            return onlyOnError;
        }
    }

    public long getWeight() {
        if (rpc.getStats().containsKey(name)) {
            return Math.max(rpc.getStats().get(name).methodTime, 1);
        } else {
            return timeout / 2;
        }
    }

    @Override
    public int compareTo(RequestImpl another) {
        if (ann.highPriority() && !another.isHighPriority()) {
            return -1;
        } else if (!ann.highPriority() && another.isHighPriority()) {
            return 1;
        } else {
            return Long.valueOf(another.getWeight()).compareTo(getWeight());
        }
    }


    public boolean isHighPriority() {
        return ann.highPriority();
    }

    @Override
    public void clearProgress() {
        this.progress = 0;
        tick();
    }

    @Override
    public void progressTick() {
        this.progress++;
        tick();
    }

    @Override
    public void progressTick(float progress) {
        this.progress += progress;
        tick();
    }

    private void tick() {
        if (callback != null) {
            rpc.getHandler().post(new AsyncResultSender(this, ((int) this.progress * 100 / max)));
        }
    }

    public EndpointImpl getRpc() {
        return rpc;
    }

    @Override
    public void setMaxProgress(int max) {
        this.max = max;
    }

    @Override
    public int getMaxProgress() {
        return max;
    }

    @SuppressWarnings("unchecked")
    public Callback<Object> getCallback() {
        return (Callback<Object>) callback;
    }

    public boolean isBatchFatal() {
        return batchFatal;
    }

    public void setBatchFatal(boolean batchFatal) {
        this.batchFatal = batchFatal;
    }

    public void setAdditionalControllerData(Serializable additionalControllerData) {
        this.additionalControllerData = additionalControllerData;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        if (done || cancelled) {
            return;
        }
        this.cancelled = true;
        synchronized (this) {
            notifyAll();
        }
        if ((rpc.getDebugFlags() & Endpoint.CANCEL_DEBUG) > 0) {
            JudoLogger.log("Request " + name + " cancelled.", JudoLogger.LogLevel.DEBUG);
        }
        if (running) {
            running = false;
            synchronized (rpc.getSingleCallMethods()) {
                rpc.getSingleCallMethods().remove(CacheMethod.getMethodId(method));
            }

            if (future != null) {
                future.cancel(true);
            }
            rpc.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onFinish();
                }
            });
        }
        rpc.stopRequest(RequestImpl.this);
    }

    @Override
    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, List<String>> headers) {
        this.headers = headers;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    @Override
    public long getEndTimeMillis() {
        return endTimeMillis;
    }

    @Override
    public long getTotalTimeMillis() {
        return totalTimeMillis;
    }

    public void done() {
        synchronized (this) {
            this.done = true;
            this.running = false;
            notifyAll();
        }
    }

    @Override
    public void await() throws InterruptedException {
        synchronized (this) {
            if (!isDone() && !isCancelled()) {
                wait();
            }
        }
    }

    public void start() {
        this.running = true;
    }

    public String getCustomUrl() {
        return customUrl;
    }

    public void setCustomUrl(String customUrl) {
        this.customUrl = customUrl;
    }

    public void setApiKeyRequired(boolean isApiKeyRequired) {
        this.isApiKeyRequired = isApiKeyRequired;
    }

    public void setFuture(Future<?> future) {
        this.future = future;
    }

    public boolean isRejectOnMonkeyTest() {
        if (method != null) {
            RejectOnMonkeyTest ann = ReflectionCache.getAnnotationInherited(method, RejectOnMonkeyTest.class);
            if (ann != null) {
                return ann.enabled();
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "id=" + id + " name=" + name;
    }

    public void reset() {
        this.done = false;
        this.running = false;
        this.cancelled = false;
        this.progress = 0;
    }
}