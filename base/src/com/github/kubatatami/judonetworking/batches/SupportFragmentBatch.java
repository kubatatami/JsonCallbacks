package com.github.kubatatami.judonetworking.batches;

import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;

import com.github.kubatatami.judonetworking.AsyncResult;
import com.github.kubatatami.judonetworking.callbacks.MergeCallback;
import com.github.kubatatami.judonetworking.exceptions.JudoException;

import java.lang.ref.WeakReference;

/**
 * Created with IntelliJ IDEA.
 * User: jbogacki
 * Date: 11.02.2013
 * Time: 22:48
 */
public abstract class SupportFragmentBatch<T> extends DefaultBatch<T> implements FragmentManager.OnBackStackChangedListener{


    private final WeakReference<Fragment> fragment;
    private final WeakReference<FragmentManager> manager;
    private AsyncResult asyncResult;

    public SupportFragmentBatch(Fragment fragment) {
        this(null, fragment);
    }

    protected SupportFragmentBatch(MergeCallback mergeCallback, Fragment fragment) {
        super(mergeCallback);
        this.fragment = new WeakReference<>(fragment);
        this.manager = new WeakReference<>(fragment.getFragmentManager());
        if (manager.get() != null) {
            manager.get().addOnBackStackChangedListener(this);
        }
    }


    @Override
    public void onBackStackChanged() {
        if (!isActive()) {
            tryCancel();
        }
    }

    protected void tryCancel() {
        if (asyncResult != null) {
            asyncResult.cancel();
            if(manager.get()!=null) {
                manager.get().removeOnBackStackChangedListener(this);
            }
        }
    }


    @Override
    public final void onStart(AsyncResult asyncResult) {
        super.onStart(asyncResult);
        this.asyncResult = asyncResult;
        if (isActive()) {
            onSafeStart(asyncResult);
        } else {
            tryCancel();
        }
    }

    @Override
    public void run(final T api) {

    }

    @Override
    public void runNonFatal(final T api) {
    }

    @Override
    public final void onSuccess(Object[] results) {
        super.onSuccess(results);
        if (isActive()) {
            onSafeSuccess(results);
        } else {
            tryCancel();
        }
    }

    @Override
    public final void onError(JudoException e) {
        super.onError(e);
        if (isActive()) {
            onSafeError(e);
        } else {
            tryCancel();
        }
    }

    @Override
    public final void onProgress(int progress) {
        super.onProgress(progress);
        if (isActive()) {
            onSafeProgress(progress);
        } else {
            tryCancel();
        }
    }

    @Override
    public final void onFinish() {
        if (isActive()) {
            onSafeFinish();
        } else {
            tryCancel();
        }
        if(manager.get()!=null) {
            manager.get().removeOnBackStackChangedListener(this);
        }
    }

    protected boolean isActive(){
        return fragment.get()!=null && fragment.get().getActivity() != null;
    }

    public void onSafeStart(AsyncResult asyncResult) {

    }


    public void onSafeSuccess(Object[] results) {
    }


    public void onSafeError(JudoException e) {

    }


    public void onSafeProgress(int progress) {

    }


    public void onSafeFinish() {

    }
}
