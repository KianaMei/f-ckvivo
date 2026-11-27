import java.lang.reflect.Method;

public class UfsId {
    public static void main(String[] args) {
        try {
            Class<?> c = Class.forName("android.util.FtDeviceInfo");
            System.out.println("METHODS:");
            for (Method m : c.getDeclaredMethods()) {
                System.out.println("  " + m.getReturnType().getSimpleName() + " " + m.getName() + " params=" + m.getParameterCount());
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == String.class && !m.getName().startsWith("getEmmcCid")) {
                    try {
                        m.setAccessible(true);
                        Object v = m.invoke(null);
                        System.out.println("CALL " + m.getName() + " = " + v);
                    } catch (Throwable t) {
                        System.out.println("CALL " + m.getName() + " EXC " + t);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("ERR " + t);
        }
    }
}
