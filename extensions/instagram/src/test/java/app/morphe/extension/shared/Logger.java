package app.morphe.extension.shared;

/**
 * Test-source stand-in for {@code app.morphe.extension.shared.Logger} from the {@code compileOnly}
 * morphe-extensions-library artifact. Test classes come first on the unit-test classpath, so this
 * shadows the real one, which reads app settings.
 */
public final class Logger {

    public interface LogMessage {
        String buildMessageString();
    }

    private Logger() {
    }

    public static void printInfo(LogMessage message) {
        print("INFO", null, message);
    }

    public static void printInfo(LogMessage message, Exception exception) {
        print("INFO", exception, message);
    }

    public static void printException(LogMessage message) {
        print("ERROR", null, message);
    }

    public static void printException(LogMessage message, Throwable throwable) {
        print("ERROR", throwable, message);
    }

    private static void print(String level, Throwable throwable, LogMessage message) {
        System.err.println(level + " [Instagram test logger]: "
                + (message == null ? null : message.buildMessageString()));
        if (throwable != null) {
            throwable.printStackTrace(System.err);
        }
    }
}
