import com.dsh.android.guard.CommandGuard;

public final class GuardTest2 {
    public static void main(String[] a) {
        String[] cases = {
            "X=\"rm -rf /\"; $X",
            "X='rm -rf /'; $X",
            "R=rm; $R -rf /",
            "echo hi; rm -rf /",
        };
        for (String c : cases) {
            CommandGuard.Verdict v = CommandGuard.check(c);
            System.out.printf("%-6s %-18s %s%n", v.action, v.category, c);
        }
    }
}
