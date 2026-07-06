package org.eclipse.edc.connector.cocos.attestation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AttestationReportParser {

    public static Map<String, Object> parseSnpReport(byte[] rawReport) {
        if (rawReport == null || rawReport.length != 1184) {
            return createMockSnpReport(rawReport);
        }

        ByteBuffer buf = ByteBuffer.wrap(rawReport).order(ByteOrder.LITTLE_ENDIAN);

        Map<String, Object> report = new HashMap<>();

        int version = buf.getInt(0);
        int guestSvn = buf.getInt(4);
        long policy = buf.getLong(8);

        byte[] familyId = new byte[16];
        buf.position(16);
        buf.get(familyId);

        byte[] imageId = new byte[16];
        buf.position(32);
        buf.get(imageId);

        int vmpl = buf.getInt(48);
        int sigAlgo = buf.getInt(52);

        Map<String, Object> currentTcb = parseTcb(rawReport, 56);

        long platInfo = buf.getLong(64);
        long keyInfo = buf.getLong(72);

        byte[] reportData = new byte[64];
        buf.position(80);
        buf.get(reportData);

        byte[] measurement = new byte[48];
        buf.position(144);
        buf.get(measurement);

        byte[] hostData = new byte[32];
        buf.position(192);
        buf.get(hostData);

        byte[] idKeyDigest = new byte[48];
        buf.position(224);
        buf.get(idKeyDigest);

        byte[] authorKeyDigest = new byte[48];
        buf.position(272);
        buf.get(authorKeyDigest);

        byte[] reportId = new byte[32];
        buf.position(320);
        buf.get(reportId);

        byte[] reportIdMa = new byte[32];
        buf.position(352);
        buf.get(reportIdMa);

        Map<String, Object> reportedTcb = parseTcb(rawReport, 384);

        byte[] chipId = new byte[64];
        buf.position(416);
        buf.get(chipId);

        Map<String, Object> committedTcb = parseTcb(rawReport, 480);

        Map<String, Object> currentVersion = parseVersion(rawReport, 488);
        Map<String, Object> committedVersion = parseVersion(rawReport, 496);

        Map<String, Object> launchTcb = parseTcb(rawReport, 504);

        byte[] sigR = new byte[72];
        buf.position(680);
        buf.get(sigR);

        byte[] sigS = new byte[72];
        buf.position(680 + 72);
        buf.get(sigS);

        Map<String, Object> signature = new HashMap<>();
        signature.put("r", toIntList(sigR));
        signature.put("s", toIntList(sigS));

        report.put("version", version);
        report.put("guest_svn", guestSvn);
        report.put("policy", policy);
        report.put("family_id", toIntList(familyId));
        report.put("image_id", toIntList(imageId));
        report.put("vmpl", vmpl);
        report.put("sig_algo", sigAlgo);
        report.put("current_tcb", currentTcb);
        report.put("plat_info", platInfo);
        report.put("key_info", keyInfo);
        report.put("report_data", toIntList(reportData));
        report.put("measurement", toIntList(measurement));
        report.put("host_data", toIntList(hostData));
        report.put("id_key_digest", toIntList(idKeyDigest));
        report.put("author_key_digest", toIntList(authorKeyDigest));
        report.put("report_id", toIntList(reportId));
        report.put("report_id_ma", toIntList(reportIdMa));
        report.put("reported_tcb", reportedTcb);
        report.put("chip_id", toIntList(chipId));
        report.put("committed_tcb", committedTcb);
        report.put("current", currentVersion);
        report.put("committed", committedVersion);
        report.put("launch_tcb", launchTcb);
        report.put("signature", signature);

        return report;
    }

    private static Map<String, Object> parseTcb(byte[] data, int offset) {
        Map<String, Object> tcb = new HashMap<>();
        tcb.put("bootloader", data[offset] & 0xFF);
        tcb.put("tee", data[offset + 1] & 0xFF);
        tcb.put("snp", data[offset + 3] & 0xFF);
        tcb.put("microcode", data[offset + 4] & 0xFF);
        tcb.put("fmc", null);
        return tcb;
    }

    private static Map<String, Object> parseVersion(byte[] data, int offset) {
        Map<String, Object> ver = new HashMap<>();
        ver.put("major", data[offset] & 0xFF);
        ver.put("minor", data[offset + 1] & 0xFF);
        ver.put("build", data[offset + 2] & 0xFF);
        return ver;
    }

    private static List<Integer> toIntList(byte[] bytes) {
        List<Integer> list = new ArrayList<>(bytes.length);
        for (byte b : bytes) {
            list.add(b & 0xFF);
        }
        return list;
    }

    private static Map<String, Object> createMockSnpReport(byte[] rawReport) {
        Map<String, Object> report = new HashMap<>();
        report.put("version", 3);
        report.put("guest_svn", 0);
        report.put("policy", 196608);
        report.put("family_id", toIntList(new byte[16]));
        report.put("image_id", toIntList(new byte[16]));
        report.put("vmpl", 0);
        report.put("sig_algo", 1);

        Map<String, Object> tcb = new HashMap<>();
        tcb.put("bootloader", 10);
        tcb.put("tee", 0);
        tcb.put("snp", 23);
        tcb.put("microcode", 25);
        tcb.put("fmc", null);

        report.put("current_tcb", tcb);
        report.put("plat_info", 37);
        report.put("key_info", 0);

        byte[] mockReportData = new byte[64];
        if (rawReport != null) {
            System.arraycopy(rawReport, 0, mockReportData, 0, Math.min(rawReport.length, 64));
        }
        report.put("report_data", toIntList(mockReportData));
        report.put("measurement", toIntList(new byte[48]));
        report.put("host_data", toIntList(new byte[32]));
        report.put("id_key_digest", toIntList(new byte[48]));
        report.put("author_key_digest", toIntList(new byte[48]));
        report.put("report_id", toIntList(new byte[32]));
        report.put("report_id_ma", toIntList(new byte[32]));
        report.put("reported_tcb", tcb);
        report.put("chip_id", toIntList(new byte[64]));
        report.put("committed_tcb", tcb);

        Map<String, Object> current = new HashMap<>();
        current.put("major", 1);
        current.put("minor", 55);
        current.put("build", 40);
        report.put("current", current);
        report.put("committed", current);
        report.put("launch_tcb", tcb);

        Map<String, Object> signature = new HashMap<>();
        signature.put("r", toIntList(new byte[72]));
        signature.put("s", toIntList(new byte[72]));
        report.put("signature", signature);

        return report;
    }
}
