package org.brutusin.instrumentation.performance;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import org.apache.commons.io.IOUtils;
import org.brutusin.instrumentation.Callback;
import org.brutusin.instrumentation.FrameData;
import org.brutusin.instrumentation.Listener;
import org.brutusin.instrumentation.utils.Helper;

/**
 *
 * @author ignacio
 */
public class TestModified {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Throwable {
        Callback.listener = new Listener() {
            ThreadLocal<LinkedList> tl = new ThreadLocal();

            {
                tl.set(new LinkedList());
            }

            public Object onStart(FrameData fd) {
                LinkedList list = tl.get();
                list.push(fd);
                return null;
            }

            public void onFinishedReturn(Object ret, FrameData fd) {
                LinkedList list = tl.get();
                list.pop();
            }

            public void onFinishedThrowable(Throwable th, FrameData fd) {
                LinkedList list = tl.get();
                list.pop();
            }

            public void onBeforeThrown(Throwable th, FrameData fd) {
            }

            public void init(String s) {
            }

        };

        TestModified t = new TestModified();
        long nano = System.nanoTime();
        t.fact(5000);
        System.out.println(System.nanoTime() - nano);

        showByteCode(TestModified.class);

    }

    public long tryCatch(long n) throws Throwable {
        long ret = 0;
        Throwable th = null;
        try {
            return ret;
        } catch (Throwable t) {
            th = t;
            throw t;
        } finally {
            Callback.onFinishedReturn(null, null);
        }
    }

    public long fact2(long n) throws Throwable {
        FrameData fd = FrameData.getInstance(this, "aaa", "aaad", new Object[]{n});
        Object r = Callback.onStart(fd);
        if (r != null) {
            return ((Long) r).longValue();
        }
        return 1;
    }

    public long fact(long n) throws Throwable {
        FrameData fd = FrameData.getInstance(this, "aaa", "aaad", new Object[]{n});
        Object r = Callback.onStart(fd);
        if (r != null) {
            return ((Long) r).longValue();
        }
        long ret = 0;
        Throwable th = null;
        try {
            if (n == 1) {
                ret = 1;
            } else {
                ret = fact(n - 1) * n;
            }
            return ret;
        } catch (Throwable t) {
            th = t;
            throw t;
        } finally {
            Callback.onFinishedReturn(ret, fd);
        }
    }

    public static void showByteCode(Class clazz) throws IOException {
        String className = clazz.getCanonicalName();
        String resourceName = className.replace('.', '/') + ".class";
        InputStream is = clazz.getClassLoader().getResourceAsStream(resourceName);
        byte[] bytes = IOUtils.toByteArray(is);
        Helper.viewByteCode(bytes);
    }
}
