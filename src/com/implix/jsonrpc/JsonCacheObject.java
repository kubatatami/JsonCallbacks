package com.implix.jsonrpc;

/**
 * Created with IntelliJ IDEA.
 * User: jbogacki
 * Date: 07.03.2013
 * Time: 07:55
 * To change this template use File | Settings | File Templates.
 */
class JsonCacheObject {
    long createTime;
    Object object;

    JsonCacheObject(long createTime, Object object) {
        this.createTime = createTime;
        this.object = object;
    }

    public long getCreateTime() {
        return createTime;
    }

    public Object getObject() {
        return object;
    }
}