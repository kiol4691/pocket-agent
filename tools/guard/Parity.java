import com.dsh.android.guard.RiskModel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Parity {
    public static void main(String[] args) throws Exception {
        RiskModel model = RiskModel.load(new File(args[0]));
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(args[1]), StandardCharsets.UTF_8));
        String line; int n = 0, bad = 0; double maxDiff = 0; String worst = "";
        while ((line = r.readLine()) != null) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            double py = Double.parseDouble(line.substring(0, tab));
            String cmd = new String(Base64.getDecoder().decode(line.substring(tab + 1)), StandardCharsets.UTF_8);
            double jv = model.score(cmd);
            double d = Math.abs(py - jv);
            n++;
            if (d > 1e-5) {
                bad++;
                if (d > maxDiff) { maxDiff = d; worst = cmd; }
                if (bad <= 10) System.out.printf("DIFF py=%.8f jv=%.8f  %s%n", py, jv, cmd);
            }
        }
        System.out.printf("n=%d  mismatch=%d  maxdiff=%.8f%n", n, bad, maxDiff);
        if (bad > 0) System.out.println("worst: " + worst);
    }
}
