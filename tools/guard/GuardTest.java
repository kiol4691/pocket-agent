import com.dsh.android.guard.CommandGuard;

public final class GuardTest {
    public static void main(String[] a) {
        String brace = "$" + "{B}";
        String[] cases = {
            "echo \"[V4 var]\"; R=rm; PATH=/root/workspace/fakebin:$PATH $R -rf /",
            "R=rm; $R -rf /",
            "R=rm; $R -rf /usr",
            "A=rm; B=$A; " + brace + " -rf /",
            "PATH=/root/workspace/fakebin:$PATH ls -la",
            "rm -rf /",
            "rm -rf /tmp/x",
            "T=/; rm -rf $T",
            "X=rm -rf /; $X",
        };
        for (String c : cases) {
            CommandGuard.Verdict v = CommandGuard.check(c);
            System.out.printf("%-6s %-18s %s%n     %s%n", v.action, v.category, c, v.reason);
        }
    }
}
