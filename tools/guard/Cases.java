import com.dsh.android.guard.CommandGuard;

public final class Cases {
    static String D = "$";
    public static void main(String[] a) {
        String[] cases = {
            // 用户报的漏判
            "T=/; rm -rf \"" + D + "T\"",
            "X=/; rm --recursive --force \"" + D + "X\"",
            "X=/; find \"" + D + "X\" -delete",
            "rm -rf \"" + D + "HOME\"/",
            "rm -rf \"" + D + "(printf /)\"",
            "cd / && rm -rf \"" + D + "PWD\"",
            // 用户报的误判
            "make 2>/dev/null",
            "ls shred.txt",
            "grep -r foo . 2>/dev/null",
            // 理由错
            "truncate -s 0 /etc/passwd",
            // 回归
            "rm -rf /", "rm -rf /tmp/x", "echo hi 2>&1",
            "ls > /dev/null", "cmd >/dev/null 2>&1",
            "rm -f /tmp/x 2>/dev/null",
        };
        int bad = 0;
        for (String c : cases) {
            CommandGuard.Verdict v = CommandGuard.check(c);
            System.out.printf("%-6s %-18s %s%n        %s%n", v.action, v.category, c, v.reason);
        }
    }
}
