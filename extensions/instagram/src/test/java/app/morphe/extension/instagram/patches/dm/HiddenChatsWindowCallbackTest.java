package app.morphe.extension.instagram.patches.dm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.view.Window;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class HiddenChatsWindowCallbackTest {

    @Test
    public void unwrapRestoresOnlyItsOwnTarget() {
        Window.Callback other = (Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class},
                (proxy, method, args) -> null);

        assertSame(other, HiddenChatsWindowCallback.unwrap(new HiddenChatsWindowCallback(null, other)));
        assertSame(other, HiddenChatsWindowCallback.unwrap(other));
        assertNull(HiddenChatsWindowCallback.unwrap(null));
    }

    @Test
    public void forwardsEveryWindowCallbackMethodWithItsArgumentsAndResult() throws Exception {
        List<String> calls = new ArrayList<>();
        Window.Callback target = (Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class},
                (proxy, method, args) -> {
                    calls.add(method.getName() + Arrays.toString(args == null ? new Object[0] : args));
                    Class<?> returned = method.getReturnType();
                    if (returned == boolean.class) return Boolean.TRUE;
                    return returned.isInterface() ? stub(returned) : null;
                });
        HiddenChatsWindowCallback callback = new HiddenChatsWindowCallback(null, target);

        for (Method method : Window.Callback.class.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == int.class) args[i] = 7;
                else if (types[i] == boolean.class) args[i] = true;
                // Interface arguments get a distinct object, so a dropped or swapped argument shows in the call log.
                else if (types[i].isInterface()) args[i] = stub(types[i]);
            }
            // Declared here, so default methods of the interface are forwarded too.
            Method own = HiddenChatsWindowCallback.class.getDeclaredMethod(method.getName(), types);
            calls.clear();

            Object result = own.invoke(callback, args);

            assertEquals(method.toString(),
                    Collections.singletonList(method.getName() + Arrays.toString(args)), calls);
            if (method.getReturnType() == boolean.class) assertEquals(method.toString(), Boolean.TRUE, result);
            if (method.getReturnType().isInterface()) assertEquals(method.toString(), stub(method.getReturnType()), result);
        }
    }

    // One shared object per interface type, compared by identity through equals.
    private static final java.util.Map<Class<?>, Object> STUBS = new java.util.HashMap<>();

    private static Object stub(Class<?> type) {
        return STUBS.computeIfAbsent(type, t -> Proxy.newProxyInstance(t.getClassLoader(), new Class<?>[]{t},
                (proxy, method, args) -> {
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("toString")) return t.getSimpleName() + "@stub";
                    return null;
                }));
    }
}
