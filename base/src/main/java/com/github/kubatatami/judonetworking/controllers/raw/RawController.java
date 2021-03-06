package com.github.kubatatami.judonetworking.controllers.raw;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.github.kubatatami.judonetworking.Request;
import com.github.kubatatami.judonetworking.controllers.ProtocolController;
import com.github.kubatatami.judonetworking.exceptions.ConnectionException;
import com.github.kubatatami.judonetworking.exceptions.JudoException;
import com.github.kubatatami.judonetworking.exceptions.ParseException;
import com.github.kubatatami.judonetworking.internals.results.ErrorResult;
import com.github.kubatatami.judonetworking.internals.results.RequestResult;
import com.github.kubatatami.judonetworking.internals.results.RequestSuccessResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: jbogacki
 * Date: 21.10.2013
 * Time: 15:15
 * To change this template use File | Settings | File Templates.
 */
public abstract class RawController extends ProtocolController {


    @Override
    public abstract RequestInfo createRequest(String url, Request request) throws JudoException;

    @Override
    public RequestResult parseResponse(Request request, InputStream stream, Map<String, List<String>> headers) {
        return parseResponse(request, stream);
    }

    public static RequestResult parseResponse(Request request, InputStream stream) {
        Type returnType = request.getReturnType();
        if (String.class.equals(returnType)) {
            return new RequestSuccessResult(request.getId(), convertStreamToString(stream));
        } else if (Byte[].class.equals(returnType) || byte[].class.equals(returnType)) {
            try {
                return new RequestSuccessResult(request.getId(), getByteArray(stream));
            } catch (Exception e) {
                return new ErrorResult(request.getId(), new ConnectionException(e));
            }
        } else if (Bitmap.class.equals(returnType)) {
            try {
                Bitmap bitmap = BitmapFactory.decodeStream(stream);
                return new RequestSuccessResult(request.getId(), bitmap);
            } catch (Exception e) {
                return new ErrorResult(request.getId(), new ConnectionException(e));
            }
        } else {
            return new ErrorResult(request.getId(), new ParseException("RawController handle string or byte array response only."));
        }
    }

    protected static byte[] getByteArray(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = stream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

}
