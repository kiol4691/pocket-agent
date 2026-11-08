import com.dsh.android.guard.CommandGuard;

public final class CgTest {
    static final String ZW = "\u200B";
    static final String[][] CASES = {
        // ln 规则
        {"ln -sf /tmp/k /root/.ssh/id_rsa", "BLOCK"},
        {"ln -sfn /dev/null /etc/hosts", "BLOCK"},
        {"ln -sf /tmp/evil /etc/passwd", "BLOCK"},
        {"ln -s /usr/bin/python3 /usr/local/bin/python3", "ALLOW"},
        {"ln -sf /usr/bin/python3 /usr/local/bin/python3", "ASK"},
        {"ln -s /root/.ssh/id_rsa.pub /root/workspace/pub", "ALLOW"},
        {"ln -sf /tmp/x /root/workspace/l", "ALLOW"},
        {"ln -s /tmp/x /root/.ssh/newkey", "BLOCK"},
        // 转义归一化
        {"rm -rf /et\\c/pass\\wd", "BLOCK"},
        {"rm -rf $'\\x2fetc\\x2fpasswd'", "BLOCK"},
        {"rm -rf /etc/" + ZW + "passwd", "BLOCK"},
        {"rm -rf ／ｅｔｃ／ｐａｓｓｗｄ", "BLOCK"},
        {"rm -rf '/etc/'pass\"wd\"", "BLOCK"},
        {"rm\\ -rf\\ /etc", "BLOCK"},
        {"X=$'\\x2fetc'; rm -rf \"$X/passwd\"", "BLOCK"},
        {"rm -rf /etc/\\057passwd", "BLOCK"},
        // 包装动词解包（此前一律 ALLOW）
        {"sudo rm -rf /", "BLOCK"},
        {"env rm -rf /", "BLOCK"},
        {"nice rm -rf /", "BLOCK"},
        {"nohup rm -rf /", "BLOCK"},
        {"command rm -rf /", "BLOCK"},
        {"time rm -rf /", "BLOCK"},
        {"exec rm -rf /", "BLOCK"},
        {"setsid rm -rf /", "BLOCK"},
        {"sudo -u root rm -rf /", "BLOCK"},
        {"env FOO=bar rm -rf /etc", "BLOCK"},
        {"sudo ln -sf /tmp/k /root/.ssh/id_rsa", "BLOCK"},
        {"nohup echo hi", "ALLOW"},
        // 回归
        {"rm -rf /", "BLOCK"},
        {"T=/; rm -rf \"$T\"", "BLOCK"},
        {"cd / && rm -rf *", "BLOCK"},
        {"make 2>/dev/null", "ALLOW"},
        {"ls shred.txt", "ALLOW"},
        {"rm -rf /tmp/x", "ALLOW"},
        {"echo hi 2>&1", "ALLOW"},
        {"truncate -s 0 /etc/passwd", "BLOCK"},
    };

    public static void main(String[] a) {
        int ok = 0, bad = 0;
        for (String[] c : CASES) {
            CommandGuard.Verdict v = CommandGuard.check(c[0]);
            String got = v.action.name();
            boolean pass = got.equals(c[1]);
            ok += pass ? 1 : 0;
            bad += pass ? 0 : 1;
            System.out.printf("%-5s %-6s want=%-6s %-16s %s%n",
                    pass ? "OK" : "FAIL", got, c[1], v.category, c[0]);
        }
        System.out.printf("total=%d ok=%d bad=%d%n", CASES.length, ok, bad);
    }
}
