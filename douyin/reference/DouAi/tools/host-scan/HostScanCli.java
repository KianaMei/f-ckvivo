package com.douai.module.scan;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Uses the production matcher against an APK as data; never loads host classes. */
public final class HostScanCli {
    private HostScanCli() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("Usage: HostScanCli host.apk rules.tsv report.tsv [--require-unique]");
        }
        File apk = new File(args[0]);
        HostRuleSet rules;
        try (FileInputStream input = new FileInputStream(args[1])) {
            rules = HostRuleSet.parse(input);
        }
        HostScanReport report = new HostScanner().scan(Collections.singletonList(apk), rules);
        boolean allUnique = true;
        try (PrintWriter writer = new PrintWriter(args[2], StandardCharsets.UTF_8)) {
            writer.println("# fingerprint=" + rules.getFingerprint());
            writer.println("# " + report.summary());
            writer.println("id\tfeature\tstatus\tcount\tcandidate");
            for (RuleScanResult result : report.getRuleResults()) {
                allUnique &= result.getStatus() == RuleScanResult.Status.UNIQUE;
                if (result.getCandidates().isEmpty()) {
                    writer.printf("%s\t%s\t%s\t0\t%n", result.getRuleId(), result.getFeature(), result.getStatus());
                } else {
                    for (HostCandidate candidate : result.getCandidates()) {
                        writer.printf("%s\t%s\t%s\t%d\t%s%n", result.getRuleId(), result.getFeature(),
                                result.getStatus(), result.getCandidates().size(), candidate);
                    }
                }
            }
        }
        System.out.println("fingerprint=" + rules.getFingerprint());
        System.out.println(report.summary());
        if (args.length == 4 && "--require-unique".equals(args[3]) && !allUnique) System.exit(2);
    }
}
